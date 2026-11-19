package com.fire17.linuxdesk.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraDiagTest {
    private fun e(id: String, facing: String, f: Float?, of: String? = null, phys: List<String> = emptyList(),
                  exposed: Boolean = true, why: String? = null) =
        CameraDiag.Entry(id, facing, listOfNotNull(f), of, phys, 4000 to 3000, 4000 to 3000, 4080 to 3060,
            1280 to 720, exposed, why)

    // A Fold6-like shape: logical back 0 = {2,3,4}, front 1, cover 5; lens 2 is also top-level.
    private val fold = listOf(
        e("0", "back", 6.3f, phys = listOf("2", "3", "4")),
        e("2", "back", 6.3f, of = "0", exposed = false, why = "also a top-level camera (listed there)"),
        e("3", "back", 2.2f, of = "0"),
        e("4", "back", 9.0f, of = "0"),
        e("1", "front", 3.0f),
        e("2", "back", 6.3f),
    )

    @Test fun summaryCountsAndMarks() {
        val s = CameraDiag.summary(fold)
        assertTrue(s, s.startsWith("Phone cameras for Linux: 5 of 6 ids (3 top-level)"))
        assertTrue(s, "#0 back f=6.30mm logical[2,3,4] maxYUV=4000x3000 maxJPEG=4000x3000 stream=1280x720 ✓" in s)
        assertTrue(s, "#3 back f=2.20mm   lens of 0" in s)
        assertTrue(s, "✗ also a top-level camera (listed there)" in s)
    }

    @Test fun jsonCarriesEverythingForTheTeam() {
        val j = CameraDiag.json(fold)
        assertEquals(5, j.getInt("exposed")); assertEquals(6, j.getInt("ids"))
        val c3 = j.getJSONArray("cameras").getJSONObject(2)
        assertEquals("3", c3.getString("id")); assertEquals("0", c3.getString("physical_of"))
        assertEquals("1280x720", c3.getString("stream")); assertEquals("4000x3000", c3.getString("max_yuv"))
        assertEquals("Phone back 2.2 mm (lens 3 of 0)", c3.getString("label"))   // same label as CameraServer
        val hidden = j.getJSONArray("cameras").getJSONObject(1)
        assertFalse(hidden.getBoolean("exposed")); assertFalse(hidden.has("label"))
        assertTrue(j.getJSONArray("cameras").getJSONObject(0).isNull("physical_of"))
    }

    @Test fun jsonCarriesGenericDefaultAndOrder() {
        val j = CameraDiag.json(fold)
        assertEquals("0", j.getString("default"))
        assertEquals("[\"0\",\"2\",\"3\",\"4\",\"1\"]", j.getJSONArray("order").toString())
        assertTrue(j.getJSONObject("hidden").has("2@0"))
    }

    @Test fun unknownFocalIsShownNotGuessed() {
        assertTrue(CameraDiag.summary(listOf(e("7", "external", null))).contains("#7 external f=? single"))
    }
}
