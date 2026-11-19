package com.fire17.linuxdesk.bridge

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Builds Chromium's command line from phone geometry. This is plain Kotlin, so ChromiumFlagsTest covers
 * it on the JVM.
 *
 * Flag rationale (verA research, notes/verA-linux-chrome-desktop.json):
 *  --no-sandbox                Mandatory. Android returns EINVAL for unprivileged user namespaces, and
 *                              under proot the setuid sandbox cannot work either. So the browser runs
 *                              UNSANDBOXED; do not use it for banking or other sensitive logins.
 *  --test-type                 Hides the "unsupported command-line flag: --no-sandbox" warning bar.
 *  --touch-events=enabled      Exposes touch events to web pages. lorie's Direct-touch mode supplies a
 *                              real XI2 XIDirectTouch device, which Chromium's X11 touch factory accepts.
 *  --force-device-scale-factor The Android density (about 2.5-3.5 on phones), so the desktop browser
 *                              matches the size of Android UI.
 *  --renderer-process-limit=2  Fewer processes, to stay under Android 12+'s phantom-process cap (32) and
 *                              save memory.
 *  --disable-dev-shm-usage     Android has no real /dev/shm (we bind a directory there anyway).
 *  --disable-gpu               Default. Without a kgsl-enabled guest Mesa there is no usable GPU, so software
 *                              compositing is used.
 *  GPU mode (experimental, OFF by default; Params.useGpu): setup-gpu.sh installs lfdevs' Turnip+Zink Mesa
 *                              and the phone's /dev/kgsl-3d0 (already bound by ProotRuntime's -b /dev) drives
 *                              GL via Zink on Vulkan. Then --disable-gpu is dropped for --ignore-gpu-blocklist
 *                              --use-gl=angle --use-angle=gl (ANGLE on GLX → zink). Params.gpuFallback = true
 *                              (set after the GPU process crashed) forces software again. guestGpuEnv() is
 *                              the Mesa env for X apps and Chromium. notes/gpu-npu-plan.md §2.
 *  --top-chrome-touch-ui       Bigger, touch-friendly tab strip and toolbar in BROWSER mode.
 *  --load-extension            The bundled focus-bridge extension, which pops the Android keyboard when a
 *                              web text field gets focus. Chromium 137+ gates this switch behind the
 *                              feature DisableLoadExtensionCommandLineSwitch, so that feature is disabled.
 */
object ChromiumFlags {

    const val DEFAULT_URL = "https://www.google.com/"

    enum class Mode {
        /** Full desktop Chromium UI (tabs, omnibox), maximized to the X screen. */
        BROWSER,
        /** One app window with no browser UI (--app=URL). */
        APP,
        /** True kiosk fullscreen (--kiosk URL). */
        KIOSK,
    }

    data class Params(
        val mode: Mode,
        val url: String,
        /** Android DisplayMetrics.density, e.g. 2.625. */
        val density: Float,
        /** User scale in percent of [density]; 100 = same size as Android UI. */
        val scalePercent: Int,
        /** LorieView size in real pixels, which is also the X screen size ("native" resolution mode). */
        val screenWidthPx: Int,
        val screenHeightPx: Int,
        val userDataDir: String = "/root/.config/linuxdesk-chromium",
        val extensionDir: String? = null,
        val useGpu: Boolean = false,
        /** GPU was requested but the GPU process crashed this session: back to software (--disable-gpu). */
        val gpuFallback: Boolean = false,
        /** e.g. "he-IL,en-US", from Android's LocaleList. */
        val acceptLanguages: String? = null,
    )

    /** Chromium flags for GPU mode (ANGLE on GLX, which Mesa serves with zink on Turnip/KGSL). */
    val GPU_FLAGS = listOf(
        "--ignore-gpu-blocklist",
        "--use-gl=angle",
        "--use-angle=gl",
        "--enable-gpu-rasterization",
        // Deliberately NOT --disable-gpu-process-crash-limit: after repeated GPU-process crashes Chromium must
        // be free to fall back to software by itself. isGpuCrashLine() pins that fallback for the next launch.
    )

    /**
     * Guest env for X clients and Chromium when GPU mode is on. zink on Turnip (Vulkan over /dev/kgsl-3d0);
     * MESA_VK_WSI_DEBUG=sw because lorie's DRI3 cannot import buffers, so presents go through a CPU copy.
     */
    val GPU_ENV = listOf(
        "MESA_LOADER_DRIVER_OVERRIDE=zink",
        "GALLIUM_DRIVER=zink",
        "TU_DEBUG=noconform",
        "MESA_VK_WSI_DEBUG=sw",
        "VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json",
    )

    /** True when GPU flags apply: requested and not fallen back. */
    fun gpuActive(p: Params): Boolean = p.useGpu && !p.gpuFallback

    /** Guest env to add for X apps / Chromium. Empty when GPU mode is off or fell back. */
    fun guestGpuEnv(useGpu: Boolean, gpuFallback: Boolean = false): List<String> =
        if (useGpu && !gpuFallback) GPU_ENV else emptyList()

    /**
     * A line from Chromium's stderr that means the GPU process died (it then retries, and after a few times
     * falls back by itself; we pin the fallback for the next launch).
     */
    fun isGpuCrashLine(line: String): Boolean {
        val l = line.lowercase(Locale.ROOT)
        // Chromium's own messages (gpu_process_host.cc / gpu_data_manager_impl_private.cc):
        //   "GPU process exited unexpectedly: exit_code=…", "GPU process isn't usable. Goodbye.",
        //   "Exiting GPU process due to errors during initialization", "GPU process crashed"
        return GPU_CRASH_MARKERS.any { it in l }
    }

    private val GPU_CRASH_MARKERS = listOf(
        "gpu process exited unexpectedly",
        "gpu process isn't usable",
        "exiting gpu process due to errors",
        "gpu process crashed",
        "gpu process lost",
    )

    fun scaleFactor(density: Float, scalePercent: Int): Float {
        val pct = scalePercent.coerceIn(25, 400)
        return (density * pct / 100f).coerceIn(0.5f, 6f)
    }

    /** Always uses Locale.US, so a Hebrew or comma-decimal locale never produces "2,63". */
    fun formatScale(f: Float): String = String.format(Locale.US, "%.3f", f).trimEnd('0').trimEnd('.')

    fun normalizeUrl(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return DEFAULT_URL
        if (s.contains("://") || s.startsWith("about:") || s.startsWith("chrome:") || s.startsWith("data:")) return s
        return "https://$s"
    }

    fun build(p: Params): List<String> {
        val scale = scaleFactor(p.density, p.scalePercent)
        val url = normalizeUrl(p.url)
        val flags = mutableListOf(
            "--no-sandbox",
            "--test-type",
            "--no-first-run",
            "--no-default-browser-check",
            "--password-store=basic",
            "--user-data-dir=${p.userDataDir}",
            "--ozone-platform=x11",
            "--touch-events=enabled",
            "--force-device-scale-factor=${formatScale(scale)}",
            "--renderer-process-limit=2",
            "--disable-dev-shm-usage",
        )
        if (gpuActive(p)) flags += GPU_FLAGS else flags += "--disable-gpu"
        if (p.screenWidthPx > 0 && p.screenHeightPx > 0) {
            // These switches are in DIPs. fit-window.sh fixes the size in real pixels afterwards anyway.
            val w = (p.screenWidthPx / scale).roundToInt().coerceAtLeast(200)
            val h = (p.screenHeightPx / scale).roundToInt().coerceAtLeast(200)
            flags += "--window-position=0,0"
            flags += "--window-size=$w,$h"
        }
        p.acceptLanguages?.takeIf { it.isNotBlank() }?.let { flags += "--accept-lang=$it" }
        if (p.extensionDir != null) {
            flags += "--load-extension=${p.extensionDir}"
            flags += "--disable-features=DisableLoadExtensionCommandLineSwitch"
        }
        when (p.mode) {
            Mode.BROWSER -> {
                flags += "--start-maximized"
                flags += "--top-chrome-touch-ui=enabled"
                flags += url
            }
            Mode.APP -> flags += "--app=$url"
            Mode.KIOSK -> {
                flags += "--kiosk"
                flags += url
            }
        }
        return flags
    }
}
