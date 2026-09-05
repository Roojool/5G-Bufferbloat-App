package com.bufferbloatshaper.nativeengine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeEngineBridgeContractTest {
    @Test
    fun activationRequiresTheEntireKotlinOwnedV2SafetyMask() {
        assertTrue(
            NativeEngineBridge.isSafeActivationContract(
                abiVersion = 2,
                engineLinked = true,
                featureBits = ALL_REQUIRED_FEATURES,
                nativeRequiredFeatureBits = ALL_REQUIRED_FEATURES,
                shutdownQuarantined = false
            )
        )
    }

    @Test
    fun activationRejectsANativeAttemptToLowerItsOwnRequirements() {
        assertFalse(
            NativeEngineBridge.isSafeActivationContract(
                abiVersion = 2,
                engineLinked = true,
                featureBits = 0x01L,
                nativeRequiredFeatureBits = 0x01L,
                shutdownQuarantined = false
            )
        )
    }

    @Test
    fun activationRejectsMissingSafetyFeaturesWrongAbiAndQuarantine() {
        assertFalse(
            NativeEngineBridge.isSafeActivationContract(
                abiVersion = 2,
                engineLinked = true,
                featureBits = ALL_REQUIRED_FEATURES and 0x1fL,
                nativeRequiredFeatureBits = ALL_REQUIRED_FEATURES,
                shutdownQuarantined = false
            )
        )
        assertFalse(
            NativeEngineBridge.isSafeActivationContract(
                abiVersion = 3,
                engineLinked = true,
                featureBits = ALL_REQUIRED_FEATURES,
                nativeRequiredFeatureBits = ALL_REQUIRED_FEATURES,
                shutdownQuarantined = false
            )
        )
        assertFalse(
            NativeEngineBridge.isSafeActivationContract(
                abiVersion = 2,
                engineLinked = true,
                featureBits = ALL_REQUIRED_FEATURES,
                nativeRequiredFeatureBits = ALL_REQUIRED_FEATURES,
                shutdownQuarantined = true
            )
        )
    }

    private companion object {
        const val ALL_REQUIRED_FEATURES = 0x3fL
    }
}
