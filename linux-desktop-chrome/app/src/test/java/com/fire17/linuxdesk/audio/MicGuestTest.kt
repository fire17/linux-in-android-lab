package com.fire17.linuxdesk.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The default-source rules (Tami 21:39, no feedback loop): Linux never records the speaker monitor. */
class MicGuestTest {
    private fun defaults(script: String) =
        script.lines().map { it.trim() }.filter { it.startsWith("pactl set-default-source") }

    @Test fun loadMakesPhoneMicTheDefaultLast() {
        val d = defaults(MicGuest.loadScript(40000))
        assertEquals("pactl set-default-source ${MicGuest.SOURCE}", d.last())
    }

    @Test fun unloadGivesTheDefaultBackToNoMic() {
        val s = MicGuest.unloadScript()
        val d = defaults(s)
        assertEquals(listOf("pactl set-default-source ${PulseHost.NO_MIC_SOURCE} || true"), d)
        assertTrue(s.indexOf("unload-module") < s.indexOf("set-default-source"))
    }

    @Test fun noScriptEverPointsAtTheSpeakerMonitor() {
        for (s in listOf(MicGuest.loadScript(40000), MicGuest.unloadScript())) {
            defaults(s).forEach { assertFalse(it, it.contains("${PulseHost.SINK}.monitor")) }
        }
    }

    @Test fun placeholderIsSilentAndNotTheSpeaker() {
        val loads = PulseHost.fixedLoads()
        assertTrue(loads.any { it.startsWith("module-null-sink") && it.contains("sink_name=${PulseHost.NO_MIC_SINK} ") })
        assertTrue(loads.any {
            it.startsWith("module-remap-source") && it.contains("master=${PulseHost.NO_MIC_SINK}.monitor") &&
                it.contains("source_name=${PulseHost.NO_MIC_SOURCE} ")
        })
        assertFalse(PulseHost.NO_MIC_SINK == PulseHost.SINK)
        assertEquals(MicGuest.FALLBACK_SOURCE, PulseHost.NO_MIC_SOURCE)
    }

    @Test fun micModulesNeverMatchThePlaceholder() {
        // unloadBody's awk matches sink_name=mic / source_name=phone-mic with a trailing space: never nomic/no-mic.
        assertFalse(PulseHost.NO_MIC_SINK == MicGuest.SINK)
        assertFalse(PulseHost.NO_MIC_SOURCE == MicGuest.SOURCE)
        assertFalse("sink_name=${PulseHost.NO_MIC_SINK} ".contains("sink_name=${MicGuest.SINK} "))
    }
}
