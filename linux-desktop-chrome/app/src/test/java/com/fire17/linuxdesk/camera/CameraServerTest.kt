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

    @Test fun v2ParsesCamParamAndPreflight() {
        val r = CameraServer.parseRequest("GET /stream?t=tok&cam=3 HTTP/1.1")!!
        assertEquals("GET", r.method); assertEquals("/stream", r.path); assertEquals("tok", r.token); assertEquals("3", r.params["cam"])
        assertEquals("a b", CameraServer.parseRequest("GET /stream?t=x&cam=a%20b HTTP/1.1")!!.params["cam"])
        assertEquals("OPTIONS", CameraServer.parseRequest("OPTIONS /stream HTTP/1.1")!!.method)
    }

    @Test fun labelsAreUniqueAndCarryFacingAndFocal() {
        val back = 1   // CameraCharacteristics.LENS_FACING_BACK
        val front = 0  // LENS_FACING_FRONT
        assertEquals("Phone back 6.3 mm (camera 0)", CameraServer.labelFor(back, 6.3f, "0", null))
        assertEquals("Phone back 2.2 mm (lens 3 of 0)", CameraServer.labelFor(back, 2.2f, "3", "0"))
        assertEquals("Phone front (camera 1)", CameraServer.labelFor(front, null, "1", null))
        assertNotEquals(CameraServer.labelFor(back, 6.3f, "0", null), CameraServer.labelFor(back, 6.3f, "2", null))
    }

    @Test fun picksStreamSize() {
        assertEquals(1280 to 720, CameraServer.pickSize(listOf(4000 to 3000, 1280 to 720, 640 to 480)))
        assertEquals(1280 to 960, CameraServer.pickSize(listOf(4000 to 3000, 1280 to 960, 640 to 480)))
        assertEquals(4000 to 3000, CameraServer.pickSize(listOf(4000 to 3000)))
        assertNull(CameraServer.pickSize(emptyList()))
    }

    @Test fun tokensAreLongAndFresh() {
        val a = CameraServer.newToken(); val b = CameraServer.newToken()
        assertEquals(32, a.length)
        assertTrue(a.all { it in "0123456789abcdef" })
        assertNotEquals(a, b)
    }

    @Test fun configJsIsOffAtPortZeroAndCannotBeInjected() {
        val off = PhoneCamera.configJs(0, "")
        assertTrue("port: 0" in off); assertTrue("enabled: false" in off)
        val js = PhoneCamera.configJs(4321, "ab\"; alert(1); //cd")
        assertTrue("port: 4321" in js); assertTrue("enabled: true" in js)
        assertFalse("\"; alert" in js)
        assertTrue("token: \"abalert1cd\"" in js)
        assertTrue("ask: false" in js)   // per-page prompt OFF by default
    }

    @Test fun stateJsonOffHidesPortTokenAndCams() {
        val off = PhoneCamera.stateJson(false, 4321, "secret", "[{\"id\":\"0\"}]")
        assertEquals("{\"enabled\":false,\"port\":0,\"token\":\"\",\"ask\":false,\"relayOnly\":false,\"cams\":[]}\n", off)
        val on = PhoneCamera.stateJson(true, 4321, "ab\"cd", "[{\"id\":\"0\"}]")
        assertEquals("{\"enabled\":true,\"port\":4321,\"token\":\"abcd\",\"ask\":false,\"relayOnly\":false,\"cams\":[{\"id\":\"0\"}]}\n", on)
        assertTrue("\"enabled\":false" in PhoneCamera.stateJson(true, 0, "x", "[]"))   // no port = off
    }

    @Test fun relayOnlyKeepsPortAndTokenOutOfPageScripts() {
        val js = PhoneCamera.configJs(4321, "secret", relay = true)
        assertFalse("4321" in js); assertFalse("secret" in js)
        assertTrue("relayOnly: true" in js); assertTrue("enabled: true" in js)
        assertTrue("relayOnly: false" in PhoneCamera.configJs(4321, "secret"))   // default OFF
        assertTrue("\"relayOnly\":true" in PhoneCamera.stateJson(true, 4321, "s", "[]", relay = true))
        assertTrue("\"relayOnly\":false" in PhoneCamera.stateJson(false, 4321, "s", "[]", relay = true))   // off = off
    }

    @Test fun camJsonIsValidShape() {
        val c = CameraServer.Cam("3", 1, 2.2f, "0", 1280 to 720)
        assertEquals("{\"id\":\"3\",\"label\":\"Phone back 2.2 mm (lens 3 of 0)\",\"facing\":\"back\",\"focalMm\":2.2," +
            "\"physicalOf\":\"0\",\"width\":1280,\"height\":720}", c.json())
        assertTrue("\"focalMm\":null" in CameraServer.Cam("1", 0, null, null, 640 to 480).json())
    }
}
