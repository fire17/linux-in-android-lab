package com.fire17.linuxdesk.x11

import android.view.KeyEvent
import com.fire17.linuxdesk.x11.DesktopKeyBar.Keys
import com.fire17.linuxdesk.x11.DesktopKeyBar.Mod
import org.junit.Assert.assertEquals
import org.junit.Test

/** The X key sequences the bottom key bar sends (what i3 sees), without views: DesktopKeyBar.Keys only. */
class DesktopKeyBarTest {

    private val sent = ArrayList<String>()
    private val keys = Keys { code, down -> sent += "${name(code)}${if (down) "↓" else "↑"}"; true }

    @Test fun superSpaceIsOneChordWithSuperHeldAroundSpace() {
        keys.superSpace()
        assertEquals(listOf("Super↓", "Space↓", "Space↑", "Super↑"), sent)
        assertEquals(Mod.OFF, keys.mods[KeyEvent.KEYCODE_META_LEFT])
    }

    @Test fun stickySuperThenSpaceIsTheSameChord() {
        keys.tapModifier(KeyEvent.KEYCODE_META_LEFT, 1_000)
        keys.tapKey(KeyEvent.KEYCODE_SPACE)
        assertEquals(listOf("Super↓", "Space↓", "Space↑", "Super↑"), sent)
        assertEquals(Mod.OFF, keys.mods[KeyEvent.KEYCODE_META_LEFT])
    }

    @Test fun doubleTapLocksAndALockedModifierStaysDown() {
        keys.tapModifier(KeyEvent.KEYCODE_CTRL_LEFT, 1_000)
        keys.tapModifier(KeyEvent.KEYCODE_CTRL_LEFT, 1_200)          // within 350 ms → locked
        assertEquals(Mod.LOCKED, keys.mods[KeyEvent.KEYCODE_CTRL_LEFT])
        keys.tapKey(KeyEvent.KEYCODE_TAB); keys.tapKey(KeyEvent.KEYCODE_TAB)
        assertEquals(listOf("Ctrl↓", "Tab↓", "Tab↑", "Tab↓", "Tab↑"), sent)
        keys.tapModifier(KeyEvent.KEYCODE_CTRL_LEFT, 5_000)          // tap again → off
        assertEquals("Ctrl↑", sent.last())
        assertEquals(Mod.OFF, keys.mods[KeyEvent.KEYCODE_CTRL_LEFT])
    }

    @Test fun slowSecondTapTurnsTheModifierOff() {
        keys.tapModifier(KeyEvent.KEYCODE_ALT_LEFT, 1_000)
        keys.tapModifier(KeyEvent.KEYCODE_ALT_LEFT, 2_000)
        assertEquals(listOf("Alt↓", "Alt↑"), sent)
    }

    @Test fun superSpaceWithLockedSuperLeavesSuperDown() {
        keys.tapModifier(KeyEvent.KEYCODE_META_LEFT, 1_000)
        keys.tapModifier(KeyEvent.KEYCODE_META_LEFT, 1_100)          // locked
        sent.clear()
        keys.superSpace()
        assertEquals(listOf("Space↓", "Space↑"), sent)
        assertEquals(Mod.LOCKED, keys.mods[KeyEvent.KEYCODE_META_LEFT])
    }

    @Test fun holdAndReleaseAndReleaseAllLeaveNothingStuck() {
        keys.hold(KeyEvent.KEYCODE_SHIFT_LEFT)
        keys.tapModifier(KeyEvent.KEYCODE_CTRL_LEFT, 1_000)
        keys.tapModifier(KeyEvent.KEYCODE_CTRL_LEFT, 1_100)          // locked
        keys.release(KeyEvent.KEYCODE_SHIFT_LEFT)
        keys.releaseAll()
        assertEquals(listOf("Shift↓", "Ctrl↓", "Shift↑", "Ctrl↑"), sent)
        assertEquals(true, keys.mods.values.all { it == Mod.OFF })
    }

    private fun name(code: Int) = when (code) {
        KeyEvent.KEYCODE_META_LEFT -> "Super"
        KeyEvent.KEYCODE_CTRL_LEFT -> "Ctrl"
        KeyEvent.KEYCODE_ALT_LEFT -> "Alt"
        KeyEvent.KEYCODE_SHIFT_LEFT -> "Shift"
        KeyEvent.KEYCODE_SPACE -> "Space"
        KeyEvent.KEYCODE_TAB -> "Tab"
        else -> "k$code"
    }
}
