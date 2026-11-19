package com.fire17.linuxdesk.camera

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CameraLogTest {
    private val saved = CameraLog.sink
    @After fun restore() { CameraLog.sink = saved }

    @Test fun errorLineShape() {
        assertEquals("camera-error: 3 open IOException: busy", CameraLog.errorLine("3", "open", IOException("busy")))
        assertEquals("camera-error: 0 configure onConfigureFailed", CameraLog.errorLine("0", "configure", null, "onConfigureFailed"))
        assertEquals("camera-error: - list SecurityException", CameraLog.errorLine("", "list", SecurityException()))
        val multi = CameraLog.errorLine("1", "repeat x", IllegalStateException("a\nb"))
        assertFalse(multi.contains('\n')); assertTrue(multi.startsWith("camera-error: 1 repeat_x IllegalStateException: a b"))
    }

    @Test fun camerasLineIsOneCappedLine() {
        assertEquals("cameras: {\"a\":1 }", CameraLog.camerasLine("{\"a\":1\n}"))
        assertTrue(CameraLog.camerasLine("x".repeat(20_000)).length <= CameraLog.MAX_LINE)
    }

    @Test fun linesReachTheSinkAndSinkFailuresNeverThrow() {
        val got = ArrayList<String>()
        CameraLog.sink = { got += it }
        CameraLog.error("4", "open", IOException("x")); CameraLog.cameras("{}")
        assertEquals(listOf("camera-error: 4 open IOException: x", "cameras: {}"), got)
        CameraLog.sink = { throw RuntimeException("sink down") }
        CameraLog.error("4", "open", null)   // must not throw
    }
}
