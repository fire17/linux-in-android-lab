package com.fire17.phoneinphone.stream

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

/**
 * Clean-room Kotlin implementation of the scrcpy client<->server wire protocol, written against
 * scrcpy v5.0.1 (Apache-2.0, github.com/Genymobile/scrcpy). Sources used (fetched 2026-11-19):
 *  - doc/develop.md                                  (socket order, dummy byte, packet headers)
 *  - server/.../device/Streamer.java                 (PACKET_FLAG_* constants, session meta)
 *  - server/.../device/DesktopConnection.java        (socket name "scrcpy_%08x", 64-byte device name)
 *  - server/.../control/ControlMessageReader.java    (control message layouts)
 *  - app/src/control_msg.h                           (message type enum order)
 *  - app/tests/test_control_msg_serialize.c          (expected byte layouts)
 *
 * The protocol is INTERNAL to scrcpy and has no compatibility guarantee: this file and the
 * scrcpy-server jar fetched by scripts/fetch-scrcpy-server.sh must be the same version.
 *
 * All multi-byte integers are big-endian (Java DataOutputStream default).
 */
object ScrcpyProtocol {

    const val SERVER_CLASS = "com.genymobile.scrcpy.Server"
    const val DEVICE_SERVER_PATH = "/data/local/tmp/pip-scrcpy-server.jar"
    const val DEVICE_NAME_FIELD_LENGTH = 64

    // Streamer.java
    const val PACKET_FLAG_SESSION = 1L shl 63
    const val PACKET_FLAG_CONFIG = 1L shl 62
    const val PACKET_FLAG_KEY_FRAME = 1L shl 61
    const val PTS_MASK = PACKET_FLAG_KEY_FRAME - 1

    // doc/develop.md codec ids
    const val CODEC_H264 = 0x68323634
    const val CODEC_H265 = 0x68323635
    const val CODEC_AV1 = 0x00617631

    // control_msg.h enum order
    const val TYPE_INJECT_KEYCODE = 0
    const val TYPE_INJECT_TEXT = 1
    const val TYPE_INJECT_TOUCH_EVENT = 2
    const val TYPE_INJECT_SCROLL_EVENT = 3
    const val TYPE_BACK_OR_SCREEN_ON = 4
    const val TYPE_EXPAND_NOTIFICATION_PANEL = 5
    const val TYPE_EXPAND_SETTINGS_PANEL = 6
    const val TYPE_COLLAPSE_PANELS = 7
    const val TYPE_GET_CLIPBOARD = 8
    const val TYPE_SET_CLIPBOARD = 9
    const val TYPE_SET_DISPLAY_POWER = 10
    const val TYPE_ROTATE_DEVICE = 11
    const val TYPE_RESET_VIDEO = 17

    const val INJECT_TEXT_MAX_LENGTH = 300

    // control_msg.h pointer ids
    const val POINTER_ID_MOUSE = -1L
    const val POINTER_ID_GENERIC_FINGER = -2L

    // android.view.MotionEvent actions (kept local so this file is plain-JVM unit-testable)
    const val MOTION_ACTION_DOWN = 0
    const val MOTION_ACTION_UP = 1
    const val MOTION_ACTION_MOVE = 2

    /** Socket name the server listens on in tunnel_forward mode (DesktopConnection.getSocketName). */
    fun socketName(scid: Int): String =
        if (scid == -1) "scrcpy" else "scrcpy_" + String.format("%08x", scid)

    /** 31-bit random scid (server parses it as hex). */
    fun randomScid(): Int = (java.util.Random().nextInt() and 0x7FFFFFFF)

    /**
     * Shell command that starts the server on the guest. We use tunnel_forward=true because the
     * in-app ADB client can open "localabstract:<name>" streams directly (no adb reverse needed).
     */
    fun serverCommand(version: String, scid: Int, opts: StreamOptions): String {
        val args = mutableListOf(
            "scid=" + String.format("%08x", scid),
            "log_level=info",
            "tunnel_forward=true",
            "video=true",
            "audio=false",
            "control=true",
            "cleanup=true",
            "video_codec=${opts.codec}",
            "max_size=${opts.maxSize}",
            "video_bit_rate=${opts.bitRate}",
            "max_fps=${opts.maxFps}",
            "stay_awake=true",
            "clipboard_autosync=false",
            "power_on=true",
        )
        if (opts.newDisplay.isNotBlank()) args += "new_display=${opts.newDisplay}"
        return "CLASSPATH=$DEVICE_SERVER_PATH app_process / $SERVER_CLASS $version " +
            args.joinToString(" ")
    }

    // ---------------------------------------------------------------- device -> client (video)

    /** First socket (video in our config): optional dummy byte, then 64-byte NUL-padded name. */
    fun readDeviceName(input: DataInputStream): String {
        val buf = ByteArray(DEVICE_NAME_FIELD_LENGTH)
        input.readFully(buf)
        val len = buf.indexOf(0.toByte()).let { if (it < 0) buf.size else it }
        return String(buf, 0, len, Charsets.UTF_8)
    }

    fun codecName(id: Int): String = when (id) {
        CODEC_H264 -> "h264"
        CODEC_H265 -> "h265"
        CODEC_AV1 -> "av1"
        0 -> "disabled"
        1 -> "error"
        else -> "unknown(0x" + Integer.toHexString(id) + ")"
    }

    sealed class VideoPacket {
        data class Session(val width: Int, val height: Int, val clientResize: Boolean) : VideoPacket()
        data class Media(
            val config: Boolean,
            val keyFrame: Boolean,
            val ptsUs: Long,
            val data: ByteArray,
        ) : VideoPacket()
    }

    /**
     * Reads either a 12-byte session packet (MSB of byte0 = 1) or a 12-byte media header + payload.
     * Throws EOFException when the stream ends.
     */
    fun readVideoPacket(input: DataInputStream): VideoPacket {
        val first = input.readLong()
        if (first and PACKET_FLAG_SESSION != 0L) {
            val flags = (first ushr 32).toInt()
            val width = first.toInt() // low 32 bits of the first 8 bytes = width
            val height = input.readInt()
            return VideoPacket.Session(width, height, (flags and 1) != 0)
        }
        val size = input.readInt()
        if (size < 0 || size > 64 * 1024 * 1024) throw EOFException("bad packet size $size")
        val data = ByteArray(size)
        input.readFully(data)
        val config = first and PACKET_FLAG_CONFIG != 0L
        val key = first and PACKET_FLAG_KEY_FRAME != 0L
        val pts = if (config) -1L else first and PTS_MASK
        return VideoPacket.Media(config, key, pts, data)
    }

    // ---------------------------------------------------------------- client -> device (control)

    fun injectKeycode(action: Int, keyCode: Int, repeat: Int, metaState: Int): ByteArray =
        build { out ->
            out.writeByte(TYPE_INJECT_KEYCODE)
            out.writeByte(action)
            out.writeInt(keyCode)
            out.writeInt(repeat)
            out.writeInt(metaState)
        }

    /** INJECT_TEXT: u32 length + UTF-8 bytes, truncated to 300 bytes on a char boundary. */
    fun injectText(text: String): ByteArray {
        val bytes = utf8Truncate(text, INJECT_TEXT_MAX_LENGTH)
        return build { out ->
            out.writeByte(TYPE_INJECT_TEXT)
            out.writeInt(bytes.size)
            out.write(bytes)
        }
    }

    /**
     * INJECT_TOUCH_EVENT (32 bytes): type, action u8, pointerId i64, x i32, y i32,
     * screenW u16, screenH u16, pressure u16 fixed-point, actionButton i32, buttons i32.
     * screenW/H must equal the CURRENT video size or the server ignores the event.
     */
    fun injectTouch(
        action: Int,
        pointerId: Long,
        x: Int,
        y: Int,
        screenWidth: Int,
        screenHeight: Int,
        pressure: Float,
        actionButton: Int = 0,
        buttons: Int = 0,
    ): ByteArray = build { out ->
        out.writeByte(TYPE_INJECT_TOUCH_EVENT)
        out.writeByte(action)
        out.writeLong(pointerId)
        out.writeInt(x)
        out.writeInt(y)
        out.writeShort(screenWidth)
        out.writeShort(screenHeight)
        out.writeShort(floatToU16FixedPoint(pressure))
        out.writeInt(actionButton)
        out.writeInt(buttons)
    }

    fun backOrScreenOn(action: Int): ByteArray = build { out ->
        out.writeByte(TYPE_BACK_OR_SCREEN_ON)
        out.writeByte(action)
    }

    /** SET_CLIPBOARD: sequence i64, paste u8, u32 length, UTF-8. Used as the Unicode text path. */
    fun setClipboard(sequence: Long, text: String, paste: Boolean): ByteArray {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return build { out ->
            out.writeByte(TYPE_SET_CLIPBOARD)
            out.writeLong(sequence)
            out.writeByte(if (paste) 1 else 0)
            out.writeInt(bytes.size)
            out.write(bytes)
        }
    }

    fun simple(type: Int): ByteArray = byteArrayOf(type.toByte())

    // ---------------------------------------------------------------- helpers

    /** Binary.floatToU16FixedPoint: 1.0 -> 0xffff. */
    fun floatToU16FixedPoint(f: Float): Int {
        val clamped = f.coerceIn(0f, 1f)
        return if (clamped >= 1f) 0xFFFF else (clamped * 65536f).toInt() and 0xFFFF
    }

    fun isAscii(s: String): Boolean = s.all { it.code in 0x20..0x7E || it == '\n' || it == '\t' }

    fun utf8Truncate(s: String, maxBytes: Int): ByteArray {
        val all = s.toByteArray(Charsets.UTF_8)
        if (all.size <= maxBytes) return all
        var end = maxBytes
        // back off continuation bytes (10xxxxxx)
        while (end > 0 && (all[end].toInt() and 0xC0) == 0x80) end--
        return all.copyOf(end)
    }

    private inline fun build(block: (DataOutputStream) -> Unit): ByteArray {
        val bos = ByteArrayOutputStream(32)
        DataOutputStream(bos).use { block(it) }
        return bos.toByteArray()
    }
}

data class StreamOptions(
    val codec: String = "h264",
    val maxSize: Int = 1280,
    val bitRate: Int = 6_000_000,
    val maxFps: Int = 60,
    /** e.g. "1080x2340/440" to ask the guest for a dedicated virtual display; blank = mirror main. */
    val newDisplay: String = "",
)
