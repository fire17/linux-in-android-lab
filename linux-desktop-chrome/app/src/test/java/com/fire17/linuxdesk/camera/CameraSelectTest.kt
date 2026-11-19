package com.fire17.linuxdesk.camera

import com.fire17.linuxdesk.camera.CameraSelect.Facing.BACK
import com.fire17.linuxdesk.camera.CameraSelect.Facing.EXTERNAL
import com.fire17.linuxdesk.camera.CameraSelect.Facing.FRONT
import com.fire17.linuxdesk.camera.CameraSelect.Lens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic devices only: no per-phone guesses live in CameraSelect, so none are needed here. */
class CameraSelectTest {
    private fun ids(c: CameraSelect.Choice) = c.ordered.map { it.id }

    @Test fun singleCameraDevice() {
        val c = CameraSelect.choose(listOf(Lens("0", BACK, 4.0f)))
        assertEquals(listOf("0"), ids(c)); assertEquals("0", c.default!!.id)
    }

    @Test fun logicalPlusThreePhysical() {
        // Logical back 0 = {2 wide 6.3, 3 ultra 2.2, 4 tele 9.0}; front 1; lens 2 is ALSO a top-level id.
        val dev = listOf(
            Lens("0", BACK, 6.3f, physicalIds = listOf("2", "3", "4")),
            Lens("1", FRONT, 3.0f),
            Lens("2", BACK, 6.3f),
            Lens("2", BACK, 6.3f, physicalOf = "0"),
            Lens("3", BACK, 2.2f, physicalOf = "0"),
            Lens("4", BACK, 9.0f, physicalOf = "0"),
        )
        val c = CameraSelect.choose(dev)
        assertEquals("0", c.default!!.id)                         // the back logical camera
        assertEquals(listOf("0", "2", "3", "4", "1"), ids(c))     // default, std-focal next, ultra→tele, front last
        assertEquals(ids(c).toSet().size, ids(c).size)            // every id once
        assertTrue(c.hidden.keys.any { it.startsWith("2@0") })   // physical 2 hidden: listed as top-level 2
    }

    @Test fun frontOnlyDevice() {
        // No back camera at all (e.g. a tablet/Chromebook-style device): the front camera is the default.
        val c = CameraSelect.choose(listOf(Lens("1", FRONT, 2.5f)))
        assertEquals("1", c.default!!.id); assertEquals(listOf("1"), ids(c))
    }

    @Test fun frontOnlyTieGoesToShorterFocal() {
        val c = CameraSelect.choose(listOf(Lens("1", FRONT, 2.5f), Lens("7", FRONT, 1.8f)))
        assertEquals("7", c.default!!.id)
        assertEquals(listOf("7", "1"), ids(c))
    }

    @Test fun noLogicalPicksStandardBackLensAmongMany() {
        // Old-style phone: every lens a separate id, no logical camera. Main = closest to the median.
        val c = CameraSelect.choose(listOf(Lens("0", BACK, 5.4f), Lens("2", BACK, 1.9f), Lens("3", BACK, 7.0f),
            Lens("1", FRONT, 2.9f)))
        assertEquals("0", c.default!!.id)
        assertEquals(listOf("0", "2", "3", "1"), ids(c))
    }

    @Test fun unstreamableAndUnknownFocalHandled() {
        val c = CameraSelect.choose(listOf(Lens("0", BACK, null), Lens("5", BACK, 4.3f), Lens("9", BACK, 3.0f, streamable = false),
            Lens("20", EXTERNAL, null)))
        assertEquals("5", c.default!!.id)                          // known focal beats unknown
        assertEquals(listOf("5", "0", "20"), ids(c))               // back, then external; 9 hidden
        assertEquals("no YUV output", c.hidden["9"])
    }

    @Test fun emptyDevice() {
        val c = CameraSelect.choose(emptyList())
        assertNull(c.default); assertTrue(c.ordered.isEmpty())
    }

    @Test fun standardFocalRule() {
        assertEquals(6.3f, CameraSelect.standardFocal(listOf(2.2f, 6.3f, 9.0f)))
        assertEquals(6.3f, CameraSelect.standardFocal(listOf(30f, 2.2f, 9.0f, 6.3f)))   // ultra, main, 3x, 10x → main
        assertEquals(4.0f, CameraSelect.standardFocal(listOf(4.0f)))
        assertNull(CameraSelect.standardFocal(listOf(null, null)))
    }
}
