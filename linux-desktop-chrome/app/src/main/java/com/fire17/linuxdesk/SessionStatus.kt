package com.fire17.linuxdesk

/**
 * What the session is REALLY running, from the start scripts' markers (Tami 13:42, AC5CC9EB: "I can't understand what
 * is actually running ... have it adaptive to what is really running"):
 *
 *   LINUXDESK_STAGE_NOW <component>   a component starts now (Weston, Hyprland, Omarchy shell, Sway, Chrome, …)
 *   LINUXDESK_DESKTOP <name>          the desktop that answers (Hyprland, Sway, i3, Omarchy Mobile, Chrome)
 *   LINUXDESK_WINDOW_UP …             on screen (handled by SessionService / GenieRuntime as before)
 *   LINUXDESK_ERROR <reason>          something failed, now or later (SessionErrors pops it up)
 *
 * Pure (no Android), one instance per launched start script; SessionService turns [onLine]'s answers into states.
 */
class SessionStatus {
    /** The desktop that answered, empty until a LINUXDESK_DESKTOP line. */
    @Volatile var desktop = ""
        private set
    /** The component starting now, empty until a LINUXDESK_STAGE_NOW line. */
    @Volatile var stage = ""
        private set
    /** The script reported its own failure (LINUXDESK_ERROR), so its exit needs no second pop-up. */
    @Volatile var errorSeen = false
        private set
    /** The last LINUXDESK_ERROR reason, empty while none. */
    @Volatile var lastError = ""
        private set

    sealed class Event {
        data class Stage(val component: String) : Event()
        data class Desktop(val name: String) : Event()
        data class Error(val reason: String) : Event()
    }

    /** One script line; null when it is no status marker. */
    fun onLine(line: String): Event? = when {
        line.startsWith(STAGE) -> line.removePrefix(STAGE).trim().takeIf { it.isNotEmpty() }
            ?.let { stage = it; Event.Stage(it) }
        line.startsWith(DESKTOP) -> line.removePrefix(DESKTOP).trim().takeIf { it.isNotEmpty() }
            ?.let { desktop = it; Event.Desktop(it) }
        line.startsWith(ERROR) -> line.removePrefix(ERROR).trim().ifEmpty { "unknown error" }
            .let { errorSeen = true; lastError = it; Event.Error(it) }
        else -> null
    }

    /** What runs once the desktop is on screen: "Hyprland", "Sway + Chrome", else the last component started, else null. */
    fun runningName(): String? = when {
        desktop.isEmpty() -> stage.ifEmpty { null }
        stage == "Chrome" && desktop != "Chrome" -> "$desktop + Chrome"
        else -> desktop
    }

    companion object {
        const val STAGE = "LINUXDESK_STAGE_NOW "
        const val DESKTOP = "LINUXDESK_DESKTOP "
        const val ERROR = "LINUXDESK_ERROR "
    }
}
