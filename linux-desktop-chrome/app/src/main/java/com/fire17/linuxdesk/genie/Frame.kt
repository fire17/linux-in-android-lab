package com.fire17.linuxdesk.genie

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

/**
 * What a verb handler hands back to the Router (spec 01 §1.1, §3 "wire ↔ storage").
 *  - [Json]   → a type-0 frame: `u32 BE len ‖ 0x00 ‖ UTF-8 JSON` (byte-compatible with Storage Genie).
 *  - [Binary] → a type-1 frame: `u32 BE len ‖ 0x01 ‖ u16 BE hlen ‖ header JSON ‖ raw bytes`.
 * The Router adds/echoes `id`; handlers never need to.
 */
sealed class Reply {
    class Json(val obj: JSONObject) : Reply()
    class Binary(val header: JSONObject, val bytes: ByteArray) : Reply()

    companion object {
        fun error(code: String, detail: String? = null): Json {
            val o = JSONObject().put("error", code)
            if (!detail.isNullOrEmpty()) o.put("detail", detail.take(200))
            return Json(o)
        }
        fun ok(): Json = Json(JSONObject().put("ok", true))
    }
}

/** A request frame larger than [Frame.MAX_IN]: the transport answers `{error:"too_big"}` and closes. */
class FrameTooBig(val size: Long) : Exception("frame too big")

/** One decoded reply frame (as read from deskd): the JSON (or type-1 header) plus raw bytes for type 1. */
class DecodedReply(val header: JSONObject, val body: ByteArray?) {
    val isBinary: Boolean get() = body != null
}

/**
 * The `linuxdesk/1` frame codec. Pure JVM (no android.*), so FrameTest runs on the host.
 * Limits (spec 01 §4 rule 9): requests ≤ 1 MiB in, replies ≤ 8 MiB out.
 */
object Frame {
    const val MAX_IN: Int = 1 shl 20          // 1 MiB, request payload
    const val MAX_OUT: Int = 8 shl 20         // 8 MiB, whole reply payload (type byte included)
    const val MAX_HEADER: Int = 0xFFFF        // u16 hlen
    const val TYPE_JSON: Int = 0
    const val TYPE_BINARY: Int = 1

    // ---------------------------------------------------------------- reading

    /** Reads exactly [n] bytes or throws EOFException. */
    fun readExactly(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) throw EOFException("stream closed after $off/$n bytes")
            off += r
        }
        return buf
    }

    fun u32(b: ByteArray, off: Int = 0): Long =
        ((b[off].toLong() and 0xff) shl 24) or ((b[off + 1].toLong() and 0xff) shl 16) or
            ((b[off + 2].toLong() and 0xff) shl 8) or (b[off + 3].toLong() and 0xff)

    /**
     * Reads one length-prefixed payload. Throws [FrameTooBig] (before reading the body) when the length
     * exceeds [max], so a hostile peer cannot make us allocate.
     */
    fun readPayload(input: InputStream, max: Int = MAX_IN): ByteArray {
        val n = u32(readExactly(input, 4))
        if (n > max) throw FrameTooBig(n)
        return if (n == 0L) ByteArray(0) else readExactly(input, n.toInt())
    }

    /** Parses a request payload as a JSON object, or null when it is not one (→ `bad_json`). */
    fun parseRequest(payload: ByteArray): JSONObject? = try {
        val s = String(payload, Charsets.UTF_8).trim()
        if (!s.startsWith("{")) null else JSONObject(s)
    } catch (_: Exception) {
        null
    }

    /** Decodes a reply payload (type byte first). Returns null when malformed. */
    fun decodeReply(payload: ByteArray): DecodedReply? {
        if (payload.isEmpty()) return null
        return try {
            when (payload[0].toInt() and 0xff) {
                TYPE_JSON -> DecodedReply(JSONObject(String(payload, 1, payload.size - 1, Charsets.UTF_8)), null)
                TYPE_BINARY -> {
                    if (payload.size < 3) return null
                    val hlen = ((payload[1].toInt() and 0xff) shl 8) or (payload[2].toInt() and 0xff)
                    if (3 + hlen > payload.size) return null
                    val header = JSONObject(String(payload, 3, hlen, Charsets.UTF_8))
                    DecodedReply(header, payload.copyOfRange(3 + hlen, payload.size))
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------- writing

    private fun lenPrefix(n: Int): ByteArray =
        byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())

    /** A request frame (genie → phone, and app → deskd): `u32 len ‖ JSON`, no type byte. */
    fun encodeRequest(o: JSONObject): ByteArray {
        val body = o.toString().toByteArray(Charsets.UTF_8)
        require(body.size <= MAX_IN) { "request too big" }
        return lenPrefix(body.size) + body
    }

    /** A type-0 reply frame. Oversize replies are replaced by `{error:"too_big"}` (keeping `id`). */
    fun encodeJson(o: JSONObject): ByteArray {
        var body = o.toString().toByteArray(Charsets.UTF_8)
        if (body.size + 1 > MAX_OUT) body = tooBigFor(o).toString().toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream(5 + body.size)
        out.write(lenPrefix(body.size + 1))
        out.write(TYPE_JSON)
        out.write(body)
        return out.toByteArray()
    }

    /** A type-1 reply frame. Oversize (or a header over 64 KiB) becomes a type-0 `too_big` error. */
    fun encodeBinary(header: JSONObject, bytes: ByteArray): ByteArray {
        val h = header.toString().toByteArray(Charsets.UTF_8)
        val total = 1L + 2 + h.size + bytes.size
        if (h.size > MAX_HEADER || total > MAX_OUT) return encodeJson(tooBigFor(header))
        val out = ByteArrayOutputStream(4 + total.toInt())
        out.write(lenPrefix(total.toInt()))
        out.write(TYPE_BINARY)
        out.write((h.size ushr 8) and 0xff)
        out.write(h.size and 0xff)
        out.write(h)
        out.write(bytes)
        return out.toByteArray()
    }

    fun encode(r: Reply): ByteArray = when (r) {
        is Reply.Json -> encodeJson(r.obj)
        is Reply.Binary -> encodeBinary(r.header, r.bytes)
    }

    private fun tooBigFor(o: JSONObject): JSONObject {
        val e = JSONObject().put("error", "too_big").put("detail", "reply exceeds 8 MiB")
        if (o.has("id")) e.put("id", o.opt("id"))
        if (o.has("sid")) e.put("sid", o.opt("sid"))
        return e
    }

    // ---------------------------------------------------------------- ids

    /**
     * The request's `id`, when present and an integer. Returns [NO_ID] when absent, [BAD_ID] when present
     * but not an integer (→ `bad_args`).
     */
    fun requestId(o: JSONObject): Long {
        if (!o.has("id") || o.isNull("id")) return NO_ID
        val v = o.opt("id")
        return when (v) {
            is Int -> v.toLong()
            is Long -> v
            is Number -> if (v.toDouble() == Math.floor(v.toDouble()) && !v.toDouble().isInfinite()) v.toLong() else BAD_ID
            else -> BAD_ID
        }
    }

    const val NO_ID: Long = Long.MIN_VALUE
    const val BAD_ID: Long = Long.MIN_VALUE + 1

    /** A copy of [r] with `id` set to [id] (or removed when [id] == [NO_ID]). The input is not mutated. */
    fun withId(r: Reply, id: Long): Reply = when (r) {
        is Reply.Json -> Reply.Json(copyWithId(r.obj, id))
        is Reply.Binary -> Reply.Binary(copyWithId(r.header, id), r.bytes)
    }

    fun copyWithId(o: JSONObject, id: Long): JSONObject {
        val c = JSONObject(o.toString())
        if (id == NO_ID) c.remove("id") else c.put("id", id)
        return c
    }
}
