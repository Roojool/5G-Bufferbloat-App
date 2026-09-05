package com.bufferbloatshaper.vpn

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import com.bufferbloatshaper.model.AppRoutingMode
import com.bufferbloatshaper.model.ShaperConfig
import com.bufferbloatshaper.model.VpnRuntimeMetrics
import com.bufferbloatshaper.model.VpnRuntimeState
import com.bufferbloatshaper.model.VpnRuntimeStateStore
import com.bufferbloatshaper.model.VpnRuntimeStatus
import com.bufferbloatshaper.nativeengine.NativeEngineBridge
import com.bufferbloatshaper.nativeengine.SocketProtector
import com.bufferbloatshaper.util.Notifications
import com.bufferbloatshaper.util.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lifecycle owner for the local Android VPN interface.
 *
 * Production traffic can be routed only through a verified native engine
 * contract. The unsafe hand-written Kotlin relay was removed rather than kept
 * as an activation fallback. If the native engine is absent or unhealthy,
 * this service fails before establishing a TUN route so the device continues
 * using its ordinary network.
 */
class ShaperVpnService : VpnService() {

    private val commandScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lifecycleMutex = Mutex()

    private var vpnInterface: ParcelFileDescriptor? = null
    private var nativeSession: NativeEngineBridge.Session? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var metricsJob: Job? = null
    private var runtimeGeneration = 0L

    @Volatile
    private var runtimeRunning = false

    /** Disabled before teardown/revocation so a late native socket cannot escape. */
    @Volatile
    private var socketProtectionAllowed = false

    @Volatile
    var config: ShaperConfig = ShaperConfig()
        private set

    private lateinit var notifications: Notifications

    override fun onCreate() {
        super.onCreate()
        notifications = Notifications(this)
        notifications.createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val requested = configFrom(intent)
                commandScope.launch {
                    lifecycleMutex.withLock {
                        startOrUpdateLocked(requested, startId, allowStart = true)
                    }
                }
            }

            ACTION_UPDATE_CONFIG -> {
                val requested = configFrom(intent)
                commandScope.launch {
                    lifecycleMutex.withLock {
                        startOrUpdateLocked(requested, startId, allowStart = false)
                    }
                }
            }

            ACTION_STOP -> {
                commandScope.launch {
                    lifecycleMutex.withLock {
                        stopLocked("Shaping was stopped by the user.", stopService = true)
                    }
                }
            }
        }

        // Never let Android restart a VPN using stale configuration.
        return START_NOT_STICKY
    }

    /**
     * Starts or atomically updates the native engine. Route/MTU changes need a
     * fresh TUN, while rate/AQM configuration can be updated in place.
     */
    private suspend fun startOrUpdateLocked(
        requested: ShaperConfig,
        startId: Int,
        allowStart: Boolean
    ) {
        val validationErrors = requested.validationErrors()
        if (validationErrors.isNotEmpty()) {
            failBeforeStart(requested, validationErrors.joinToString(" "), startId)
            return
        }

        if (!runtimeRunning && !allowStart) {
            config = requested.copy(isActive = false)
            VpnRuntimeStateStore.publish(
                VpnRuntimeState(
                    status = VpnRuntimeStatus.STOPPED,
                    generation = runtimeGeneration,
                    detail = "Configuration saved. Start shaping when you are ready.",
                    config = config,
                    nativeEngineAvailable = NativeEngineBridge.capability().available,
                    ipv6Supported = false
                )
            )
            return
        }

        val capability = NativeEngineBridge.capability()
        if (!capability.available) {
            // An engine can become unavailable between an active session and
            // an update. Tear down first so the UI never reports failure
            // while a stale TUN, worker, or wake lock remains alive.
            releaseRuntimeLocked()
            config = requested.copy(isActive = false)
            VpnRuntimeStateStore.publish(
                VpnRuntimeState(
                    status = VpnRuntimeStatus.UNSUPPORTED,
                    generation = runtimeGeneration,
                    detail = "Shaping was not started. ${capability.detail}",
                    recoverableError = "Install a build with a verified native packet engine before enabling shaping.",
                    config = config,
                    nativeEngineAvailable = false,
                    ipv6Supported = false
                )
            )
            stopSelfResult(startId)
            return
        }

        if (!runtimeRunning) {
            startLocked(requested, startId, capability)
            return
        }

        if (requiresTunnelRestart(config, requested)) {
            stopLocked("Restarting the local VPN to apply routing changes.", stopService = false)
            startLocked(requested, startId, capability)
            return
        }

        val session = nativeSession
        if (session == null) {
            failLocked("The native engine session disappeared unexpectedly.", startId)
            return
        }

        NativeEngineBridge.update(session, requested).fold(
            onSuccess = {
                config = requested.copy(isActive = true)
                VpnRuntimeStateStore.update { state ->
                    state.copy(
                        status = VpnRuntimeStatus.RUNNING,
                        detail = "Shaping is active with the updated local configuration.",
                        recoverableError = null,
                        config = config
                    )
                }
            },
            onFailure = { error ->
                failLocked("Could not update the native engine: ${error.userMessage()}", startId)
            }
        )
    }

    private suspend fun startLocked(
        requested: ShaperConfig,
        startId: Int,
        capability: NativeEngineBridge.Capability
    ) {
        val generation = ++runtimeGeneration
        VpnRuntimeStateStore.publish(
            VpnRuntimeState(
                status = VpnRuntimeStatus.STARTING,
                generation = generation,
                detail = "Checking local VPN and native engine readiness…",
                config = requested.copy(isActive = false),
                nativeEngineAvailable = capability.available,
                ipv6Supported = false
            )
        )

        try {
            startForeground(Notifications.NOTIFICATION_ID, notifications.buildNotification())

            val builder = Builder()
                .setSession("Bufferbloat Shaper")
                .addAddress(IPV4_TUN_ADDRESS, IPV4_PREFIX_LENGTH)
                .addRoute(IPV4_DEFAULT_ROUTE, 0)
                // Do not set a resolver. The future native engine must forward
                // ordinary DNS traffic to the resolver selected by the device.
                .setMtu(requested.mtu)
                .setBlocking(true)

            applyAppRouting(builder, requested)

            val establishedTunnel = builder.establish()
                ?: throw IllegalStateException(
                    "Android could not establish the local VPN. Another VPN, work-profile policy, or device setting may be using it."
                )

            // The native ABI receives a borrowed FD and must duplicate it on a
            // successful start. Kotlin remains the sole owner of this PFD.
            // Give a future engine only a per-socket protection operation. It
            // must call this after socket() and before bind/connect/send so it
            // cannot loop direct sockets back into this VPN.
            socketProtectionAllowed = true
            val session = NativeEngineBridge.start(
                establishedTunnel.fd,
                requested,
                SocketProtector { socketFd ->
                    socketProtectionAllowed && protect(socketFd)
                }
            ).getOrElse { error ->
                establishedTunnel.close()
                throw error
            }

            vpnInterface = establishedTunnel
            nativeSession = session
            config = requested.copy(isActive = true)
            runtimeRunning = true
            acquireWakeLock()
            startMetricsCollection(generation)

            VpnRuntimeStateStore.publish(
                VpnRuntimeState(
                    status = VpnRuntimeStatus.RUNNING,
                    generation = generation,
                    detail = "Local IPv4 shaping is active. IPv6 remains disabled until the native engine passes dual-stack tests.",
                    config = config,
                    startedAtMs = System.currentTimeMillis(),
                    nativeEngineAvailable = true,
                    ipv6Supported = false
                )
            )
            Log.i(TAG, "Native VPN engine started (generation=$generation)")
        } catch (error: Throwable) {
            failLocked("Could not start shaping: ${error.userMessage()}", startId)
        }
    }

    private fun applyAppRouting(builder: Builder, requested: ShaperConfig) {
        val packages = requested.appRoutingPolicy.normalizedPackages()
        try {
            when (requested.appRoutingPolicy.mode) {
                AppRoutingMode.ALL_APPS -> Unit
                AppRoutingMode.ONLY_SELECTED_APPS -> packages.forEach(builder::addAllowedApplication)
                AppRoutingMode.EXCLUDE_SELECTED_APPS -> packages.forEach(builder::addDisallowedApplication)
            }
        } catch (_: Exception) {
            // Do not pre-query installed packages: package visibility rules can
            // make a valid app look absent. Builder validation occurs before
            // establish(), so normal traffic remains untouched on failure.
            throw IllegalArgumentException(
                "One selected app cannot be routed on this Android build. Review the package name and app-routing mode."
            )
        }
    }

    private fun requiresTunnelRestart(current: ShaperConfig, updated: ShaperConfig): Boolean =
        current.appRoutingPolicy != updated.appRoutingPolicy || current.mtu != updated.mtu

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BufferbloatShaper::NativeEngine"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    /** Pull aggregate metrics only; this never captures traffic payloads or DNS names. */
    private fun startMetricsCollection(generation: Long) {
        metricsJob?.cancel()
        metricsJob = commandScope.launch {
            var consecutiveMisses = 0
            var previousMetrics: NativeEngineBridge.Metrics? = null
            var previousSampleAtMs = 0L
            while (isActive && runtimeRunning && generation == runtimeGeneration) {
                delay(METRICS_INTERVAL_MS)
                val session = nativeSession ?: break
                val health = NativeEngineBridge.health(session)
                if (health == null || health.state != NativeEngineBridge.ENGINE_STATE_RUNNING ||
                    health.lastStatus != NativeEngineBridge.STATUS_OK
                ) {
                    failForNativeHealth(generation, health?.state, health?.lastStatus)
                    return@launch
                }
                val event = NativeEngineBridge.pollEvent(session)
                if (event != null && event.eventStatus != NativeEngineBridge.STATUS_OK) {
                    failForNativeHealth(generation, health.state, event.eventStatus)
                    return@launch
                }
                val metrics = NativeEngineBridge.metrics(session)
                if (metrics == null || metrics.state != NativeEngineBridge.ENGINE_STATE_RUNNING) {
                    consecutiveMisses++
                    if (consecutiveMisses >= MAX_METRIC_MISSES) {
                        commandScope.launch {
                            lifecycleMutex.withLock {
                                if (runtimeRunning && generation == runtimeGeneration) {
                                    failLocked("The native engine health check stopped responding.", null)
                                }
                            }
                        }
                        return@launch
                    }
                    continue
                }
                consecutiveMisses = 0
                val nowMs = System.currentTimeMillis()
                val prior = previousMetrics
                val elapsedMs = metrics.sampledAtMs - previousSampleAtMs
                val measuredEgress = if (prior != null && elapsedMs > 0) {
                    ((metrics.bytesIn - prior.bytesIn).coerceAtLeast(0) * 1_000.0) / elapsedMs
                } else {
                    null
                }
                val measuredIngress = if (prior != null && elapsedMs > 0) {
                    ((metrics.bytesOut - prior.bytesOut).coerceAtLeast(0) * 1_000.0) / elapsedMs
                } else {
                    null
                }
                previousMetrics = metrics
                previousSampleAtMs = metrics.sampledAtMs
                VpnRuntimeStateStore.update { state ->
                    if (state.generation != generation) state else state.copy(
                        metrics = VpnRuntimeMetrics(
                            egressRateBytesPerSec = config.egressRateBytesPerSec.toDouble(),
                            ingressTargetBytesPerSec = config.ingressRateBytesPerSec,
                            measuredEgressBytesPerSec = measuredEgress,
                            measuredIngressBytesPerSec = measuredIngress,
                            bytesEgressed = metrics.bytesIn,
                            packetsEgressed = metrics.packetsIn,
                            queuedPackets = 0,
                            queuedBytes = metrics.queuedBytes,
                            droppedPackets = metrics.aqmDrops,
                            activeFlows = metrics.activeFlows.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                            measuredAtMs = nowMs
                        )
                    )
                }
                updateNotification()
            }
        }
    }

    private fun failForNativeHealth(generation: Long, state: Int?, status: Int?) {
        commandScope.launch {
            lifecycleMutex.withLock {
                if (runtimeRunning && generation == runtimeGeneration) {
                    failLocked(
                        "The native engine reported an unhealthy state (state=${state ?: "unavailable"}, status=${status ?: "unavailable"}).",
                        null
                    )
                }
            }
        }
    }

    private fun updateNotification() {
        val state = VpnRuntimeStateStore.state.value
        val upload = state.metrics.measuredEgressBytesPerSec?.let {
            "%.1f Mbps".format(it * 8 / 1_000_000)
        } ?: "—"
        val download = state.metrics.measuredIngressBytesPerSec?.let {
            "%.1f Mbps".format(it * 8 / 1_000_000)
        } ?: "—"
        notifications.updateNotification(upload, download)
    }

    private suspend fun failBeforeStart(requested: ShaperConfig, reason: String, startId: Int) {
        // This method is also reached by a bad configuration update while a
        // future native session is running. Always release before publishing
        // ERROR so Android's routes and our ownership state agree.
        releaseRuntimeLocked()
        config = requested.copy(isActive = false)
        VpnRuntimeStateStore.publish(
            VpnRuntimeState(
                status = VpnRuntimeStatus.ERROR,
                generation = runtimeGeneration,
                detail = "Shaping was not started.",
                recoverableError = reason,
                config = config,
                nativeEngineAvailable = NativeEngineBridge.capability().available,
                ipv6Supported = false
            )
        )
        stopSelfResult(startId)
    }

    private suspend fun failLocked(reason: String, startId: Int?) {
        releaseRuntimeLocked()
        VpnRuntimeStateStore.publish(
            VpnRuntimeState(
                status = VpnRuntimeStatus.ERROR,
                generation = runtimeGeneration,
                detail = "Shaping stopped safely.",
                recoverableError = reason,
                config = config.copy(isActive = false),
                nativeEngineAvailable = NativeEngineBridge.capability().available,
                ipv6Supported = false
            )
        )
        if (startId != null) stopSelfResult(startId) else stopSelf()
    }

    /** Stop and join native/Kotlin work before Kotlin closes the TUN descriptor. */
    private suspend fun stopLocked(detail: String, stopService: Boolean) {
        val wasActive = runtimeRunning || nativeSession != null || vpnInterface != null
        if (wasActive) {
            VpnRuntimeStateStore.update { it.copy(status = VpnRuntimeStatus.STOPPING, detail = detail) }
        }
        releaseRuntimeLocked()
        config = config.copy(isActive = false)
        VpnRuntimeStateStore.publish(
            VpnRuntimeState(
                status = VpnRuntimeStatus.STOPPED,
                generation = runtimeGeneration,
                detail = detail,
                config = config,
                nativeEngineAvailable = NativeEngineBridge.capability().available,
                ipv6Supported = false
            )
        )
        if (stopService) stopSelf()
    }

    private suspend fun releaseRuntimeLocked() {
        // Close the gate before asking native workers to stop. A correct engine
        // joins those workers before NativeEngineBridge.close returns.
        socketProtectionAllowed = false
        runtimeRunning = false

        val job = metricsJob
        metricsJob = null
        job?.cancelAndJoin()

        // Native stop is required to join native workers before the PFD closes.
        NativeEngineBridge.close(nativeSession)
        nativeSession = null

        try {
            vpnInterface?.close()
        } catch (error: Throwable) {
            Log.w(TAG, "Could not close VPN interface", error)
        } finally {
            vpnInterface = null
        }

        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onRevoke() {
        socketProtectionAllowed = false
        commandScope.launch {
            lifecycleMutex.withLock {
                stopLocked("VPN permission was revoked by Android.", stopService = true)
            }
        }
        super.onRevoke()
    }

    override fun onDestroy() {
        runBlocking {
            lifecycleMutex.withLock {
                releaseRuntimeLocked()
            }
        }
        commandScope.cancel()
        super.onDestroy()
    }

    private fun configFrom(intent: Intent): ShaperConfig {
        val persisted = Preferences(this).loadConfig()
        var requested = persisted
        if (intent.hasExtra(EXTRA_UPLOAD_MBPS)) {
            requested = requested.copy(
                egressRateBytesPerSec = ShaperConfig.mbpsToBytesSec(
                    intent.getDoubleExtra(EXTRA_UPLOAD_MBPS, 0.0)
                )
            )
        }
        if (intent.hasExtra(EXTRA_DOWNLOAD_MBPS)) {
            requested = requested.copy(
                ingressRateBytesPerSec = ShaperConfig.mbpsToBytesSec(
                    intent.getDoubleExtra(EXTRA_DOWNLOAD_MBPS, 0.0)
                )
            )
        }
        if (intent.hasExtra(EXTRA_AUTO_CALIBRATE)) {
            requested = requested.copy(
                autoCalibrationEnabled = intent.getBooleanExtra(EXTRA_AUTO_CALIBRATE, false)
            )
        }
        if (intent.hasExtra(EXTRA_SMART_MODE)) {
            requested = requested.copy(
                smartModeEnabled = intent.getBooleanExtra(EXTRA_SMART_MODE, false)
            )
        }
        if (intent.hasExtra(EXTRA_HEADROOM)) {
            requested = requested.copy(
                headroomFactor = intent.getDoubleExtra(EXTRA_HEADROOM, requested.headroomFactor)
            )
        }
        return requested
    }

    private fun Throwable.userMessage(): String = message?.takeIf { it.isNotBlank() }
        ?: javaClass.simpleName

    companion object {
        private const val TAG = "ShaperVpnService"
        private const val IPV4_TUN_ADDRESS = "10.0.0.2"
        private const val IPV4_PREFIX_LENGTH = 32
        private const val IPV4_DEFAULT_ROUTE = "0.0.0.0"
        private const val METRICS_INTERVAL_MS = 1_000L
        private const val MAX_METRIC_MISSES = 3

        const val ACTION_START = "com.bufferbloatshaper.START"
        const val ACTION_STOP = "com.bufferbloatshaper.STOP"
        const val ACTION_UPDATE_CONFIG = "com.bufferbloatshaper.UPDATE_CONFIG"

        const val EXTRA_UPLOAD_MBPS = "upload_mbps"
        const val EXTRA_DOWNLOAD_MBPS = "download_mbps"
        const val EXTRA_AUTO_CALIBRATE = "auto_calibrate"
        const val EXTRA_SMART_MODE = "smart_mode"
        const val EXTRA_HEADROOM = "headroom"

        fun updateConfiguration(context: android.content.Context) {
            context.startService(Intent(context, ShaperVpnService::class.java).apply {
                action = ACTION_UPDATE_CONFIG
            })
        }
    }
}
