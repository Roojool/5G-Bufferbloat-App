package com.bufferbloatshaper.nativeengine

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device/emulator smoke test for the checked-in ABI boundary. It deliberately
 * verifies the safe fallback, rather than treating a stub library as a relay.
 */
@RunWith(AndroidJUnit4::class)
class NativeEngineInstrumentationTest {

    @Test
    fun packagedStubReportsUnavailableBeforeAnyTunIsCreated() {
        val capability = NativeEngineBridge.capability()

        assertFalse("The checked-in engine must fail closed until gVisor is integrated", capability.available)
        assertNotNull("An unavailable engine must explain itself", capability.detail)
    }
}
