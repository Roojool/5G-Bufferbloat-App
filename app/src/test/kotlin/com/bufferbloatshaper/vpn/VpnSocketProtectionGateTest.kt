package com.bufferbloatshaper.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class VpnSocketProtectionGateTest {
    @Test
    fun revocationPermanentlyWinsOverAnInProgressStart() {
        val gate = VpnSocketProtectionGate()

        assertTrue(gate.tryEnable())
        assertTrue(gate.isAllowed())

        gate.revoke()

        assertTrue(gate.isRevoked())
        assertFalse(gate.isAllowed())
        assertFalse(gate.tryEnable())
        assertFalse(gate.isAllowed())
    }

    @Test
    fun ordinaryDisableDoesNotPermanentlyBlockAFutureEnable() {
        val gate = VpnSocketProtectionGate()

        assertTrue(gate.tryEnable())
        gate.disable()

        assertFalse(gate.isAllowed())
        assertFalse(gate.isRevoked())
        assertTrue(gate.tryEnable())
    }

    @Test
    fun revokeIsOrderedOutsideThePlatformProtectOperation() {
        val gate = VpnSocketProtectionGate()
        val protectEntered = CountDownLatch(1)
        val releaseProtect = CountDownLatch(1)
        val protectFinished = CountDownLatch(1)
        val revokerReady = CountDownLatch(1)
        val revokeFinished = CountDownLatch(1)
        val protected = AtomicBoolean(false)

        assertTrue(gate.tryEnable())

        val worker = thread(start = true) {
            protected.set(
                gate.protectIfAllowed {
                    protectEntered.countDown()
                    releaseProtect.await(2, TimeUnit.SECONDS)
                }
            )
            protectFinished.countDown()
        }
        assertTrue("The protection operation should begin", protectEntered.await(2, TimeUnit.SECONDS))

        val revoker = thread(start = true) {
            revokerReady.countDown()
            gate.revoke()
            revokeFinished.countDown()
        }
        assertTrue("The revoker should be scheduled", revokerReady.await(2, TimeUnit.SECONDS))
        assertFalse(
            "Revocation must not complete halfway through protect(fd)",
            revokeFinished.await(250, TimeUnit.MILLISECONDS)
        )

        releaseProtect.countDown()
        assertTrue("The protection operation should finish", protectFinished.await(2, TimeUnit.SECONDS))
        assertTrue("Revocation should finish after protection", revokeFinished.await(2, TimeUnit.SECONDS))
        worker.join(2_000)
        revoker.join(2_000)

        assertTrue(protected.get())
        assertFalse(gate.isAllowed())
        assertTrue(gate.isRevoked())
    }

    @Test
    fun reentrantRevocationMakesTheProtectionCallbackFailClosed() {
        val gate = VpnSocketProtectionGate()
        assertTrue(gate.tryEnable())

        val accepted = gate.protectIfAllowed {
            gate.revoke()
            true
        }

        assertFalse(accepted)
        assertTrue(gate.isRevoked())
    }
}
