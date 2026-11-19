package com.fire17.linuxdesk.audio

/**
 * The guest half of "Microphone to Linux": pactl commands that add a phone-fed source to the ONE guest
 * PulseAudio (started by [PulseHost], ld-audio-out lane; never a second daemon). Loaded LIVE (pactl against
 * [PulseHost.PULSE_SERVER]) when the switch turns ON, unloaded when it turns OFF, so the TCP port exists only while
 * the person has the mic on.
 *
 *   module-null-sink            sink "mic" (48 kHz, s16le, mono) that the TCP module plays into
 *   module-remap-source         source "phone-mic" = mic.monitor, made the default source, so Chromium's
 *                               getUserMedia (and any Pulse client) hears the phone
 *   module-simple-protocol-tcp  listen=127.0.0.1, random per-session port, playback=true, record=FALSE
 *
 * record=false matters: Android's loopback is shared by every app on the phone, so the port must never hand
 * audio OUT. Its only direction is in, and it is unloaded as soon as the switch goes OFF.
 */
object MicGuest {
    const val SINK = "mic"
    const val SOURCE = "phone-mic"

    const val OWNER = "mic-in"

    /** Random 127.0.0.1 port, fresh for each ON (PulseHost's picker, 20000–60000). */
    fun pickPort(): Int = PulseHost.freeLoopbackPort()

    /**
     * Before PulseHost.start: when the mic is already ON, make sure PulseAudio starts even if Sound is OFF.
     * Registers no modules (they are loaded live by [loadScript]); just keeps PulseHost's owner set non-empty.
     */
    fun prepare() {
        if (MicIn.consent) PulseHost.require(OWNER, emptyList()) else PulseHost.release(OWNER)
    }

    private val fmt get() = "rate=${MicIn.RATE} channels=${MicIn.CHANNELS} format=s16le"

    /**
     * Shell script that loads the three modules and prints `LINUXDESK_MIC_MODULES <ids>` (unload them with
     * [unloadScript]). Idempotent: modules left from an earlier ON are unloaded first.
     */
    fun loadScript(port: Int): String {
        require(port in 1024..65535)
        return """
            set -e
            export PULSE_SERVER=${PulseHost.PULSE_SERVER}
            ${unloadBody()}
            a=${'$'}(pactl load-module module-null-sink sink_name=$SINK $fmt sink_properties=device.description=Phone-Mic-Sink)
            b=${'$'}(pactl load-module module-remap-source master=$SINK.monitor source_name=$SOURCE source_properties=device.description=Phone-Microphone)
            c=${'$'}(pactl load-module module-simple-protocol-tcp listen=127.0.0.1 port=$port playback=true record=false sink=$SINK $fmt)
            pactl set-default-source $SOURCE
            echo "LINUXDESK_MIC_MODULES ${'$'}a ${'$'}b ${'$'}c"
        """.trimIndent()
    }

    /** Unloads every mic module this file ever loads (matched by our names/port args, not by remembered ids), then
     *  gives the default source back to [FALLBACK_SOURCE]. */
    fun unloadScript(): String = "export PULSE_SERVER=${PulseHost.PULSE_SERVER}\n" + unloadBody()

    /** The source Linux records from while the phone mic is not loaded: PulseHost's silent placeholder, NEVER
     *  ld.monitor (that was the 21:40 digital loopback: pages "recording the mic" heard the speakers). */
    const val FALLBACK_SOURCE = PulseHost.NO_MIC_SOURCE

    private fun unloadBody() = """
        for id in ${'$'}(pactl list short modules 2>/dev/null | awk '/module-simple-protocol-tcp.*sink=$SINK |module-remap-source.*source_name=$SOURCE |module-null-sink.*sink_name=$SINK /{print ${'$'}1}'); do pactl unload-module "${'$'}id" || true; done
        pactl set-default-source $FALLBACK_SOURCE || true
    """.trimIndent()
}
