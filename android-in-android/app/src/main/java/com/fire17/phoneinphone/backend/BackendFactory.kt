package com.fire17.phoneinphone.backend

import android.content.Context
import android.content.SharedPreferences
import com.fire17.phoneinphone.ondevice.OnDeviceBackend
import com.fire17.phoneinphone.stream.LinkMode
import com.fire17.phoneinphone.stream.LinkTarget
import com.fire17.phoneinphone.stream.StreamBackend
import com.fire17.phoneinphone.stream.StreamOptions

/** Settings persisted by MainActivity; the single place that turns them into a backend. */
data class Settings(
    val backend: String = "stream",
    val linkMode: LinkMode = LinkMode.DIRECT,
    val host: String = "192.168.1.10",
    val port: Int = 5555,
    val serial: String = "",
    val maxSize: Int = 1280,
    val bitRateMbps: Int = 6,
    val maxFps: Int = 60,
    val codec: String = "h264",
    val newDisplay: String = "",
    val runAvf: Boolean = true,
) {
    fun save(p: SharedPreferences) = p.edit()
        .putString("backend", backend)
        .putString("linkMode", linkMode.name)
        .putString("host", host)
        .putInt("port", port)
        .putString("serial", serial)
        .putInt("maxSize", maxSize)
        .putInt("bitRateMbps", bitRateMbps)
        .putInt("maxFps", maxFps)
        .putString("codec", codec)
        .putString("newDisplay", newDisplay)
        .putBoolean("runAvf", runAvf)
        .apply()

    companion object {
        fun load(p: SharedPreferences): Settings {
            val d = Settings()
            return Settings(
                backend = p.getString("backend", d.backend)!!,
                linkMode = runCatching { LinkMode.valueOf(p.getString("linkMode", d.linkMode.name)!!) }.getOrDefault(d.linkMode),
                host = p.getString("host", d.host)!!,
                port = p.getInt("port", d.port),
                serial = p.getString("serial", d.serial)!!,
                maxSize = p.getInt("maxSize", d.maxSize),
                bitRateMbps = p.getInt("bitRateMbps", d.bitRateMbps),
                maxFps = p.getInt("maxFps", d.maxFps),
                codec = p.getString("codec", d.codec)!!,
                newDisplay = p.getString("newDisplay", d.newDisplay)!!,
                runAvf = p.getBoolean("runAvf", d.runAvf),
            )
        }

        fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("pip", Context.MODE_PRIVATE)
    }
}

object BackendFactory {
    fun create(context: Context, s: Settings): PhoneBackend = when (s.backend) {
        "ondevice" -> OnDeviceBackend(context.applicationContext, s.runAvf)
        else -> StreamBackend(
            context.applicationContext,
            LinkTarget(s.linkMode, s.host.trim(), s.port, s.serial.trim()),
            StreamOptions(
                codec = s.codec,
                maxSize = s.maxSize,
                bitRate = s.bitRateMbps * 1_000_000,
                maxFps = s.maxFps,
                newDisplay = s.newDisplay.trim(),
            ),
        )
    }
}
