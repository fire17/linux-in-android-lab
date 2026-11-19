package com.fire17.linuxdesk.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraServerTest {
    @Test fun parsesPathAndToken() {
        assertEquals("/stream" to "abc", CameraServer.parseRequestLine("GET /stream?t=abc HTTP/1.1"))
        assertEquals("/info" to "x", CameraServer.parseRequestLine("GET /info?a=1&t=x HTTP/1.1"))
        assertEquals("/stream" to null, CameraServer.parseRequestLine("GET /stream HTTP/1.1"))
    }

    @Test fun rejectsNonGetAndGarbage() {
        assertNull(CameraServer.parseRequestLine("POST /stream?t=abc HTTP/1.1"))
        assertNull(CameraServer.parseRequestLine(""))
        assertNull(CameraServer.parseRequestLine(null))
    }

    @Test fun tokensAreLongAndFresh() {
        val a = CameraServer.newToken(); val b = CameraServer.newToken()
        assertEquals(32, a.length)
        assertTrue(a.all { it in "0123456789abcdef" })
        assertNotEquals(a, b)
    }

    @Test fun configJsIsOffAtPortZeroAndCannotBeInjected() {
        assertTrue("port: 0" in PhoneCamera.configJs(0, ""))
        val js = PhoneCamera.configJs(4321, "ab\"; alert(1); //cd")
        assertTrue("port: 4321" in js)
        assertFalse("\"; alert" in js)
        assertTrue("token: \"abalert1cd\"" in js)
        assertTrue("ask: false" in js)   // per-page prompt OFF by default
    }
}
