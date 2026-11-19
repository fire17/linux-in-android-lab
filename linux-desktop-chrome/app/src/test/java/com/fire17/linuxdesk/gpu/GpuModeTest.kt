package com.fire17.linuxdesk.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GpuModeTest {

    @Test fun supportNeedsKgslAndArm64() {
        assertEquals(GpuMode.Support.SUPPORTED, GpuMode.support(true, true, "arm64-v8a"))
        assertEquals(GpuMode.Support.UNSUPPORTED_GPU, GpuMode.support(false, false, "arm64-v8a"))
        assertEquals(GpuMode.Support.NODE_BLOCKED, GpuMode.support(true, false, "arm64-v8a"))
        assertEquals(GpuMode.Support.UNSUPPORTED_ABI, GpuMode.support(true, true, "x86_64"))
    }

    @Test fun probeOnMissingNodeIsUnsupported() {
        assertEquals(GpuMode.Support.UNSUPPORTED_GPU, GpuMode.probe("arm64-v8a", File("/nonexistent/kgsl-3d0")))
        val tmp = File.createTempFile("kgsl", null).apply { deleteOnExit() }
        assertEquals(GpuMode.Support.SUPPORTED, GpuMode.probe("arm64-v8a", tmp))
    }

    @Test fun setupArgvPerDistro() {
        assertEquals(listOf("/bin/sh", GpuMode.SETUP_SCRIPT, "alpine"), GpuMode.setupArgv("alpine"))
        assertEquals("arch", GpuMode.setupArgv("omarchy").last())
    }

    @Test fun parsesZinkTurnipFromGlxinfo() {
        val out = """
            [gpu-check] marker: mesa-27.0.0-devel-20261025-r2 alpine 2026-11-19T19:00:00Z
            [gpu-check] /dev/kgsl-3d0 visible
                deviceName         = Turnip Adreno (TM) 750
                driverName         = turnip Mesa driver
            OpenGL vendor string: Mesa
            OpenGL renderer string: zink Vulkan 1.4(Turnip Adreno (TM) 750 (MESA_TURNIP))
            OpenGL version string: 4.6 (Compatibility Profile) Mesa 27.0.0-devel
        """.trimIndent()
        val r = GpuMode.parseRenderer(out)
        assertEquals("glxinfo", r.source)
        assertTrue(r.renderer.startsWith("zink"))
        assertTrue(r.hardware)
        assertEquals("gpu: renderer=${r.renderer} hardware=true (via glxinfo)", GpuMode.logLine(r))
    }

    @Test fun llvmpipeIsSoftware() {
        val r = GpuMode.parseRenderer("OpenGL renderer string: llvmpipe (LLVM 22.1.3, 128 bits)\n")
        assertFalse(r.hardware)
        assertFalse(GpuMode.parseRenderer("deviceName = llvmpipe (LLVM 22.1.3, 128 bits)").hardware)
    }

    @Test fun vulkanOnlyAndNothing() {
        val vk = GpuMode.parseRenderer("    deviceName         = Turnip Adreno (TM) 750\n[gpu-check] glxinfo not installed")
        assertEquals("vulkaninfo", vk.source)
        assertTrue(vk.hardware)
        val none = GpuMode.parseRenderer("[gpu-check] vulkaninfo not installed\n[gpu-check] glxinfo not installed")
        assertEquals("none", none.source)
        assertFalse(none.hardware)
        assertTrue(GpuMode.logLine(none).contains("renderer=unknown"))
    }

    @Test fun kgslGalliumNamesCountAsHardware() {
        assertTrue(GpuMode.isHardware("FD750"))
        assertTrue(GpuMode.isHardware("FD 750 (kgsl)"))
        assertFalse(GpuMode.isHardware("softpipe"))
        assertFalse(GpuMode.isHardware("zink Vulkan 1.4(llvmpipe (LLVM 22.1.3, 128 bits))"))
    }
}
