package com.fire17.linuxdesk.genie

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.fire17.linuxdesk.LauncherActivity
import com.fire17.linuxdesk.R
import com.fire17.linuxdesk.SessionBus

/**
 * Tami 15:05: after installing a new APK (or a phone reboot) the genie link did not come back until he opened the
 * app by hand. This specialUse foreground service hosts ONLY the genie link (GenieRuntime.startLinkOnly: iroh +
 * Router, and the local link if that switch is ON) when no session runs. No proot, no X, no Chromium, and never
 * the microphone or the camera: its only FGS type is specialUse, which Android 14+ allows from BOOT_COMPLETED and
 * MY_PACKAGE_REPLACED (microphone/camera types are not, and are never asked for here).
 *
 * Started by [GenieBootReceiver] only when a genie is paired AND Genie access (or Developer: local link) is ON.
 * Stops itself when both switches go OFF ("Stop genie", Forget) and when a session starts (GenieRuntime.start then
 * owns the link under SessionService's FGS).
 */
class GenieLinkService : Service() {

    companion object {
        private const val CHANNEL = "linuxdesk_genie_link_fgs"
        private const val NOTIFICATION_ID = 1918   // SessionService 1917, GenieNotes 1931+

        /** The gate: a paired genie that may connect. Nothing starts otherwise. */
        fun wanted(ctx: Context): Boolean =
            Binding.isBound(ctx) && (GenieSettings.genieAccess(ctx) || GenieSettings.localLink(ctx))

        /** Starts the link-only service if [wanted]. Never throws: a refused FGS start is logged and dropped. */
        fun startIfWanted(ctx: Context, why: String): Boolean {
            if (!wanted(ctx)) return false
            return try {
                val i = Intent(ctx, GenieLinkService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
                SessionBus.log("genie: link-only service requested ($why)")
                true
            } catch (t: Throwable) {
                // e.g. ForegroundServiceStartNotAllowedException outside the boot/update exemption
                SessionBus.log("genie: link-only service not started ($why): ${t.javaClass.simpleName}")
                false
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, GenieLinkService::class.java))
        }
    }

    private val listener: () -> Unit = {
        // "Stop genie" / Forget / switches OFF: nothing left to host.
        if (!wanted(this)) stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.ld_genie_link_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService() must be answered with startForeground() even when we then stop at once.
        try {
            goForeground()
        } catch (t: Throwable) {
            // e.g. a START_STICKY restart that Android does not allow to go foreground: no link rather than a crash.
            SessionBus.log("genie: link-only service could not go foreground (${t.javaClass.simpleName})")
            stopSelf(); return START_NOT_STICKY
        }
        if (!wanted(this)) { stopSelf(); return START_NOT_STICKY }
        Binding.removeListener(listener)
        Binding.addListener(listener)
        Thread({
            try {
                if (!GenieRuntime.startLinkOnly(this)) {
                    // A session already hosts the link; it keeps the process alive, this service is not needed.
                    if (!GenieRuntime.linkOnly) stopSelf()
                }
            } catch (t: Throwable) {
                SessionBus.log("genie: link-only start failed (${t.javaClass.simpleName})")
                stopSelf()
            }
        }, "genie-link-only").apply { isDaemon = true; start() }
        // Killed under memory pressure: Android restarts the link (specialUse only, no intent needed).
        return START_STICKY
    }

    override fun onDestroy() {
        Binding.removeListener(listener)
        try { GenieRuntime.stopLinkOnly() } catch (_: Throwable) {}
        super.onDestroy()
    }

    private fun goForeground() {
        val open = PendingIntent.getActivity(this, 0, Intent(this, LauncherActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_desk)
            .setContentTitle(getString(R.string.ld_genie_link_on))
            .setContentText(getString(R.string.ld_genie_link_on_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.ld_genie_stop),
                GenieRuntime.stopPendingIntent(this)).build())
            .build()
        // specialUse ONLY (the type allowed from boot / package replaced on Android 14+); never microphone/camera.
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }
}

/**
 * Tami 15:05: brings the genie link back after an APK update (MY_PACKAGE_REPLACED) or a reboot (BOOT_COMPLETED)
 * without opening the app. Starts ONLY [GenieLinkService], and only when a genie is paired with access ON; never a
 * Linux session, Chrome, the mic or the camera. Both actions are on Android's list of broadcasts that may start a
 * foreground service from the background.
 */
class GenieBootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                GenieLinkService.startIfWanted(ctx.applicationContext, intent.action!!.substringAfterLast('.'))
        }
    }
}
