package com.fire17.linuxdesk

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * "Share logs": one tap zips every log the app keeps and opens the share sheet with that zip
 * (Tami, voice AC29D3B9: the log is too long to copy by hand).
 *
 * The zip holds: the session log (SessionBus), the genie audit log (verb, ok/error and timing only, no content or
 * auth), the deskd self-test log, a short device summary, hw.json (HwProbe: read-only hardware detection, no
 * identifiers), and the last 2000 lines of this app's own logcat. No
 * pairing token, key or file content goes in it: the audit and self-test logs never contain those, and logcat
 * output from this app never logs auth values or typed text (spec 01 §4 rule 7).
 */
object LogShare {
    private const val AUTHORITY_SUFFIX = ".logs"

    fun share(activity: Activity): Boolean {
        val zip = try { build(activity) } catch (_: Throwable) { return false }
        val uri = FileProvider.getUriForFile(activity, activity.packageName + AUTHORITY_SUFFIX, zip)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, zip.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(zip.name, uri)
        activity.startActivity(Intent.createChooser(send, zip.name))
        return true
    }

    private fun build(activity: Activity): File {
        val dir = File(activity.cacheDir, "logs").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }   // keep only the newest zip
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val zip = File(dir, "linuxdesk-logs-$stamp.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { z ->
            fun put(name: String, text: String) {
                z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry()
            }
            fun putFile(name: String, f: File) {
                if (f.isFile) put(name, f.readText().takeLast(2_000_000))
            }
            put("session.log", SessionBus.logText())
            putFile("genie-audit.jsonl", File(activity.filesDir, "genie-audit.jsonl"))
            putFile("genie-audit.jsonl.1", File(activity.filesDir, "genie-audit.jsonl.1"))
            putFile("selftest.log", File(activity.filesDir, "rootfs/run/linuxdesk/selftest.log"))
            put("device.txt", deviceInfo(activity))
            put("hw.json", try { com.fire17.linuxdesk.hw.HwProbe.get(activity).toString(2) } catch (t: Throwable) {
                "{\"error\":\"${t.javaClass.simpleName}\"}"
            })
            put("logcat.txt", ownLogcat())
        }
        return zip
    }

    private fun deviceInfo(activity: Activity): String {
        val pi = activity.packageManager.getPackageInfo(activity.packageName, 0)
        return buildString {
            appendLine("app ${pi.versionName} (${if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else 0})")
            appendLine("device ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("abis ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("time ${Date()}")
        }
    }

    /** This app's own logcat only (an app can read its own uid's lines without any permission). */
    private fun ownLogcat(): String = try {
        val p = ProcessBuilder("logcat", "-d", "-t", "2000", "--pid", android.os.Process.myPid().toString())
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out
    } catch (t: Throwable) {
        "logcat unavailable: ${t.javaClass.simpleName}"
    }
}
