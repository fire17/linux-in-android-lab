package com.fire17.linuxdesk.hw

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure parts of HwProbe (mappers + summary); the Android probes themselves need a device. */
class HwProbeTest {

    @Test fun fold6IsQualcommHexagonV75() {
        val f = HwProbe.socFamily("QTI", "SM8650")
        assertEquals("qualcomm", f.family)
        assertEquals("Snapdragon 8 Gen 3", f.name)
        assertEquals("hexagon", f.npu)
        assertEquals("V75", f.htp)
    }

    @Test fun otherFamilies() {
        assertEquals("google_tensor", HwProbe.socFamily("Google", "Tensor G4").family)
        assertEquals("mediatek", HwProbe.socFamily("Mediatek", "MT6989").family)
        assertEquals("apu", HwProbe.socFamily("Mediatek", "MT6989").npu)
        assertEquals("samsung_exynos", HwProbe.socFamily("Samsung", "Exynos 2400").family)
        assertEquals("unknown", HwProbe.socFamily("", "").family)
        assertEquals("none_known", HwProbe.socFamily("", "").npu)
        assertEquals("qualcomm", HwProbe.socFamily("", "", hardware = "qcom").family)
        assertEquals("", HwProbe.socFamily("QTI", "SM7450").htp)   // unknown model: no HTP guess
    }

    @Test fun gpuFamilies() {
        assertEquals("adreno", HwProbe.gpuFamily("Qualcomm", "Adreno (TM) 750"))
        assertEquals("adreno", HwProbe.gpuFamily("Google Inc. (Qualcomm)", "ANGLE (Qualcomm, Adreno (TM) 750, OpenGL ES 3.2)"))
        assertEquals("mali", HwProbe.gpuFamily("ARM", "Mali-G715"))
        assertEquals("xclipse", HwProbe.gpuFamily("Samsung", "Samsung Xclipse 940"))
        assertEquals("software", HwProbe.gpuFamily("Google Inc.", "Android Emulator OpenGL ES Translator"))
        assertEquals("unknown", HwProbe.gpuFamily("", ""))
    }

    @Test fun summaryIsReadableAndTolerant() {
        assertTrue(HwProbe.summary(JSONObject()).contains("GPU: ?"))
        val j = JSONObject()
            .put("device", JSONObject().put("manufacturer", "samsung").put("model", "SM-F956B").put("android", "15").put("sdk", 35)
                .put("abis", JSONArray(listOf("arm64-v8a"))))
            .put("soc", JSONObject().put("manufacturer", "QTI").put("model", "SM8650").put("name_hint", "Snapdragon 8 Gen 3"))
            .put("gpu", JSONObject().put("gl", JSONObject().put("renderer", "Adreno (TM) 750").put("version", "OpenGL ES 3.2"))
                .put("nodes", JSONObject().put("/dev/kgsl-3d0", JSONObject().put("exists", true).put("open_ro", true))))
            .put("vulkan", JSONObject().put("present", true).put("api", "1.3.0"))
            .put("npu", JSONObject().put("family_hint", "hexagon").put("hexagon_htp", "V75"))
            .put("cameras", JSONObject().put("cameras", JSONArray().put(JSONObject().put("id", "0").put("facing", "back")
                .put("megapixels", 50.0).put("focal_mm", JSONArray(listOf(6.3))).put("physical_ids", JSONArray(listOf("2", "3"))))))
        val s = HwProbe.summary(j)
        assertTrue(s, s.contains("SM-F956B"))
        assertTrue(s, s.contains("Snapdragon 8 Gen 3"))
        assertTrue(s, s.contains("Adreno (TM) 750"))
        assertTrue(s, s.contains("/dev/kgsl-3d0"))
        assertTrue(s, s.contains("Vulkan: 1.3.0"))
        assertTrue(s, s.contains("HTP V75"))
        assertTrue(s, s.contains("#0 back 50.0MP f=6.3mm logical(2)"))
    }
}
