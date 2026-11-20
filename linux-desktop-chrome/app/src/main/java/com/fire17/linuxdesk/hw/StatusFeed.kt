package com.fire17.linuxdesk.hw

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Bar indicators for the PRoot Omarchy shell (notes/indicators/REPORT.md, option B). The guest has no system D-Bus,
 * no UPower/NetworkManager/BlueZ and cannot read /sys/class/power_supply or /sys/class/net, so Android tells it:
 * `<rootfs>/tmp/linuxdesk-status/status.json`, written atomically (tmp + rename) at [start] and on every change.
 *
 *   {"t":…,"battery":{"present":true,"percent":0.83,"charging":true,"full":false,"plugged":"usb"},
 *    "net":{"kind":"wifi","signal":72,"validated":true},"bt":{"present":true,"enabled":true,"connected":0}}
 *
 *  - battery: sticky ACTION_BATTERY_CHANGED (no permission). percent is 0..1 like UPower's displayDevice.
 *  - net: ConnectivityManager default-network callback (ACCESS_NETWORK_STATE, already held). kind wifi | cellular |
 *    ethernet | none; signal 0..100, -1 = unknown (Wi-Fi RSSI from NetworkCapabilities API 29+ / WifiInfo; mobile from
 *    TelephonyManager level API 28+). No SSID (would need location).
 *  - bt: BluetoothAdapter present/enabled; connected needs BLUETOOTH_CONNECT, which we do not request (counted only if
 *    already granted, else 0). Every Bluetooth call is guarded: SecurityException just reads as "off".
 * Guest side: the overlay QML in assets/linuxdesk/hyprland/indicators (FileView + 5 s reload), staged by
 * start-hyprland.sh. Nothing here needs a new permission.
 */
object StatusFeed {
    private const val TICK_MS = 5_000L   // Bluetooth and mobile signal have no callback we can use without permissions

    @Volatile private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var appCtx: Context? = null
    private var file: File? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var battery = "{\"present\":false,\"percent\":0,\"charging\":false,\"full\":false,\"plugged\":\"\"}"
    private var net = "{\"kind\":\"none\",\"signal\":-1,\"validated\":false}"
    private var lastBody = ""

    val running: Boolean get() = thread != null

    /** Starts the feed for this session's rootfs (idempotent: a running feed is restarted on the new rootfs). */
    @Synchronized
    fun start(ctx: Context, rootfsDir: File) {
        stop()
        val app = ctx.applicationContext
        appCtx = app
        file = File(rootfsDir, "tmp/linuxdesk-status/status.json")
        lastBody = ""
        val t = HandlerThread("linuxdesk-status").also { it.start() }
        val h = Handler(t.looper)
        thread = t
        handler = h
        h.post {
            if (thread !== t) return@post   // stopped before this ran: register nothing
            // Battery: the sticky intent answers at once; the same receiver then gets every change.
            val br = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) { battery = batteryJson(i); write() }
            }
            batteryReceiver = br
            // A protected system broadcast: the system still delivers it to a not-exported receiver.
            val sticky = try {
                ContextCompat.registerReceiver(app, br, IntentFilter(Intent.ACTION_BATTERY_CHANGED), null, h, ContextCompat.RECEIVER_NOT_EXPORTED)
            } catch (_: Throwable) { null }
            if (sticky != null) battery = batteryJson(sticky)

            // Network: the default network (what the guest's traffic actually uses).
            val cm = app.getSystemService(ConnectivityManager::class.java)
            if (cm != null) {
                net = netJson(cm, cm.activeNetwork?.let { cm.getNetworkCapabilities(it) })
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) { net = netJson(cm, caps); write() }
                    override fun onLost(n: Network) {
                        net = netJson(cm, cm.activeNetwork?.takeIf { it != n }?.let { cm.getNetworkCapabilities(it) }); write()
                    }
                }
                try {
                    cm.registerDefaultNetworkCallback(cb, h)
                    netCallback = cb
                } catch (_: Throwable) {}
            }
            write()
            h.postDelayed(object : Runnable {
                override fun run() {
                    if (thread == null) return
                    val m = app.getSystemService(ConnectivityManager::class.java)
                    if (m != null && net.contains("\"cellular\"")) net = netJson(m, m.activeNetwork?.let { m.getNetworkCapabilities(it) })
                    write()
                    handler?.postDelayed(this, TICK_MS)
                }
            }, TICK_MS)
        }
    }

    @Synchronized
    fun stop() {
        val t = thread ?: return
        val app = appCtx
        thread = null
        try { batteryReceiver?.let { app?.unregisterReceiver(it) } } catch (_: Throwable) {}
        try { netCallback?.let { app?.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) } } catch (_: Throwable) {}
        batteryReceiver = null
        netCallback = null
        handler?.removeCallbacksAndMessages(null)
        handler = null
        t.quitSafely()
    }

    // ------------------------------------------------------------------------------------------ JSON

    private fun batteryJson(i: Intent): String {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).takeIf { it > 0 } ?: 100
        val present = i.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true) && level >= 0
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val plugged = when (i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            0 -> ""
            else -> "dock"
        }
        val pct = if (level >= 0) (level.toDouble() / scale).coerceIn(0.0, 1.0) else 0.0
        val full = status == BatteryManager.BATTERY_STATUS_FULL
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING
        return "{\"present\":$present,\"percent\":${"%.2f".format(java.util.Locale.US, pct)},\"charging\":$charging," +
            "\"full\":$full,\"plugged\":\"$plugged\"}"
    }

    @Suppress("DEPRECATION")   // allNetworks: only to look under a VPN
    private fun netJson(cm: ConnectivityManager, caps0: NetworkCapabilities?): String {
        var caps = caps0
        // A VPN as the default network hides the transport: report the physical network under it.
        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && physicalKind(caps) == null) {
            caps = try {
                cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }
                    .firstOrNull { !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && physicalKind(it) != null } ?: caps
            } catch (_: Throwable) { caps }
        }
        if (caps == null) return "{\"kind\":\"none\",\"signal\":-1,\"validated\":false}"
        val kind = physicalKind(caps) ?: "none"
        val signal = when (kind) {
            "wifi" -> wifiSignal(caps)
            "cellular" -> cellSignal()
            "ethernet" -> 100
            else -> -1
        }
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return "{\"kind\":\"$kind\",\"signal\":$signal,\"validated\":$validated}"
    }

    private fun physicalKind(c: NetworkCapabilities): String? = when {
        c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        else -> null
    }

    /** RSSI dBm → 0..100 (NetworkManager's scale: -100 dBm = 0, -50 dBm = 100). -1 = unknown. */
    private fun wifiSignal(caps: NetworkCapabilities): Int {
        if (Build.VERSION.SDK_INT < 29) return -1
        var rssi = caps.signalStrength
        if (rssi == NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED) {
            rssi = (caps.transportInfo as? WifiInfo)?.rssi ?: return -1
        }
        if (rssi >= 0 || rssi < -127) return -1   // WifiInfo.INVALID_RSSI is -127
        return ((rssi + 100) * 2).coerceIn(0, 100)
    }

    /** Mobile signal level 0..4 → 0..100 (TelephonyManager.getSignalStrength, API 28+, no permission). */
    private fun cellSignal(): Int {
        if (Build.VERSION.SDK_INT < 28) return -1
        return try {
            val tm = appCtx?.getSystemService(TelephonyManager::class.java) ?: return -1
            val level = tm.signalStrength?.level ?: return -1
            (level.coerceIn(0, 4) * 25)
        } catch (_: Throwable) { -1 }
    }

    @SuppressLint("MissingPermission")   // every call is guarded; BLUETOOTH_CONNECT is checked before the profile query
    private fun btJson(): String {
        val ctx = appCtx ?: return "{\"present\":false,\"enabled\":false,\"connected\":0}"
        return try {
            val adapter = if (ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH))
                ctx.getSystemService(BluetoothManager::class.java)?.adapter else null
            if (adapter == null) return "{\"present\":false,\"enabled\":false,\"connected\":0}"
            val enabled = try { adapter.isEnabled } catch (_: SecurityException) { false }
            var connected = 0
            val canConnect = Build.VERSION.SDK_INT < 31 ||
                ctx.checkSelfPermission("android.permission.BLUETOOTH_CONNECT") == PackageManager.PERMISSION_GRANTED
            if (enabled && canConnect) {
                try {
                    // BluetoothProfile 1 = HEADSET, 2 = A2DP, 4 = HID_HOST; STATE_CONNECTED = 2.
                    connected = listOf(1, 2, 4).count { adapter.getProfileConnectionState(it) == 2 }
                } catch (_: Throwable) {}
            }
            "{\"present\":true,\"enabled\":$enabled,\"connected\":$connected}"
        } catch (_: Throwable) {
            "{\"present\":false,\"enabled\":false,\"connected\":0}"
        }
    }

    /** Runs on the feed thread only. Rewrites the file when anything but "t" changed (atomic: tmp + rename). */
    private fun write() {
        val f = file ?: return
        val body = "\"battery\":$battery,\"net\":$net,\"bt\":${btJson()}"
        if (body == lastBody && f.isFile) return
        try {
            val dir = f.parentFile ?: return
            if (!dir.isDirectory && !dir.mkdirs()) return
            dir.setReadable(true, false); dir.setExecutable(true, false)
            val tmp = File(dir, "status.json.tmp")
            tmp.writeText("{\"t\":${System.currentTimeMillis() / 1000},$body}\n")
            tmp.setReadable(true, false)
            if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
            lastBody = body
        } catch (_: Throwable) {}
    }
}
