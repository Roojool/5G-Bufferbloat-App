package com.bufferbloatshaper

import android.app.Application
import com.bufferbloatshaper.model.VpnRuntimeState
import com.bufferbloatshaper.model.VpnRuntimeStateStore
import com.bufferbloatshaper.model.VpnRuntimeStatus
import com.bufferbloatshaper.util.Notifications
import com.bufferbloatshaper.util.Preferences

/**
 * Application class — creates notification channel on startup.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications(this).createChannel()

        val preferences = Preferences(this)
        if (preferences.consumeUnrecoverableNativeShutdown()) {
            VpnRuntimeStateStore.publish(
                VpnRuntimeState(
                    status = VpnRuntimeStatus.ERROR,
                    detail = "The local VPN was stopped after its native engine failed to shut down safely.",
                    recoverableError = "The app restarted in safe mode. Review diagnostics before trying again.",
                    config = preferences.loadConfig(),
                    nativeEngineAvailable = false,
                    ipv6Supported = false
                )
            )
        }
    }
}
