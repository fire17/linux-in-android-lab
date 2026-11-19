package com.fire17.linuxdesk.genie

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import android.text.format.Formatter
import com.fire17.linuxdesk.R
import java.util.Date

/**
 * The genie's notifications (spec 01 §1.2, §2 phase B, §1.3 delete rule):
 *  - paired: "Paired with <genie>" (posted by Binding on a successful pairing);
 *  - connected: "Genie connected · last command 14:05" with a Stop genie action (ongoing, silent);
 *  - suggest: "Your genie suggests deleting N files" (fs.propose_delete);
 *  - confirm: the heads-up "Confirm: delete N files (X)" (fs.execute → awaiting_confirm).
 *
 * Nothing here contains a code, a token, typed text or file names.
 */
object GenieNotes {
    private const val CH_EVENTS = "linuxdesk_genie"
    private const val CH_LINK = "linuxdesk_genie_link"
    private const val CH_CONFIRM = "linuxdesk_genie_confirm"

    // SessionService uses 1917 for its FGS notification.
    const val ID_PAIRED = 1931
    const val ID_CONNECTED = 1932
    const val ID_APP_CLOSED = 1933
    private const val ID_SUGGEST_BASE = 1940
    private const val ID_CONFIRM_BASE = 1960

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    private fun ensureChannels(ctx: Context) {
        val nm = nm(ctx) ?: return
        nm.createNotificationChannel(NotificationChannel(CH_EVENTS,
            ctx.getString(R.string.ld_genie_channel_events), NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_LINK,
            ctx.getString(R.string.ld_genie_channel_link), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_CONFIRM,
            ctx.getString(R.string.ld_genie_channel_confirm), NotificationManager.IMPORTANCE_HIGH))
    }

    private fun canPost(ctx: Context) = nm(ctx)?.areNotificationsEnabled() == true

    private fun openPair(ctx: Context, req: Int): PendingIntent =
        PendingIntent.getActivity(ctx, req,
            Intent(ctx, PairActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun builder(ctx: Context, channel: String) =
        Notification.Builder(ctx, channel).setSmallIcon(R.drawable.ic_stat_desk)

    private fun post(ctx: Context, id: Int, n: Notification) {
        ensureChannels(ctx)
        if (!canPost(ctx)) return
        try { nm(ctx)?.notify(id, n) } catch (_: SecurityException) {} // POST_NOTIFICATIONS revoked
    }

    /** "Paired with <genie>". Tapping opens the Pair screen (where Forget lives). */
    fun paired(ctx: Context, genie: String) {
        post(ctx, ID_PAIRED, builder(ctx, CH_EVENTS)
            .setContentTitle(ctx.getString(R.string.ld_genie_note_paired, genie))
            .setContentText(ctx.getString(R.string.ld_genie_note_paired_text))
            .setContentIntent(openPair(ctx, 31))
            .setAutoCancel(true)
            .build())
    }

    /**
     * The one-line status for the FGS notification: "Genie connected · last command 14:05", or
     * "Genie connected" before the first command. SessionService can put this into its own
     * notification instead of posting [connected].
     */
    fun connectedText(ctx: Context, lastCommandAt: Long): String =
        if (lastCommandAt <= 0L) ctx.getString(R.string.ld_genie_note_connected)
        else ctx.getString(R.string.ld_genie_note_connected_last,
            DateFormat.getTimeFormat(ctx).format(Date(lastCommandAt)))

    /**
     * An ongoing, silent "Genie connected" notification with a Stop genie action. [stop] is supplied by
     * the owner of the link (wire lane / SessionService), because only it can close the endpoint.
     * Call again to refresh the last-command time; call [clearConnected] on disconnect.
     */
    fun connected(ctx: Context, genie: String, lastCommandAt: Long, stop: PendingIntent?) {
        val b = builder(ctx, CH_LINK)
            .setContentTitle(connectedText(ctx, lastCommandAt))
            .setContentText(genie)
            .setContentIntent(openPair(ctx, 32))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (stop != null) b.addAction(Notification.Action.Builder(null, ctx.getString(R.string.ld_genie_stop), stop).build())
        post(ctx, ID_CONNECTED, b.build())
    }

    fun clearConnected(ctx: Context) { nm(ctx)?.cancel(ID_CONNECTED) }

    @Volatile private var lastAppClosedAt = 0L

    /**
     * "Your genie wants to look": the Router posts this when it answers {"error":"app_closed"}
     * (genie_window = sg and LinuxDesk is not on screen). At most one per 2 minutes, as in Storage Genie.
     * Tapping opens LinuxDesk's launcher, which reopens the window. The verb name is never shown.
     */
    fun appClosed(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - lastAppClosedAt in 0 until GenieWindow.GRACE_MS) return
        lastAppClosedAt = now
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            ?: Intent(ctx, PairActivity::class.java)
        post(ctx, ID_APP_CLOSED, builder(ctx, CH_EVENTS)
            .setContentTitle(ctx.getString(R.string.ld_genie_note_app_closed))
            .setContentText(ctx.getString(R.string.ld_genie_note_app_closed_text))
            .setContentIntent(PendingIntent.getActivity(ctx, 33, launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .build())
    }

    /** "Your genie suggests deleting N files (X)". [open] = the storage lane's review screen intent. */
    fun suggest(ctx: Context, proposalId: String, count: Int, bytes: Long, open: Intent) {
        val req = ID_SUGGEST_BASE + (proposalId.hashCode() and 0xF)
        post(ctx, req, builder(ctx, CH_EVENTS)
            .setContentTitle(ctx.resources.getQuantityString(R.plurals.ld_genie_note_suggest, count, count))
            .setContentText(Formatter.formatShortFileSize(ctx, bytes))
            .setContentIntent(PendingIntent.getActivity(ctx, req, open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .build())
    }

    /**
     * The heads-up "Confirm: delete N files (X)". Deletion still needs the Approve tap on the review
     * sheet that [open] leads to; this notification has no action that deletes anything.
     */
    fun confirm(ctx: Context, proposalId: String, count: Int, bytes: Long, open: Intent) {
        val req = ID_CONFIRM_BASE + (proposalId.hashCode() and 0xF)
        post(ctx, req, builder(ctx, CH_CONFIRM)
            .setContentTitle(ctx.resources.getQuantityString(R.plurals.ld_genie_note_confirm, count, count,
                Formatter.formatShortFileSize(ctx, bytes)))
            .setContentText(ctx.getString(R.string.ld_genie_note_confirm_text))
            .setContentIntent(PendingIntent.getActivity(ctx, req, open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .build())
    }

    fun clearProposal(ctx: Context, proposalId: String) {
        val k = proposalId.hashCode() and 0xF
        nm(ctx)?.cancel(ID_SUGGEST_BASE + k)
        nm(ctx)?.cancel(ID_CONFIRM_BASE + k)
    }
}
