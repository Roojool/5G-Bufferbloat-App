package com.bufferbloatshaper.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.bufferbloatshaper.MainActivity
import com.bufferbloatshaper.R

/**
 * Notification management for the foreground VPN service (Phase 5).
 *
 * Shows persistent notification with live stats while shaping is active,
 * plus quick-action buttons for pause/resume.
 */
class Notifications(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "bufferbloat_shaper_vpn"
        const val NOTIFICATION_ID = 1001

        const val ACTION_PAUSE = "com.bufferbloatshaper.ACTION_PAUSE"
        const val ACTION_RESUME = "com.bufferbloatshaper.ACTION_RESUME"
    }

    /**
     * Create the notification channel (required for Android O+).
     */
    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.vpn_channel_name),
            NotificationManager.IMPORTANCE_LOW // Low = no sound, shows in shade
        ).apply {
            description = context.getString(R.string.vpn_channel_desc)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
        manager.createNotificationChannel(channel)
    }

    /**
     * Build the foreground service notification.
     *
     * @param uploadRate Current upload rate as human-readable string (e.g., "12.5 Mbps").
     * @param downloadRate Current download rate as human-readable string.
     * @param isPaused Whether shaping is currently paused.
     * @return Notification to use with startForeground().
     */
    fun buildNotification(
        uploadRate: String = "—",
        downloadRate: String = "—",
        isPaused: Boolean = false
    ): Notification {
        // Tap notification → open main activity
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Pause/Resume action button
        val actionIntent = PendingIntent.getBroadcast(
            context,
            1,
            Intent(if (isPaused) ACTION_RESUME else ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val actionLabel = if (isPaused) {
            context.getString(R.string.action_resume)
        } else {
            context.getString(R.string.action_pause)
        }

        val contentText = if (isPaused) {
            "Shaping paused"
        } else {
            context.getString(R.string.notification_shaping_active, uploadRate, downloadRate)
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(contentText)
            .setContentIntent(openIntent)
            .addAction(0, actionLabel, actionIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * Update the existing notification with new stats.
     */
    fun updateNotification(uploadRate: String, downloadRate: String, isPaused: Boolean = false) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(uploadRate, downloadRate, isPaused))
    }
}
