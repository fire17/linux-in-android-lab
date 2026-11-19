package com.fire17.linuxdesk.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicForegroundTest {
    @Test fun firstStartIsForegroundEdge() {
        val c = MicForeground.Counter()
        assertFalse(c.visible)
        assertTrue(c.onStarted())
        assertTrue(c.visible)
    }

    @Test fun launcherToDesktopIsNoEdge() {
        val c = MicForeground.Counter()
        c.onStarted()                         // launcher
        assertFalse(c.onStarted())            // desktop starts before the launcher stops
        assertFalse(c.onStopped(false))       // launcher stops
        assertTrue(c.visible)
        assertEquals(1, c.started)
    }

    @Test fun lastStopIsBackgroundEdge() {
        val c = MicForeground.Counter()
        c.onStarted()
        assertTrue(c.onStopped(false))
        assertFalse(c.visible)
        assertTrue(c.onStarted())             // coming back resumes
    }

    @Test fun rotationIsNoEdge() {
        val c = MicForeground.Counter()
        c.onStarted()
        assertFalse(c.onStopped(true))        // recreated for a configuration change
        assertTrue(c.visible)
        assertFalse(c.onStarted())            // the new instance: no foreground edge either
        assertTrue(c.onStopped(false))        // a real leave afterwards still is one
    }

    @Test fun extraStopNeverGoesNegative() {
        val c = MicForeground.Counter()
        assertTrue(c.onStopped(false))
        assertEquals(0, c.started)
        assertTrue(c.onStarted())
    }

    @Test fun mayCaptureRules() {
        assertFalse(MicForeground.mayCapture(consent = false, visible = true, allowBackground = true))
        assertTrue(MicForeground.mayCapture(consent = true, visible = true, allowBackground = false))
        assertFalse(MicForeground.mayCapture(consent = true, visible = false, allowBackground = false))
        assertTrue(MicForeground.mayCapture(consent = true, visible = false, allowBackground = true))
    }
}
