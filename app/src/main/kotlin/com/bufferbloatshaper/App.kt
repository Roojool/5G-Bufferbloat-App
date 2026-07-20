package com.bufferbloatshaper

import android.app.Application
import com.bufferbloatshaper.util.Notifications

/**
 * Application class — creates notification channel on startup.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications(this).createChannel()
    }
}
