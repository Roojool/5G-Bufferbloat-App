package com.bufferbloatshaper.vpn

/**
 * Narrow, thread-safe gate used by the JNI socket-protection callback.
 *
 * Android can revoke VPN permission while a serialized start is still
 * establishing its TUN. Revocation is terminal for this service instance: a
 * later start step can never reopen the gate after [revoke] has won.
 */
internal class VpnSocketProtectionGate {
    private val lock = Any()

    private var socketProtectionAllowed = false

    private var revoked = false

    fun tryEnable(): Boolean = synchronized(lock) {
        if (revoked) {
            false
        } else {
            socketProtectionAllowed = true
            true
        }
    }

    fun disable() = synchronized(lock) {
        socketProtectionAllowed = false
    }

    fun revoke() = synchronized(lock) {
        revoked = true
        socketProtectionAllowed = false
    }

    /**
     * Makes the permission check and the platform protect call one ordered
     * operation. Revocation/teardown therefore happens wholly before or after
     * this call, never in the gap between a true check and protect(fd).
     */
    fun protectIfAllowed(protect: () -> Boolean): Boolean = synchronized(lock) {
        if (revoked || !socketProtectionAllowed) {
            false
        } else {
            // Re-check after the platform call in case a re-entrant revocation
            // was delivered on this same thread while protect(fd) ran.
            protect() && !revoked && socketProtectionAllowed
        }
    }

    fun isAllowed(): Boolean = synchronized(lock) { socketProtectionAllowed }

    fun isRevoked(): Boolean = synchronized(lock) { revoked }
}
