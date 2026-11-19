package com.fire17.linuxdesk.genie

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException

class FrameTest {

    private fun be32(n: Int) = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())

    @Test fun jsonReplyIsStorageGenieCompatible() {
        // SG / heygenie: u32 BE length ‖ 0x00 ‖ UTF-8 JSON, length counts the type byte.
        val f = Frame.encodeJson(JSONObject().put("ok", true))
        val body = "{\"ok\":true}".toByteArray()
        assertArrayEquals(be32(body.size + 1) + byteArrayOf(0) + body, f)
    }

    @Test fun binaryReplyLayout() {
        val h = JSONObject().put("id", 7).put("mime", "image/jpeg")
        val bytes = byteArrayOf(1, 2, 3, -1)
        val f = Frame.encodeBinary(h, bytes)
        val hb = h.toString().toByteArray()
        assertEquals((1 + 2 + hb.size + bytes.size).toLong(), Frame.u32(f))
        assertEquals(1, f[4].toInt())
        assertEquals(hb.size, ((f[5].toInt() and 0xff) shl 8) or (f[6].toInt() and 0xff))
        val d = Frame.decodeReply(f.copyOfRange(4, f.size))!!
        assertTrue(d.isBinary)
        assertEquals(7, d.header.getInt("id"))
        assertEquals("image/jpeg", d.header.getString("mime"))
        assertArrayEquals(bytes, d.body)
    }

    @Test fun roundTripJsonReply() {
        val f = Frame.encodeJson(JSONObject().put("id", 3).put("text", "שלום"))
        val payload = Frame.readPayload(ByteArrayInputStream(f), Frame.MAX_OUT)
        val d = Frame.decodeReply(payload)!!
        assertFalse(d.isBinary)
        assertEquals("שלום", d.header.getString("text"))
    }

    @Test fun requestFrameHasNoTypeByte() {
        val f = Frame.encodeRequest(JSONObject().put("cmd", "sys.ping"))
        val payload = Frame.readPayload(ByteArrayInputStream(f))
        assertEquals("sys.ping", Frame.parseRequest(payload)!!.getString("cmd"))
    }

    @Test fun oversizeRequestRejectedBeforeAllocating() {
        // Header claims 1 MiB + 1 with no body behind it: must throw FrameTooBig, not EOF / OOM.
        val input = ByteArrayInputStream(be32(Frame.MAX_IN + 1))
        try {
            Frame.readPayload(input, Frame.MAX_IN)
            fail("expected FrameTooBig")
        } catch (e: FrameTooBig) {
            assertEquals((Frame.MAX_IN + 1).toLong(), e.size)
        }
    }

    @Test fun hugeLengthIsUnsigned() {
        // 0xFFFFFFFF must read as 4 GiB (too big), never as -1.
        try {
            Frame.readPayload(ByteArrayInputStream(byteArrayOf(-1, -1, -1, -1)))
            fail("expected FrameTooBig")
        } catch (_: FrameTooBig) {
        }
    }

    @Test fun exactlyMaxInIsAccepted() {
        val n = Frame.MAX_IN
        val payload = Frame.readPayload(ByteArrayInputStream(be32(n) + ByteArray(n)), Frame.MAX_IN)
        assertEquals(n, payload.size)
    }

    @Test(expected = EOFException::class)
    fun truncatedBodyIsEof() {
        Frame.readPayload(ByteArrayInputStream(be32(10) + ByteArray(4)))
    }

    @Test fun emptyPayloadAllowed() {
        assertEquals(0, Frame.readPayload(ByteArrayInputStream(be32(0))).size)
        assertNull(Frame.parseRequest(ByteArray(0)))
    }

    @Test fun badJsonIsNull() {
        assertNull(Frame.parseRequest("not json".toByteArray()))
        assertNull(Frame.parseRequest("[1,2]".toByteArray()))
        assertNull(Frame.parseRequest("{broken".toByteArray()))
        assertNotNull(Frame.parseRequest("  {\"cmd\":\"x\"}".toByteArray()))
    }

    @Test fun oversizeBinaryReplyBecomesTooBigError() {
        val f = Frame.encodeBinary(JSONObject().put("id", 9).put("mime", "image/png"), ByteArray(Frame.MAX_OUT))
        val d = Frame.decodeReply(f.copyOfRange(4, f.size))!!
        assertFalse(d.isBinary)
        assertEquals("too_big", d.header.getString("error"))
        assertEquals(9, d.header.getInt("id"))
    }

    @Test fun oversizeJsonReplyBecomesTooBigError() {
        val big = "x".repeat(Frame.MAX_OUT)
        val f = Frame.encodeJson(JSONObject().put("id", 4).put("stdout", big))
        assertTrue(f.size < 1000)
        val d = Frame.decodeReply(f.copyOfRange(4, f.size))!!
        assertEquals("too_big", d.header.getString("error"))
        assertEquals(4, d.header.getInt("id"))
    }

    @Test fun binaryJustUnderCapIsKept() {
        val h = JSONObject().put("sid", "s1").put("mime", "image/jpeg")
        val room = Frame.MAX_OUT - 3 - h.toString().toByteArray().size
        val f = Frame.encodeBinary(h, ByteArray(room))
        assertEquals(1, f[4].toInt())
        assertEquals(Frame.MAX_OUT.toLong(), Frame.u32(f))
    }

    @Test fun malformedRepliesDecodeToNull() {
        assertNull(Frame.decodeReply(ByteArray(0)))
        assertNull(Frame.decodeReply(byteArrayOf(2, 0)))                // unknown type
        assertNull(Frame.decodeReply(byteArrayOf(1, 0)))                // binary too short
        assertNull(Frame.decodeReply(byteArrayOf(1, 0, 50, 1, 2)))      // hlen past the end
        assertNull(Frame.decodeReply(byteArrayOf(0) + "nope".toByteArray()))
    }

    @Test fun requestIdParsing() {
        assertEquals(Frame.NO_ID, Frame.requestId(JSONObject()))
        assertEquals(Frame.NO_ID, Frame.requestId(JSONObject().put("id", JSONObject.NULL)))
        assertEquals(7L, Frame.requestId(JSONObject("{\"id\":7}")))
        assertEquals(0L, Frame.requestId(JSONObject("{\"id\":0}")))
        assertEquals(-3L, Frame.requestId(JSONObject("{\"id\":-3}")))
        assertEquals(1L shl 40, Frame.requestId(JSONObject("{\"id\":${1L shl 40}}")))
        assertEquals(5L, Frame.requestId(JSONObject("{\"id\":5.0}")))
        assertEquals(Frame.BAD_ID, Frame.requestId(JSONObject("{\"id\":5.5}")))
        assertEquals(Frame.BAD_ID, Frame.requestId(JSONObject("{\"id\":\"abc\"}")))
    }

    @Test fun withIdEchoesAndDoesNotMutate() {
        val orig = JSONObject().put("ok", true).put("id", 99)
        val r = Frame.withId(Reply.Json(orig), 7) as Reply.Json
        assertEquals(7L, r.obj.getLong("id"))
        assertEquals(99, orig.getInt("id"))
        val none = Frame.withId(Reply.Json(orig), Frame.NO_ID) as Reply.Json
        assertFalse(none.obj.has("id"))
        val bin = Frame.withId(Reply.Binary(JSONObject().put("mime", "x"), byteArrayOf(1)), 3) as Reply.Binary
        assertEquals(3L, bin.header.getLong("id"))
    }

    @Test fun replyErrorHelper() {
        val e = Reply.error("bad_args", "d".repeat(500))
        assertEquals("bad_args", e.obj.getString("error"))
        assertEquals(200, e.obj.getString("detail").length)
        assertFalse(Reply.error("auth").obj.has("detail"))
    }
}
