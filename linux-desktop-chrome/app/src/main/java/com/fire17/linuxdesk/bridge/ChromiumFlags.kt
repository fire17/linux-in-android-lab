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
 *  --disable-gpu               Default. proot exposes no GPU device, so software compositing is used.
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
        /** e.g. "he-IL,en-US", from Android's LocaleList. */
        val acceptLanguages: String? = null,
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
        if (!p.useGpu) flags += "--disable-gpu"
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
