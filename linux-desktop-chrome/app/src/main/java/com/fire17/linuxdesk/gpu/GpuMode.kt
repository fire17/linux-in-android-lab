package com.fire17.linuxdesk.gpu

import java.io.File
import java.util.Locale

/**
 * GPU acceleration inside the Linux guest (experimental, OFF by default; notes/gpu-npu-plan.md §2 / v2).
 *
 * Path: lfdevs' kgsl-enabled Mesa (Turnip Vulkan + Zink GL) in the guest → the phone's /dev/kgsl-3d0, which
 * ProotRuntime already binds (-b /dev) and which an untrusted_app may open. Only Adreno (KGSL) phones qualify;
 * everything else stays on llvmpipe and the setting says "not supported on this GPU yet".
 *
 * This file is plain Kotlin (no android.*), so GpuModeTest covers it on the JVM. The Android glue lives in
 * SessionService: support check → setup-gpu.sh once → env + Chromium flags → self-check → crash fallback.
 */
object GpuMode {

    /** Guest marker written by setup-gpu.sh on success (holds the pinned Mesa build id). */
    const val MARKER = "etc/linuxdesk/gpu-v1"
    const val SETUP_SCRIPT = "/opt/linuxdesk/setup-gpu.sh"
    const val CHECK_SCRIPT = "/opt/linuxdesk/gpu-check.sh"
    const val KGSL_NODE = "/dev/kgsl-3d0"

    enum class Support {
        /** /dev/kgsl-3d0 exists and opens: Adreno with KGSL, Turnip can drive it. */
        SUPPORTED,
        /** No KGSL node (Mali, Xclipse, PowerVR, emulator, …). */
        UNSUPPORTED_GPU,
        /** The node exists but this app cannot open it (SELinux policy / permissions on this build). */
        NODE_BLOCKED,
        /** Not aarch64: lfdevs ships arm64 builds only. */
        UNSUPPORTED_ABI,
    }

    /**
     * Decides support from facts the caller probes ([kgslExists], [kgslOpens]; HwProbe reports the same two as
     * gpu.nodes["/dev/kgsl-3d0"].exists / open_ro) and the primary ABI.
     */
    fun support(kgslExists: Boolean, kgslOpens: Boolean, primaryAbi: String): Support = when {
        primaryAbi != "arm64-v8a" -> Support.UNSUPPORTED_ABI
        !kgslExists -> Support.UNSUPPORTED_GPU
        !kgslOpens -> Support.NODE_BLOCKED
        else -> Support.SUPPORTED
    }

    /** Probes the KGSL node from the app process (same uid/SELinux domain as proot and the guest). */
    fun probe(primaryAbi: String, node: File = File(KGSL_NODE)): Support {
        val exists = node.exists()
        val opens = exists && try { java.io.FileInputStream(node).close(); true } catch (_: Throwable) { false }
        return support(exists, opens, primaryAbi)
    }

    enum class Start {
        /** Setting OFF (the default): software, setup-gpu.sh never runs, whatever Mesa the guest has. */
        OFF,
        /** Setting ON, but Chromium's GPU process crashed in an earlier session: software until "Retry GPU". */
        PINNED_SOFTWARE,
        /** Setting ON and not pinned: prepare the driver and try the GPU. */
        TRY_GPU,
    }

    /**
     * The Start decision. It follows the user's setting only: an installed kgsl Mesa (the gpu-v1 marker) or a
     * working /dev/kgsl-3d0 never turns GPU mode on by itself.
     */
    fun startDecision(setting: Boolean, crashPinned: Boolean): Start = when {
        !setting -> Start.OFF
        crashPinned -> Start.PINNED_SOFTWARE
        else -> Start.TRY_GPU
    }

    /** Distro id as Distro.id ("alpine" / "omarchy") → the argument setup-gpu.sh takes. */
    fun setupArgv(distroId: String): List<String> = listOf("/bin/sh", SETUP_SCRIPT, if (distroId == "omarchy") "arch" else "alpine")

    // ------------------------------------------------------------------------------------ self-check parsing

    data class Renderer(
        /** "OpenGL renderer string" from glxinfo -B, or the Vulkan deviceName, or "" if none was printed. */
        val renderer: String,
        /** True when the renderer is the phone's GPU (Turnip / Adreno / FD7xx via zink or kgsl), not llvmpipe. */
        val hardware: Boolean,
        val source: String,
    )

    /**
     * Parses gpu-check.sh output. It prints glxinfo -B and/or vulkaninfo --summary; we take the GL renderer
     * first (that is what X apps and Chromium's ANGLE-on-GL use), else the Vulkan deviceName.
     */
    fun parseRenderer(output: String): Renderer {
        var gl = ""
        var vk = ""
        for (raw in output.lineSequence()) {
            val line = raw.trim()
            if (gl.isEmpty() && line.startsWith("OpenGL renderer string:")) gl = line.substringAfter(':').trim()
            if (vk.isEmpty() && line.startsWith("deviceName")) vk = line.substringAfter('=').trim()
        }
        return when {
            gl.isNotEmpty() -> Renderer(gl, isHardware(gl), "glxinfo")
            vk.isNotEmpty() -> Renderer(vk, isHardware(vk), "vulkaninfo")
            else -> Renderer("", false, "none")
        }
    }

    fun isHardware(renderer: String): Boolean {
        val r = renderer.lowercase(Locale.ROOT)
        if ("llvmpipe" in r || "softpipe" in r || "swrast" in r || "lavapipe" in r) return false
        return "turnip" in r || "adreno" in r || Regex("\\bfd ?[0-9]{3}\\b").containsMatchIn(r) || "kgsl" in r
    }

    /** The one session-log line sys.logs readers grep for. */
    fun logLine(r: Renderer): String =
        "gpu: renderer=${r.renderer.ifEmpty { "unknown" }} hardware=${r.hardware} (via ${r.source})"
}
