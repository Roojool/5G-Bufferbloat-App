package com.bufferbloatshaper.model

/**
 * Coarse, payload-free queue classes used only by the Kotlin fair-queue
 * reference tests. They are not derived from app traffic in the shipped build
 * and must not be presented as a live classification feature.
 */
enum class FlowType {
    UNKNOWN,
    BULK_TRANSFER,
    INTERACTIVE,
    VIDEO_CALL,
    WEB_BROWSING,
    DNS
}
