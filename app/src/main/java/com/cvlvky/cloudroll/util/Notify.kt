package com.cvlvky.cloudroll.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.cvlvky.cloudroll.MainActivity

object Notify {
    const val TRANSFERS = "transfers"
    const val RESULTS = "results"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(TRANSFERS, "Backup and downloads in progress", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows progress while CloudRoll backs up or saves files"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(RESULTS, "Finished backups and downloads", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Says when a backup or a download has finished"
            },
        )
    }

    fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx,
        0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
