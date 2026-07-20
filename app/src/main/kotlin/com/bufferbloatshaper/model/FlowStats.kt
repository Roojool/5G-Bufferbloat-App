package com.bufferbloatshaper.model

/**
 * Statistics for a single network flow (identified by 5-tuple).
 */
data class FlowStats(
    /** Unique flow identifier (hash of 5-tuple). */
    val flowId: Int,

    /** Source IP address as string. */
    val srcIp: String,

    /** Source port. */
    val srcPort: Int,

    /** Destination IP address as string. */
    val dstIp: String,

    /** Destination port. */
    val dstPort: Int,

    /** Protocol number (6 = TCP, 17 = UDP). */
    val protocol: Int,

    /** Total bytes sent (egress) in this flow. */
    val bytesSent: Long = 0L,

    /** Total bytes received (ingress) in this flow. */
    val bytesReceived: Long = 0L,

    /** Total packets sent. */
    val packetsSent: Long = 0L,

    /** Total packets received. */
    val packetsReceived: Long = 0L,

    /** Packets dropped by CoDel AQM in this flow's queue. */
    val packetsDropped: Long = 0L,

    /** Current sojourn time in the egress queue (nanoseconds). */
    val currentSojournNs: Long = 0L,

    /** Current queue depth (packets waiting) for this flow. */
    val queueDepth: Int = 0,

    /** Estimated RTT for this flow in milliseconds. */
    val estimatedRttMs: Double = 0.0,

    /** Flow classification (Phase 4). */
    val classification: FlowType = FlowType.UNKNOWN,

    /** Timestamp of last activity (System.nanoTime). */
    val lastActivityNs: Long = 0L
)

/**
 * Flow type classification for adaptive smart mode (Phase 4, §7).
 */
enum class FlowType {
    /** Not yet classified. */
    UNKNOWN,
    /** Sustained high-throughput transfer (backup, large download, attachment). */
    BULK_TRANSFER,
    /** Small packets, high frequency, low jitter (gaming, real-time control). */
    INTERACTIVE,
    /** Mixed sizes, periodic patterns, SRTP-like (video/voice call). */
    VIDEO_CALL,
    /** Bursty, short-lived connections (web page loads). */
    WEB_BROWSING,
    /** DNS queries — always high priority. */
    DNS
}
