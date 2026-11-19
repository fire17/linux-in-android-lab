package com.fire17.phoneinphone.stream

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream

/**
 * Golden vectors copied from scrcpy v5.0.1 app/tests/test_control_msg_serialize.c (Apache-2.0),
 * so a protocol drift fails CI instead of silently breaking input on a phone.
 */
class ScrcpyProtocolTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun injectKeycode_matchesUpstream() {
        val expected = bytes(
            0x00, // INJECT_KEYCODE
            0x01, // AKEY_EVENT_ACTION_UP
            0x00, 0x00, 0x00, 0x42, // AKEYCODE_ENTER
            0x00, 0x00, 0x00, 0x05, // repeat
            0x00, 0x00, 0x00, 0x41, // AMETA_SHIFT_ON | AMETA_SHIFT_LEFT_ON
        )
        assertArrayEquals(expected, ScrcpyProtocol.injectKeycode(1, 0x42, 5, 0x41))
    }

    @Test fun injectText_matchesUpstream() {
        val expected = bytes(0x01, 0x00, 0x00, 0x00, 0x0d) + "hello, world!".toByteArray()
        assertArrayEquals(expected, ScrcpyProtocol.injectText("hello, world!"))
    }

    @Test fun injectTouch_matchesUpstream() {
        val expected = bytes(
            0x02, // INJECT_TOUCH_EVENT
            0x00, // ACTION_DOWN
            0x12, 0x34, 0x56, 0x78, 0x87, 0x65, 0x43, 0x21, // pointer id
            0x00, 0x00, 0x00, 0x64, 0x00, 0x00, 0x00, 0xc8, // 100 200
            0x04, 0x38, 0x07, 0x80, // 1080 1920
            0xff, 0xff, // pressure 1.0
            0x00, 0x00, 0x00, 0x01, // action button PRIMARY
            0x00, 0x00, 0x00, 0x01, // buttons PRIMARY
        )
        val actual = ScrcpyProtocol.injectTouch(0, 0x1234567887654321L, 100, 200, 1080, 1920, 1f, 1, 1)
        assertEquals(32, actual.size)
        assertArrayEquals(expected, actual)
    }

    @Test fun backOrScreenOn_matchesUpstream() {
        assertArrayEquals(bytes(0x04, 0x01), ScrcpyProtocol.backOrScreenOn(1))
    }

    @Test fun setClipboard_matchesUpstream() {
        val expected = bytes(
            0x09,
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
            0x01,
            0x00, 0x00, 0x00, 0x0d,
        ) + "hello, world!".toByteArray()
        assertArrayEquals(expected, ScrcpyProtocol.setClipboard(0x0102030405060708L, "hello, world!", true))
    }

    @Test fun resetVideo_isType17() {
        assertArrayEquals(bytes(17), ScrcpyProtocol.simple(ScrcpyProtocol.TYPE_RESET_VIDEO))
    }

    @Test fun socketName_formatsScidAsHex() {
        assertEquals("scrcpy_0000abcd", ScrcpyProtocol.socketName(0xabcd))
        assertEquals("scrcpy", ScrcpyProtocol.socketName(-1))
    }

    @Test fun readsSessionThenMediaPackets() {
        val session = bytes(0x80, 0, 0, 0x01, 0, 0, 0x04, 0x38, 0, 0, 0x09, 0x60) // 1080x2400, client-resize
        val media = bytes(0x20, 0, 0, 0, 0, 0, 0x03, 0xe8, 0, 0, 0, 3, 9, 8, 7) // key frame, pts=1000, 3 bytes
        val config = bytes(0x40, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 5)
        val din = DataInputStream(ByteArrayInputStream(session + config + media))
        val s = ScrcpyProtocol.readVideoPacket(din) as ScrcpyProtocol.VideoPacket.Session
        assertEquals(1080, s.width); assertEquals(2400, s.height); assertTrue(s.clientResize)
        val c = ScrcpyProtocol.readVideoPacket(din) as ScrcpyProtocol.VideoPacket.Media
        assertTrue(c.config); assertEquals(-1L, c.ptsUs)
        val m = ScrcpyProtocol.readVideoPacket(din) as ScrcpyProtocol.VideoPacket.Media
        assertTrue(m.keyFrame); assertEquals(1000L, m.ptsUs); assertArrayEquals(bytes(9, 8, 7), m.data)
    }

    @Test fun utf8Truncate_doesNotSplitHebrew() {
        val s = "שלום".repeat(100) // 2 bytes per char
        val b = ScrcpyProtocol.utf8Truncate(s, 301)
        assertEquals(300, b.size)
        assertTrue(!ScrcpyProtocol.isAscii("שלום"))
    }

    @Test fun serverCommand_hasVersionFirstAndForwardTunnel() {
        val cmd = ScrcpyProtocol.serverCommand("5.0.1", 0x1f, StreamOptions())
        assertTrue(cmd.contains("app_process / com.genymobile.scrcpy.Server 5.0.1 scid=0000001f"))
        assertTrue(cmd.contains("tunnel_forward=true"))
        assertTrue(cmd.contains("audio=false"))
    }
}
