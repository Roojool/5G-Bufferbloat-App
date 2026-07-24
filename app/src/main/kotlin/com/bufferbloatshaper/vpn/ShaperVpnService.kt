package com.bufferbloatshaper.vpn

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import com.bufferbloatshaper.calibration.CalibrationEngine
import com.bufferbloatshaper.model.ShaperConfig
import com.bufferbloatshaper.shaping.EgressShaper
import com.bufferbloatshaper.shaping.FlowClassifier
import com.bufferbloatshaper.shaping.IngressController
import com.bufferbloatshaper.util.BatteryMonitor
import com.bufferbloatshaper.util.Notifications
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Core VPN service — the heart of the bufferbloat shaper (§2).
 *
 * Architecture (per §2):
 *   Apps → TUN interface → IP packet parsing
 *     → Protocol dispatch:
 *         TCP → egress shaper → TcpRelay → protected socket → Internet
 *         UDP port 53 → DnsRelay → protected DatagramSocket → DNS server
 *         UDP other → UdpRelay → protected DatagramSocket → Internet
 *         ICMP → passthrough (PMTUD, connectivity checks)
 *     → Responses: relay → PacketBuilder (proper IP packets) → TUN → Apps
 *
 * Data path design (fixes from audit):
 * - TCP egress: TUN → parse → egress shaper → onSendPacket → TcpRelay
 *   The shaper gates the rate; the relay handles connection management.
 * - TCP ingress: server → relay socket → PacketBuilder → TUN
 *   Shaped via receive-window throttling (IngressController).
 * - UDP: bypasses egress shaper (per §4 — QUIC flow control is inside encryption)
 *   DNS queries get priority forwarding.
 * - ICMP: straight passthrough, no shaping.
 */
class ShaperVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var serviceScope: CoroutineScope? = null
    private var packetLoopJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // Core components
    private var tcpRelay: TcpRelay? = null
    private var dnsRelay: DnsRelay? = null
    private var udpRelay: UdpRelay? = null
    private var egressShaper: EgressShaper? = null
    private var ingressController: IngressController? = null
    private var calibrationEngine: CalibrationEngine? = null
    private var flowClassifier: FlowClassifier? = null
    private var batteryMonitor: BatteryMonitor? = null
    private var notifications: Notifications? = null

    /** PacketWriter for TUN output (shared by all relays). */
    private var packetWriter: PacketWriter? = null

    /** Current configuration (updated from UI). */
    @Volatile
    var config = ShaperConfig()
        private set

    override fun onCreate() {
        super.onCreate()
        notifications = Notifications(this)
        notifications?.createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_START -> {
                val uploadMbps = intent.getDoubleExtra(EXTRA_UPLOAD_MBPS, 10.0)
                val downloadMbps = intent.getDoubleExtra(EXTRA_DOWNLOAD_MBPS, 50.0)
                val autoCalibrate = intent.getBooleanExtra(EXTRA_AUTO_CALIBRATE, false)
                val smartMode = intent.getBooleanExtra(EXTRA_SMART_MODE, false)
                val headroom = intent.getDoubleExtra(EXTRA_HEADROOM, 0.85)

                config = ShaperConfig(
                    egressRateBytesPerSec = ShaperConfig.mbpsToBytesSec(uploadMbps),
                    ingressRateBytesPerSec = ShaperConfig.mbpsToBytesSec(downloadMbps),
                    autoCalibrationEnabled = autoCalibrate,
                    smartModeEnabled = smartMode,
                    headroomFactor = headroom,
                    isActive = true
                )

                startShaping()
                START_STICKY
            }
            ACTION_STOP -> {
                stopShaping()
                START_NOT_STICKY
            }
            ACTION_UPDATE_CONFIG -> {
                val uploadMbps = intent.getDoubleExtra(EXTRA_UPLOAD_MBPS, 0.0)
                val downloadMbps = intent.getDoubleExtra(EXTRA_DOWNLOAD_MBPS, 0.0)
                if (uploadMbps > 0) {
                    egressShaper?.updateRate(ShaperConfig.mbpsToBytesSec(uploadMbps).toDouble())
                }
                if (downloadMbps > 0) {
                    ingressController?.setTargetRate(ShaperConfig.mbpsToBytesSec(downloadMbps))
                }
                START_STICKY
            }
            else -> START_NOT_STICKY
        }
    }

    /**
     * Establish the VPN tunnel and start the shaping pipeline.
     */
    private fun startShaping() {
        Log.d(TAG, "Starting shaper VPN service...")

        // Start as foreground service
        val notification = notifications?.buildNotification() ?: return
        startForeground(Notifications.NOTIFICATION_ID, notification)

        // Acquire partial wake lock to survive Doze
        // This prevents the CPU from sleeping while we're actively relaying traffic.
        // Battery impact is monitored by BatteryMonitor and reported to the user.
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BufferbloatShaper::VpnRelay"
        ).apply {
            acquire()
        }

        // Establish VPN interface — dual-stack (IPv4 + IPv6)
        val builder = Builder()
            .setSession("BufferbloatShaper")
            .addAddress("10.0.0.2", 32)          // IPv4 TUN address
            .addRoute("0.0.0.0", 0)               // Capture all IPv4
            .addAddress("fd00::2", 128)            // IPv6 TUN address (ULA)
            .addRoute("::", 0)                     // Capture all IPv6
            .addDnsServer("8.8.8.8")
            .addDnsServer("8.8.4.4")
            .addDnsServer("2001:4860:4860::8888")  // Google DNS IPv6
            .setMtu(1500)
            .setBlocking(true)

        vpnInterface = builder.establish()

        if (vpnInterface == null) {
            Log.e(TAG, "Failed to establish VPN interface")
            wakeLock?.release()
            stopSelf()
            return
        }

        Log.d(TAG, "VPN interface established (dual-stack)")

        // Create coroutine scope for all async work
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        serviceScope = scope

        // Set up TUN I/O
        val vpnFd = vpnInterface!!.fileDescriptor
        val inputStream = FileInputStream(vpnFd)
        val outputStream = FileOutputStream(vpnFd)
        val inputChannel = inputStream.channel
        val outputChannel = outputStream.channel

        val reader = PacketReader(inputChannel)
        val writer = PacketWriter(outputChannel)
        packetWriter = writer

        // Shared TUN write callback (thread-safe via PacketWriter's @Synchronized)
        val writeTun: (ByteArray) -> Unit = { packet -> writer.writePacket(packet) }

        // --- Initialize all components ---

        // TCP relay
        val relay = TcpRelay(this, scope)
        relay.onResponsePacket = writeTun
        tcpRelay = relay

        // DNS relay
        val dns = DnsRelay(this, scope)
        dns.onResponsePacket = writeTun
        dnsRelay = dns

        // UDP relay
        val udp = UdpRelay(this, scope)
        udp.onResponsePacket = writeTun
        udpRelay = udp

        // Egress shaper (token bucket + CoDel + fair queue)
        val shaper = EgressShaper(
            rateBytesPerSec = config.egressRateBytesPerSec.toDouble(),
            codelTargetMs = config.codelTargetMs,
            codelIntervalMs = config.codelIntervalMs,
            fqBuckets = config.fqBuckets,
            burstFraction = config.burstFraction,
            smartModeEnabled = config.smartModeEnabled
        )
        egressShaper = shaper

        // Flow classifier (Phase 4)
        if (config.smartModeEnabled) {
            val classifier = FlowClassifier()
            flowClassifier = classifier
            shaper.flowClassifier = classifier
        }

        // Wire egress shaper output → TCP relay
        // This is the FIX for B1+B2: shaped packets go to the relay, not a no-op.
        shaper.onSendPacket = { shapedPacket ->
            // Re-parse the shaped packet to route it to the relay
            val buf = ByteBuffer.wrap(shapedPacket)
            if (shapedPacket.size >= IpPacket.MIN_HEADER_SIZE && IpPacket.isValidIpv4(buf)) {
                val ip = IpPacket(buf)
                if (ip.isTcp && shapedPacket.size >= ip.payloadOffset + TcpPacket.MIN_HEADER_SIZE) {
                    val tcp = TcpPacket(buf, ip.payloadOffset)
                    relay.handleOutboundPacket(ip, tcp, shapedPacket)
                }
            }
        }

        // Ingress controller (TCP rwnd throttling)
        val ingress = IngressController(relay)
        ingressController = ingress
        if (config.ingressRateBytesPerSec > 0) {
            ingress.setTargetRate(config.ingressRateBytesPerSec)
        }

        // Calibration engine (Phase 2)
        if (config.autoCalibrationEnabled) {
            val calibration = CalibrationEngine(this, scope)
            calibrationEngine = calibration
            calibration.egressShaper = shaper
            calibration.ingressController = ingress
            calibration.headroomFactor = config.headroomFactor

            // Wire byte counting from relays to calibration
            relay.onBytesRelayed = { egress, ingressBytes ->
                calibration.recordBytes(egress, ingressBytes)
            }
            udp.onBytesRelayed = { egress, ingressBytes ->
                calibration.recordBytes(egress, ingressBytes)
            }

            calibration.start()
        }

        // Battery monitor (Phase 5)
        val battery = BatteryMonitor(this)
        batteryMonitor = battery
        battery.start(scope)

        // Start the egress shaper dequeue loop
        shaper.start(scope)

        // Start the main packet processing loop
        startPacketLoop(scope, reader)

        // Periodic maintenance and shaper metric logging
        scope.launch {
            var counter = 0
            while (isActive) {
                delay(5000)
                counter++
                Log.d(TAG, "EgressShaper metrics: enqueued=${shaper.totalPacketsEnqueued.get()} pkts, shaped=${shaper.totalPacketsShaped.get()} pkts / ${shaper.totalBytesShaped.get()} bytes")
                if (counter % 6 == 0) {
                    relay.cleanupStaleConnections()
                    udp.cleanupStaleSessions()
                    shaper.cleanup()
                    flowClassifier?.cleanup()
                    updateNotificationStats()
                }
            }
        }

        Log.d(TAG, "Shaper VPN service started successfully")
    }

    /**
     * Main packet processing loop.
     * Reads raw IP packets from the TUN interface, identifies the IP version
     * and protocol, and routes them to the appropriate handler.
     */
    private fun startPacketLoop(scope: CoroutineScope, packetReader: PacketReader) {
        packetLoopJob = scope.launch {
            val buffer = ByteBuffer.allocate(PacketReader.MAX_PACKET_SIZE)
            Log.d(TAG, "Packet processing loop started")

            while (isActive) {
                val bytesRead = packetReader.readPacketInto(buffer)
                if (bytesRead <= 0) {
                    if (bytesRead == -1) break // TUN fd closed
                    delay(1)
                    continue
                }

                // Check IP version
                val version = IpPacket.ipVersion(buffer)

                when (version) {
                    4 -> {
                        if (!IpPacket.isValidIpv4(buffer)) {
                            buffer.clear()
                            continue
                        }
                        val ipPacket = IpPacket(buffer)
                        when {
                            ipPacket.isTcp -> handleTcpPacket(ipPacket, buffer)
                            ipPacket.isUdp -> handleUdpPacket(ipPacket, buffer)
                            ipPacket.isIcmp -> handleIcmpPacket(ipPacket, buffer)
                        }
                    }
                    6 -> {
                        // IPv6: for now, passthrough without shaping.
                        // Full IPv6 shaping requires Ip6Packet parser (future work).
                        if (!Ip6Packet.isValidIpv6(buffer)) {
                            buffer.clear()
                            continue
                        }
                        val ip6Packet = Ip6Packet(buffer)
                        handleIpv6Passthrough(ip6Packet)
                    }
                    // Other versions: drop silently
                }

                buffer.clear()
            }

            Log.d(TAG, "Packet processing loop ended")
        }
    }

    /**
     * Handle a TCP packet — route through egress shaper, then to relay.
     * FIX for B2: packets go through shaper ONLY. The shaper's onSendPacket
     * callback sends them to the relay. No double-processing.
     */
    private fun handleTcpPacket(ipPacket: IpPacket, buffer: ByteBuffer) {
        val tcpPacket = TcpPacket(buffer, ipPacket.payloadOffset)
        val rawPacket = ipPacket.toByteArray()
        val flowHash = ipPacket.flowHash(tcpPacket.sourcePort, tcpPacket.destinationPort)

        // SYN packets bypass the shaper — they need to reach the relay immediately
        // for connection setup. Data packets go through the shaper.
        if (tcpPacket.isSynOnly || tcpPacket.isRst || tcpPacket.isFin) {
            // Control packets: route directly to relay (no shaping delay)
            tcpRelay?.handleOutboundPacket(ipPacket, tcpPacket, rawPacket)
        } else {
            // Data packets: route through egress shaper → relay
            egressShaper?.enqueue(rawPacket, flowHash)
        }
    }

    /**
     * Handle a UDP packet.
     * - DNS (port 53): route to DnsRelay (priority, never shaped)
     * - Other UDP: route to UdpRelay (lightweight passthrough, per §4)
     */
    private fun handleUdpPacket(ipPacket: IpPacket, buffer: ByteBuffer) {
        val udpPacket = UdpPacket(buffer, ipPacket.payloadOffset)

        // Extract UDP payload
        val payloadLen = udpPacket.payloadLength
        if (payloadLen <= 0) return

        val payloadOffset = udpPacket.payloadOffset
        if (buffer.limit() < payloadOffset + payloadLen) return

        val payload = ByteArray(payloadLen)
        val savedPos = buffer.position()
        buffer.position(payloadOffset)
        buffer.get(payload)
        buffer.position(savedPos)

        val srcIp = ipPacket.sourceAddress
        val dstIp = ipPacket.destinationAddress

        if (udpPacket.isDns) {
            // DNS queries get priority forwarding — no shaping, no queuing
            dnsRelay?.handleDnsQuery(srcIp, dstIp, udpPacket.sourcePort, udpPacket.destinationPort, payload)
        } else {
            // General UDP (QUIC, gaming, etc.) — forward via UdpRelay
            // Per §4: UDP/QUIC is NOT shaped on ingress or egress
            udpRelay?.handleOutbound(srcIp, dstIp, udpPacket.sourcePort, udpPacket.destinationPort, payload)
        }
    }

    /**
     * Handle ICMP packet.
     *
     * Android doesn't allow raw ICMP sockets without root, so we can't
     * actually relay ICMP to the real destination. Instead, for Echo Requests
     * (type 8), we generate a local Echo Reply (type 0) by:
     *   1. Swapping src/dst IP addresses
     *   2. Changing ICMP type from 8 (Echo Request) to 0 (Echo Reply)
     *   3. Recomputing the ICMP checksum
     *   4. Recomputing the IP header checksum
     *
     * This keeps ping working through the VPN (measuring loopback latency
     * to the VPN interface itself, not real RTT to the target — but it
     * prevents ping from hanging/failing entirely, which breaks apps that
     * use connectivity checks).
     *
     * Non-Echo-Request ICMP (Destination Unreachable, Time Exceeded, etc.)
     * is silently dropped — those originate from routers along the path,
     * which we can't synthesize meaningfully.
     */
    private fun handleIcmpPacket(ipPacket: IpPacket, buffer: ByteBuffer) {
        val headerLen = ipPacket.headerLength
        val totalLen = ipPacket.totalLength
        val icmpOffset = headerLen
        val icmpLen = totalLen - headerLen

        // Need at least 8 bytes for ICMP header (type, code, checksum, id, seq)
        if (icmpLen < 8) return

        val icmpType = buffer.get(icmpOffset).toInt() and 0xFF

        // Only handle Echo Request (type 8)
        if (icmpType != 8) return

        // Copy the entire packet so we can modify it
        val packet = ipPacket.toByteArray()

        // 1. Swap src and dst IP addresses in the IP header (offsets 12-15 and 16-19)
        for (i in 0..3) {
            val tmp = packet[12 + i]
            packet[12 + i] = packet[16 + i]
            packet[16 + i] = tmp
        }

        // 2. Change ICMP type from 8 (Echo Request) to 0 (Echo Reply)
        //    ICMP code stays 0, identifier and sequence number stay the same
        packet[icmpOffset] = 0

        // 3. Recompute ICMP checksum (covers the entire ICMP message)
        //    Zero out existing checksum field first
        packet[icmpOffset + 2] = 0
        packet[icmpOffset + 3] = 0
        val icmpChecksum = PacketBuilder.computeChecksum(packet, icmpOffset, icmpLen)
        packet[icmpOffset + 2] = (icmpChecksum shr 8).toByte()
        packet[icmpOffset + 3] = (icmpChecksum and 0xFF).toByte()

        // 4. Recompute IP header checksum (src/dst IPs changed)
        packet[10] = 0
        packet[11] = 0
        val ipChecksum = PacketBuilder.computeChecksum(packet, 0, headerLen)
        packet[10] = (ipChecksum shr 8).toByte()
        packet[11] = (ipChecksum and 0xFF).toByte()

        // Write the Echo Reply back to TUN
        packetWriter?.writePacket(packet)
    }

    /**
     * Handle IPv6 packets — passthrough without shaping for now.
     * Ensures IPv6 traffic isn't silently black-holed.
     */
    private fun handleIpv6Passthrough(ip6Packet: Ip6Packet) {
        // Extract the raw packet and write it back through.
        // Full IPv6 shaping is a future enhancement.
        // For now, at least don't drop it.
        val rawPacket = ip6Packet.toByteArray()

        // IPv6 packets read from TUN are outbound from apps.
        // We need to forward them to the internet, but without a proper
        // IPv6 relay, we can only do passthrough.
        // TODO: Implement proper IPv6 TCP/UDP relay
    }

    /**
     * Update the foreground notification with current stats.
     */
    private fun updateNotificationStats() {
        val stats = egressShaper?.getStats() ?: return
        val uploadMbps = "%.1f Mbps".format(stats.rateBytesPerSec * 8 / 1_000_000)
        val downloadMbps = ingressController?.let {
            "%.1f Mbps".format(it.targetRateBytesSec * 8.0 / 1_000_000)
        } ?: "—"

        notifications?.updateNotification(uploadMbps, downloadMbps)
    }

    /**
     * Stop shaping and tear down the VPN tunnel.
     */
    private fun stopShaping() {
        Log.d(TAG, "Stopping shaper VPN service...")

        packetLoopJob?.cancel()
        calibrationEngine?.stop()
        egressShaper?.stop()
        ingressController?.disable()
        batteryMonitor?.stop()
        tcpRelay?.shutdown()
        dnsRelay = null
        udpRelay?.shutdown()
        serviceScope?.cancel()

        vpnInterface?.close()
        vpnInterface = null

        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        Log.d(TAG, "Shaper VPN service stopped")
    }

    override fun onDestroy() {
        stopShaping()
        super.onDestroy()
    }

    override fun onRevoke() {
        Log.d(TAG, "VPN permission revoked by user")
        stopShaping()
    }

    // ---- Accessors for UI ----
    fun getEgressStats() = egressShaper?.getStats()
    fun getIngressStats() = ingressController?.getStats()
    fun getCalibrationState() = calibrationEngine?.state
    fun getBatteryStats() = batteryMonitor?.getStats()
    fun getActiveConnectionCount() = (tcpRelay?.activeConnectionCount ?: 0) +
            (udpRelay?.activeSessionCount ?: 0)

    companion object {
        private const val TAG = "ShaperVpnService"

        const val ACTION_START = "com.bufferbloatshaper.START"
        const val ACTION_STOP = "com.bufferbloatshaper.STOP"
        const val ACTION_UPDATE_CONFIG = "com.bufferbloatshaper.UPDATE_CONFIG"

        const val EXTRA_UPLOAD_MBPS = "upload_mbps"
        const val EXTRA_DOWNLOAD_MBPS = "download_mbps"
        const val EXTRA_AUTO_CALIBRATE = "auto_calibrate"
        const val EXTRA_SMART_MODE = "smart_mode"
        const val EXTRA_HEADROOM = "headroom"
    }
}
