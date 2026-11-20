package com.fire17.linuxdesk

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference

/**
 * Session errors as pop-ups (Tami 13:42, AC5CC9EB: "if there are errors in the background they also pop up as
 * pop-ups"): a start script's `LINUXDESK_ERROR <reason>` line, or the script exiting nonzero before the desktop came up.
 * A LinuxDesk screen in the foreground gets an AlertDialog (OK · Show log · Share logs); otherwise a high-priority
 * notification whose tap opens the launcher and its log. The same reason again within [REPEAT_MS] is not shown twice.
 * Main process only (LinuxDeskApp installs it).
 */
object SessionErrors {
    private const val CHANNEL = "linuxdesk_errors"
    private const val NOTIFICATION_ID = 1918
    private const val REPEAT_MS = 60_000L

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    /** The resumed LinuxDesk activity (dialog host), null while none is on screen. */
    private var resumed: WeakReference<Activity>? = null
    private var dialog: AlertDialog? = null
    private var lastReason = ""
    private var lastAt = 0L

    fun install(a: Application) {
        app = a
        a.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(act: Activity) { resumed = WeakReference(act) }
            override fun onActivityPaused(act: Activity) { if (resumed?.get() === act) resumed = null }
            override fun onActivityDestroyed(act: Activity) {
                if (dialog?.ownerActivity === act) dialog = null
            }
            override fun onActivityCreated(act: Activity, b: Bundle?) {}
            override fun onActivityStarted(act: Activity) {}
            override fun onActivityStopped(act: Activity) {}
            override fun onActivitySaveInstanceState(act: Activity, b: Bundle) {}
        })
    }

    /** Any thread. Logs the reason, then shows it on the main thread (dialog or notification). */
    fun report(reason: String) {
        SessionBus.log("error: $reason")
        main.post { show(reason) }
    }

    private fun show(reason: String) {
        val now = System.currentTimeMillis()
        if (reason == lastReason && now - lastAt < REPEAT_MS) return
        lastReason = reason; lastAt = now
        val act = resumed?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
        if (act != null && showDialog(act, reason)) return
        notifyError(reason)
    }

    private fun showDialog(act: Activity, reason: String): Boolean = try {
        dialog?.takeIf { it.isShowing }?.dismiss()
        dialog = AlertDialog.Builder(act)
            .setTitle(R.string.ld_err_title)
            .setMessage(reason)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.ld_err_show_log) { _, _ -> openLog(act) }
            .setNegativeButton(R.string.ld_share_logs) { _, _ -> LogShare.share(act) }
            .create().also { it.setOwnerActivity(act); it.show() }
        true
    } catch (_: Throwable) {
        false   // e.g. the window token went away between the check and show(): the notification takes over
    }

    /** The launcher shows the session log (and its Share logs / Copy buttons). */
    private fun openLog(ctx: Context) {
        ctx.startActivity(Intent(ctx, LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun notifyError(reason: String) {
        val a = app ?: return
        try {
            val nm = a.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, a.getString(R.string.ld_err_channel), NotificationManager.IMPORTANCE_HIGH))
            val open = PendingIntent.getActivity(a, 5, Intent(a, LauncherActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(a, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_desk)
                .setContentTitle(a.getString(R.string.ld_err_title))
                .setContentText(reason)
                .setStyle(Notification.BigTextStyle().bigText(reason))
                .setCategory(Notification.CATEGORY_ERROR)
                .setAutoCancel(true)
                .setContentIntent(open)
                .addAction(Notification.Action.Builder(null, a.getString(R.string.ld_err_show_log), open).build())
                .build()
            nm.notify(NOTIFICATION_ID, n)
        } catch (t: Throwable) {
            // POST_NOTIFICATIONS not granted (Android 13+): the reason is in the session log either way.
            SessionBus.log("error: could not show the error notification (${t.javaClass.simpleName})")
        }
    }
}
