package com.fire17.phoneinphone.ondevice

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.Surface
import com.fire17.phoneinphone.backend.BackendListener
import com.fire17.phoneinphone.backend.BackendStats
import com.fire17.phoneinphone.backend.PhoneBackend
import com.fire17.phoneinphone.backend.Stage
import com.fire17.phoneinphone.backend.StageStatus
import com.fire17.phoneinphone.backend.TouchAction
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Backend "OnDevice" — an INSTRUMENTED EXPERIMENT, not a working phone.
 *
 * Tami's thesis: "Android is a kind of Linux; if we can run Linux in an app we can run Android in
 * an app." This backend climbs that ladder one rung at a time on THIS phone, with no root, and
 * reports exactly which rung held and which one broke:
 *
 *   TRACK A — userspace container (proot):
 *     A1 proot binaries in nativeLibraryDir (+16 KB ELF alignment)
 *     A2 Linux in the app: Alpine minirootfs under proot  -> `uname -a; id; cat /etc/os-release`
 *     A3 Android userland present (an AOSP/redroid /system tree the user imported)
 *     A4 a guest BIONIC binary runs under proot           -> /system/bin/toybox uname -a
 *     A5 guest binder: /dev/binder + guest servicemanager -> expected to FAIL: the host already has
 *        a context manager and SELinux forbids an app from becoming one. Fixing it needs a
 *        userspace binder shim (twoyi-style libvbinder / LD_PRELOAD broker) = NOT_IMPLEMENTED.
 *     A6 zygote + system_server + SurfaceFlinger + launcher -> NOT_IMPLEMENTED (depends on A5 and
 *        on a gralloc/HWC replacement that renders into our Surface, e.g. AOSP goldfish encoders).
 *
 *   TRACK B — hardware VM (AVF), see [AvfProbe]. Runtime-gated: feature + permissions + hidden API.
 *
 * Nothing here pretends. Every stage ends OK / FAIL / SKIPPED / NOT_IMPLEMENTED with the evidence.
 */
class OnDeviceBackend(private val context: Context, private val runAvf: Boolean) : PhoneBackend {

    override val id = "ondevice"
    override val displayName = "On-device (experimental)"
    override val rendersVideo = false

    private val running = AtomicBoolean(false)
    private var listener: BackendListener? = null
    private val stages = LinkedHashMap<String, Stage>()

    private val alpineRoot = File(context.filesDir, "alpine-root")
    val androidRoot = File(context.filesDir, "android-root")
    val androidTarball = File(context.filesDir, "android-userland.tar.gz")

    override fun start(listener: BackendListener) {
        if (!running.compareAndSet(false, true)) return
        this.listener = listener
        Thread({
            var err: Throwable? = null
            try { climb() } catch (t: Throwable) { err = t; log("FATAL ${t.javaClass.simpleName}: ${t.message}") }
            log("---- summary ----")
            stages.values.forEach { log("${it.status.mark} ${it.title}: ${it.detail}") }
            running.set(false)
            listener.onStopped(err)
        }, "pip-ondevice").start()
    }

    private fun report(key: String, title: String, status: StageStatus, detail: String = "") {
        val s = Stage(key, title, status, detail)
        stages[key] = s
        listener?.onStage(s)
    }

    private fun log(s: String) {
        Log.i(TAG, s)
        listener?.onLog(s)
    }

    private fun climb() {
        log("device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ABIs ${Build.SUPPORTED_ABIS.joinToString()}")
        log("targetSdk ${context.applicationInfo.targetSdkVersion}, nativeLibraryDir ${context.applicationInfo.nativeLibraryDir}")

        // Pre-register every stage so the whole ladder is visible from the first frame.
        report("a1", "A1 proot binaries in nativeLibraryDir", StageStatus.PENDING)
        report("a2", "A2 Linux userland (Alpine) under proot", StageStatus.PENDING)
        report("a3", "A3 Android userland (/system tree) present", StageStatus.PENDING)
        report("a4", "A4 guest bionic binary runs under proot", StageStatus.PENDING)
        report("a5", "A5 guest binder / servicemanager", StageStatus.PENDING)
        report("a6", "A6 zygote → system_server → SurfaceFlinger → launcher", StageStatus.PENDING)
        if (runAvf) report("avf.api", "B  AVF hardware VM track", StageStatus.PENDING)

        val trackA = trackProot()
        if (!trackA) log("track A stopped at the first failing rung (see ✗ above)")

        if (runAvf && running.get()) {
            log("---- track B: AVF ----")
            AvfProbe(context, ::report, ::log).run()
        } else if (!runAvf) {
            report("avf.api", "B  AVF hardware VM track", StageStatus.SKIPPED, "disabled in settings")
        }
    }

    // ------------------------------------------------------------------ TRACK A

    private fun trackProot(): Boolean {
        val runner = ProotRunner(context)

        // A1
        report("a1", "A1 proot binaries in nativeLibraryDir", StageStatus.RUNNING)
        val missing = runner.requiredLibs().filter { !it.exists() }
        if (missing.isNotEmpty()) {
            report("a1", "A1 proot binaries in nativeLibraryDir", StageStatus.FAIL,
                "missing: ${missing.joinToString { it.name }} — CI must run scripts/fetch-proot.sh (and this ABI must be arm64-v8a or x86_64)")
            return skipRest("a2", "a3", "a4", "a5", "a6")
        }
        val align = ProotRunner.loadAlignments(runner.proot)
        val align16k = align?.all { it >= 0x4000 } == true
        report("a1", "A1 proot binaries in nativeLibraryDir", StageStatus.OK,
            "libproot.so ${runner.proot.length() / 1024} KiB, PT_LOAD align ${align?.joinToString { "0x" + java.lang.Long.toHexString(it) }}" +
                if (!align16k) " (WARNING: not 16 KB aligned)" else " (16 KB ok)")

        // A2 — the proven part of the thesis: Linux inside the app.
        report("a2", "A2 Linux userland (Alpine) under proot", StageStatus.RUNNING, "extracting…")
        val alpineAsset = alpineAssetName()
        if (alpineAsset == null) {
            report("a2", "A2 Linux userland (Alpine) under proot", StageStatus.FAIL,
                "no Alpine asset for ABI ${Build.SUPPORTED_ABIS[0]} — CI must run scripts/fetch-rootfs.sh")
            return skipRest("a3", "a4", "a5", "a6")
        }
        if (!File(alpineRoot, "bin/busybox").exists()) {
            alpineRoot.deleteRecursively()
            val ex = TarExtractor(alpineRoot)
            context.assets.open(alpineAsset).use { ex.extractGz(it) }
            File(alpineRoot, "etc/resolv.conf").writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
            log("alpine extracted: ${ex.summary()}")
        }
        val r2 = runner.run(alpineRoot, listOf("/bin/sh", "-c", "uname -a; id; head -n2 /etc/os-release; echo PIP_OK"), timeoutSec = 30)
        r2.output.lines().filter { it.isNotBlank() }.forEach { log("[alpine] $it") }
        if (!r2.output.contains("PIP_OK")) {
            report("a2", "A2 Linux userland (Alpine) under proot", StageStatus.FAIL,
                "exit=${r2.exitCode}${if (r2.timedOut) " TIMEOUT" else ""}: ${r2.output.lines().lastOrNull { it.isNotBlank() } ?: "no output"}")
            return skipRest("a3", "a4", "a5", "a6")
        }
        report("a2", "A2 Linux userland (Alpine) under proot", StageStatus.OK,
            "${r2.output.lines().firstOrNull { it.startsWith("Linux") }?.take(70) ?: "ran"} (${r2.millis} ms)")

        // A3 — an Android /system tree. Too big to bundle (~0.7 GB compressed for redroid 16);
        // the user imports it (in-app picker, or scripts/ondevice-push-userland.sh via run-as).
        report("a3", "A3 Android userland (/system tree) present", StageStatus.RUNNING)
        if (!File(androidRoot, "system/build.prop").exists() && androidTarball.exists()) {
            report("a3", "A3 Android userland (/system tree) present", StageStatus.RUNNING,
                "extracting ${androidTarball.length() / (1024 * 1024)} MiB…")
            androidRoot.deleteRecursively()
            val ex = TarExtractor(androidRoot) { bytes, path ->
                report("a3", "A3 Android userland (/system tree) present", StageStatus.RUNNING,
                    "extracting… ${bytes / (1024 * 1024)} MiB  $path".take(110))
            }
            androidTarball.inputStream().use { ex.extractGz(it) }
            log("android userland extracted: ${ex.summary()}")
        }
        val buildProp = File(androidRoot, "system/build.prop")
        if (!buildProp.exists()) {
            report("a3", "A3 Android userland (/system tree) present", StageStatus.FAIL,
                "no ${buildProp.path}. Build one with scripts/make-android-userland.sh (docker export of redroid) and import it")
            return skipRest("a4", "a5", "a6")
        }
        val props = buildProp.readLines().mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        report("a3", "A3 Android userland (/system tree) present", StageStatus.OK,
            "${props["ro.build.version.release"] ?: "?"} / SDK ${props["ro.build.version.sdk"] ?: "?"} / ${props["ro.product.cpu.abilist"] ?: props["ro.system.product.cpu.abilist"] ?: "?"}")

        // A4 — bionic userspace of the GUEST running on the host kernel via proot.
        report("a4", "A4 guest bionic binary runs under proot", StageStatus.RUNNING)
        val guestEnv = mapOf(
            "ANDROID_ROOT" to "/system",
            "ANDROID_DATA" to "/data",
            "ANDROID_ART_ROOT" to "/apex/com.android.art",
            "ANDROID_I18N_ROOT" to "/apex/com.android.i18n",
            "ANDROID_TZDATA_ROOT" to "/apex/com.android.tzdata",
            "PATH" to "/system/bin:/system/xbin:/vendor/bin",
        )
        File(androidRoot, "data").mkdirs()
        File(androidRoot, "dev").mkdirs(); File(androidRoot, "proc").mkdirs(); File(androidRoot, "sys").mkdirs()
        val hasGuestApexRuntime = File(androidRoot, "apex/com.android.runtime/bin/linker64").exists() ||
            File(androidRoot, "system/apex/com.android.runtime").exists()
        log("guest /apex runtime flattened in tarball: $hasGuestApexRuntime")
        val attempts = buildList {
            add("guest /apex" to ProotRunner.DEFAULT_BINDS)
            // fallback: borrow the HOST's runtime APEX (linker64 + bionic). Version skew is likely,
            // but it tells us whether the blocker is the APEX layout or something deeper.
            if (!hasGuestApexRuntime) add("host /apex bound" to ProotRunner.DEFAULT_BINDS + "/apex")
        }
        var a4ok = false
        for ((label, binds) in attempts) {
            val r = runner.run(androidRoot, listOf("/system/bin/sh", "-c", "toybox uname -a; getprop ro.build.fingerprint 2>&1 | head -1; echo PIP_OK"),
                binds = binds, extraEnv = guestEnv, timeoutSec = 30)
            r.output.lines().filter { it.isNotBlank() }.take(20).forEach { log("[android/$label] $it") }
            if (r.output.contains("PIP_OK")) {
                report("a4", "A4 guest bionic binary runs under proot", StageStatus.OK, "$label: ${r.output.lines().firstOrNull()?.take(70)} (${r.millis} ms)")
                a4ok = true
                break
            } else {
                log("A4 attempt '$label' failed: exit=${r.exitCode}${if (r.timedOut) " TIMEOUT" else ""}")
            }
        }
        if (!a4ok) {
            report("a4", "A4 guest bionic binary runs under proot", StageStatus.FAIL,
                "guest /system/bin/sh did not run (see log). Likely: APEX not flattened in the tarball, or linker/namespace config mismatch")
            return skipRest("a5", "a6")
        }

        // A5 — binder. Expected to fail today; we want the exact error.
        report("a5", "A5 guest binder / servicemanager", StageStatus.RUNNING)
        val binderNodes = listOf("/dev/binder", "/dev/hwbinder", "/dev/vndbinder", "/dev/binderfs").map { p ->
            val f = File(p); "$p:${if (f.exists()) (if (f.canRead()) "r" else "-") + (if (f.canWrite()) "w" else "-") else "absent"}"
        }
        log("host binder nodes as seen by the app: ${binderNodes.joinToString()}")
        val r5 = runner.run(androidRoot, listOf("/system/bin/sh", "-c", "timeout 4 /system/bin/servicemanager 2>&1; echo EXIT=\$?"),
            extraEnv = guestEnv, timeoutSec = 15)
        r5.output.lines().filter { it.isNotBlank() }.take(20).forEach { log("[servicemanager] $it") }
        val smOut = r5.output.lowercase()
        val diagnosis = when {
            "context manager" in smOut || "set_context_mgr" in smOut -> "cannot become binder context manager (host owns it; SELinux denies apps)"
            "permission denied" in smOut -> "permission denied opening/using binder"
            "exit=124" in smOut -> "servicemanager stayed up 4 s (unexpected! check log — would mean binder works)"
            else -> "see log"
        }
        val smSurvived = "exit=124" in smOut
        report("a5", "A5 guest binder / servicemanager",
            if (smSurvived) StageStatus.OK else StageStatus.FAIL,
            "$diagnosis. Needed: userspace binder shim (LD_PRELOAD /dev/binder → in-app broker), NOT_IMPLEMENTED")

        // A6 — the rest of the boot. Honest placeholder with the concrete blockers.
        report("a6", "A6 zygote → system_server → SurfaceFlinger → launcher", StageStatus.NOT_IMPLEMENTED,
            "blocked on A5 binder shim + a gralloc/HWC that draws into this app's Surface (goldfish-opengl encoders), " +
                "+ property service, + phantom-process limit (Android 12+) for ~30 guest processes")
        return smSurvived
    }

    private fun skipRest(vararg keys: String): Boolean {
        for (k in keys) stages[k]?.let { report(k, it.title, StageStatus.SKIPPED, "previous rung failed") }
        return false
    }

    private fun alpineAssetName(): String? {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return null
        val arch = when (abi) { "arm64-v8a" -> "aarch64"; "x86_64" -> "x86_64"; else -> return null }
        val name = "rootfs/alpine-minirootfs-$arch.tar.gz"
        return try { context.assets.open(name).close(); name } catch (ignored: Throwable) { null }
    }

    override fun setSurface(surface: Surface?) {}
    override fun stop() { running.set(false) }
    override fun sendTouch(action: TouchAction, pointerId: Long, x: Float, y: Float, pressure: Float) {}
    override fun sendKey(action: Int, keyCode: Int, metaState: Int) {}
    override fun sendText(text: String) {}
    override fun stats() = BackendStats(extra = "${stages.values.count { it.status == StageStatus.OK }}/${stages.size} rungs ok")

    companion object {
        private const val TAG = "PiP.OnDevice"
    }
}
