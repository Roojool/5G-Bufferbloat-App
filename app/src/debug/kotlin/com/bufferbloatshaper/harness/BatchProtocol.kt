package com.bufferbloatshaper.harness

enum class BatchTransport(val wireName: String) {
    WIFI("wifi"), CELLULAR("cellular");

    companion object {
        fun parse(value: String?): BatchTransport? = entries.singleOrNull { it.wireName == value }
    }
}

object BatchProtocol {
    const val RUN_ACTION = "com.bufferbloatshaper.harness.RUN_BATCH_EXPERIMENT"
    const val CANCEL_ACTION = "com.bufferbloatshaper.harness.CANCEL_BATCH_EXPERIMENT"
    const val MAX_ENCODED_CONFIG = 8_192
    private val tokenPattern = Regex("[A-Za-z0-9._-]{1,80}")

    fun validToken(value: String?): Boolean = value != null && tokenPattern.matches(value)
    fun resultFileName(token: String): String {
        require(validToken(token))
        return "$token.json"
    }
}
