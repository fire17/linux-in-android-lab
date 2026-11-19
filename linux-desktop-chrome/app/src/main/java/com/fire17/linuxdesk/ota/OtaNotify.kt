package com.fire17.linuxdesk.ota

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.fire17.linuxdesk.R
import com.fire17.linuxdesk.SessionBus

/**
 * OTA v1: when an auto-apply lands, show one line ("Update N ready, applies at next start") as its own notification
 * (tap = the Updates screen) and log the same line to SessionBus, which is what `sys.logs` reads.
 * Separate from the session's ongoing notification on purpose. Never throws.
 */
object OtaNotify {
    private const val CHANNEL = "linuxdesk_updates"
    private const val ID = 0x07A1

    fun applied(ctx: Context, version: Long) {
        val line = OtaAutoCheck.readyLine(version)
        try { SessionBus.log("ota: $line") } catch (_: Throwable) {}
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.ld_ota_title), NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(ctx, 7, Intent(ctx, UpdatesActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(ID, Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_desk)
                .setContentTitle(ctx.getString(R.string.ld_ota_title))
                .setContentText(ctx.getString(R.string.ld_ota_ready_fmt, version))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build())
        } catch (_: Throwable) {   // no POST_NOTIFICATIONS grant etc.: the sys.logs line + ledger row remain
        }
    }
}
