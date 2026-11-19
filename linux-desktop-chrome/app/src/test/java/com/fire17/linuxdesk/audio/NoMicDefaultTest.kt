package com.fire17.linuxdesk.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 21:40 feedback loop: with the phone mic off, the default source must be the silent no-mic, never ld.monitor. */
class NoMicDefaultTest {
    private val ldMonitorDefault = Regex("""set-default-source\s+'?ld\.monitor""")

    @Test fun pulseLoadsSilentPlaceholder() {
        val loads = PulseHost.fixedLoads()
        assertTrue(loads.any { it.startsWith("module-null-sink sink_name=nomic ") })
        assertTrue(loads.any { it.startsWith("module-remap-source master=nomic.monitor source_name=no-mic ") })
        assertTrue(loads.any { it.startsWith("module-null-sink sink_name=ld ") })   // speaker sink unchanged
    }

    @Test fun unloadRestoresNoMic() {
        val s = MicGuest.unloadScript()
        assertTrue(s.contains("set-default-source no-mic"))
        assertFalse(ldMonitorDefault.containsMatchIn(s))
    }

    @Test fun loadNeverDefaultsToSpeakerMonitor() {
        val s = MicGuest.loadScript(34567)
        assertTrue(s.contains("set-default-source phone-mic"))
        assertFalse(ldMonitorDefault.containsMatchIn(s))
    }

    @Test fun unloadNeverMatchesAudioOut() {
        // AudioOut's module is `module-simple-protocol-tcp ... record=true source=ld.monitor`; the awk filter must
        // only ever match our sink=mic / source_name=phone-mic / sink_name=mic args.
        val s = MicGuest.unloadScript()
        assertFalse(s.contains("ld.monitor"))
        assertTrue(s.contains("sink=mic "))
    }
}
