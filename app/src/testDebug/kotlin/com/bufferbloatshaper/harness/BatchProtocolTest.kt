package com.bufferbloatshaper.harness

import org.junit.Assert.*
import org.junit.Test

class BatchProtocolTest {
    @Test fun transportParsingIsExplicit() {
        assertEquals(BatchTransport.WIFI, BatchTransport.parse("wifi"))
        assertEquals(BatchTransport.CELLULAR, BatchTransport.parse("cellular"))
        assertNull(BatchTransport.parse("default"))
        assertNull(BatchTransport.parse(null))
    }

    @Test fun resultTokensAreBoundedAndPathSafe() {
        assertTrue(BatchProtocol.validToken("wifi-screen-baseline.01"))
        assertEquals("wifi-screen-baseline.01.json", BatchProtocol.resultFileName("wifi-screen-baseline.01"))
        assertFalse(BatchProtocol.validToken("../private"))
        assertFalse(BatchProtocol.validToken("a".repeat(81)))
        assertFalse(BatchProtocol.validToken(""))
    }
}
