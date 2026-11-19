package com.fire17.linuxdesk.hw

import android.content.Context
import android.content.pm.PackageManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream

/**
 * GPU/NPU plan v1 (notes/gpu-npu-plan.md §1): read-only hardware DETECTION. One JSON object, no permissions,
 * no identifiers (no serial, IMEI, Android ID, Build.FINGERPRINT/DISPLAY/ID, nothing per-unit).
 *
 * Everything under `npu` is a HINT, not proof: the real proof is a working accelerator session (v3).
 * `/dev` nodes are opened read-only and closed at once; nothing is ioctl'd or mapped.
 *
 * Cheap but not free (the EGL probe creates and destroys a 1x1 pbuffer context): call [get] off the main
 * thread. The result is cached for the process lifetime (hardware does not change; `refresh = true` re-reads).
 */
object HwProbe {

    @Volatile private var cached: JSONObject? = null

    fun get(ctx: Context, refresh: Boolean = false): JSONObject {
        if (!refresh) cached?.let { return JSONObject(it.toString()) }
        val o = JSONObject().put("v", 1)
        o.put("device", safe { device() })
        o.put("soc", safe { soc() })
        o.put("gpu", safe { gpu() })
        o.put("vulkan", safe { vulkan(ctx) })
        o.put("npu", safe { npu() })
        o.put("cameras", safe { CameraProbe.inventory(ctx) })
        cached = o
        return JSONObject(o.toString())
    }

    /**
     * Non-blocking form for callers on a reader/UI thread (Router's sys.caps): the cached JSON if a probe already
     * ran, else starts one in the background and returns {"pending": true} (the next sys.caps carries it).
     */
    fun cachedOrWarm(ctx: Context): JSONObject {
        cached?.let { return JSONObject(it.toString()) }
        val app = ctx.applicationContext
        if (warming.compareAndSet(false, true)) {
            Thread({ try { get(app) } catch (_: Throwable) {} finally { warming.set(false) } }, "hw-probe").start()
        }
        return JSONObject().put("pending", true)
    }

    private val warming = java.util.concurrent.atomic.AtomicBoolean(false)

    private inline fun safe(f: () -> Any): Any = try { f() } catch (t: Throwable) {
        JSONObject().put("error", t.javaClass.simpleName)
    }

    // -------------------------------------------------------------------------------------------- device

    private fun device(): JSONObject = JSONObject()
        .put("manufacturer", Build.MANUFACTURER)
        .put("brand", Build.BRAND)
        .put("model", Build.MODEL)
        .put("device", Build.DEVICE)
        .put("hardware", Build.HARDWARE)
        .put("board", Build.BOARD)
        .put("android", Build.VERSION.RELEASE)
        .put("sdk", Build.VERSION.SDK_INT)
        .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))

    // ----------------------------------------------------------------------------------------------- SoC

    private fun soc(): JSONObject {
        val o = JSONObject()
        if (Build.VERSION.SDK_INT >= 31) {
            o.put("manufacturer", Build.SOC_MANUFACTURER).put("model", Build.SOC_MODEL).put("source", "Build.SOC_*")
        } else {
            o.put("manufacturer", "").put("model", "").put("source", "unavailable below API 31")
        }
        val fam = socFamily(o.optString("manufacturer"), o.optString("model"), Build.HARDWARE, Build.BOARD)
        o.put("family", fam.family).put("name_hint", fam.name)
        return o
    }

    data class Family(val family: String, val name: String, val npu: String, val htp: String = "")

    /**
     * Pure mapping (unit-testable): SoC strings → family + marketing-name hint + NPU hint.
     * Tables from notes/gpu-npu-plan.md §0/§1.2; unknown → "unknown", never a guess.
     */
    fun socFamily(socMfr: String, socModel: String, hardware: String = "", board: String = ""): Family {
        val m = socMfr.lowercase()
        val model = socModel.uppercase()
        val hw = (hardware + " " + board).lowercase()
        val qcom = m.contains("qti") || m.contains("qualcomm") || model.startsWith("SM") || model.startsWith("SDM") ||
            hw.contains("qcom")
        if (qcom) {
            val names = mapOf(
                "SM8350" to ("Snapdragon 888" to "V68"), "SM8450" to ("Snapdragon 8 Gen 1" to "V69"),
                "SM8475" to ("Snapdragon 8+ Gen 1" to "V69"), "SM8550" to ("Snapdragon 8 Gen 2" to "V73"),
                "SM8650" to ("Snapdragon 8 Gen 3" to "V75"), "SM8750" to ("Snapdragon 8 Elite" to "V79"),
            )
            val key = names.keys.firstOrNull { model.startsWith(it) }
            val (name, htp) = key?.let { names.getValue(it) } ?: ("" to "")
            val flagship = Regex("^SM8[3-9][0-9]0").containsMatchIn(model) || key != null
            return Family("qualcomm", name, if (flagship) "hexagon" else "hexagon?", htp)
        }
        if (m.contains("google") || model.contains("TENSOR") || hw.contains("gs101") || hw.contains("gs201") ||
            hw.contains("zuma")) return Family("google_tensor", socModel, "tpu")
        if (m.contains("mediatek") || model.startsWith("MT") || hw.startsWith("mt")) {
            return Family("mediatek", socModel, if (model.startsWith("MT69")) "apu" else "apu?")
        }
        if (m.contains("samsung") || model.contains("EXYNOS") || model.startsWith("S5E") || hw.contains("exynos")) {
            return Family("samsung_exynos", socModel, "npu")
        }
        return Family("unknown", "", "none_known")
    }

    // ----------------------------------------------------------------------------------------------- GPU

    private fun gpu(): JSONObject {
        val o = JSONObject()
        val gl = glStrings()
        o.put("gl", gl)
        val renderer = gl.optString("renderer")
        o.put("angle", renderer.startsWith("ANGLE"))
        o.put("vendor_family", gpuFamily(gl.optString("vendor"), renderer))
        o.put("nodes", JSONObject()
            .put("/dev/kgsl-3d0", node("/dev/kgsl-3d0"))
            .put("/dev/mali0", node("/dev/mali0"))
            .put("/dev/dri/renderD128", node("/dev/dri/renderD128")))
        return o
    }

    fun gpuFamily(vendor: String, renderer: String): String {
        val s = (vendor + " " + renderer).lowercase()
        return when {
            s.contains("adreno") || s.contains("qualcomm") -> "adreno"
            s.contains("mali") || s.contains("immortalis") -> "mali"
            s.contains("xclipse") -> "xclipse"
            s.contains("powervr") || s.contains("imagination") -> "powervr"
            s.contains("swiftshader") || s.contains("llvmpipe") || s.contains("android emulator") -> "software"
            s.isBlank() -> "unknown"
            else -> "other"
        }
    }

    /** exists / readable (the File bit) / opened read-only for real (then closed; nothing else done). */
    private fun node(path: String): JSONObject {
        val f = File(path)
        val o = JSONObject().put("exists", f.exists())
        if (!f.exists()) return o
        o.put("can_read", f.canRead()).put("can_write", f.canWrite())
        val opened = try { FileInputStream(f).close(); true } catch (_: Throwable) { false }
        return o.put("open_ro", opened)
    }

    /** Throwaway EGL14 1x1 pbuffer + GLES2 glGetString. Every step checked; always torn down. */
    private fun glStrings(): JSONObject {
        val o = JSONObject()
        val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (dpy == EGL14.EGL_NO_DISPLAY) return o.put("error", "no_display")
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(dpy, ver, 0, ver, 1)) return o.put("error", "egl_init")
        var ctx = EGL14.EGL_NO_CONTEXT
        var surf = EGL14.EGL_NO_SURFACE
        try {
            o.put("egl", "${ver[0]}.${ver[1]}")
            val attrs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE)
            val cfgs = arrayOfNulls<EGLConfig>(1)
            val n = IntArray(1)
            if (!EGL14.eglChooseConfig(dpy, attrs, 0, cfgs, 0, 1, n, 0) || n[0] < 1) return o.put("error", "no_config")
            ctx = EGL14.eglCreateContext(dpy, cfgs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            if (ctx == EGL14.EGL_NO_CONTEXT) return o.put("error", "no_context")
            surf = EGL14.eglCreatePbufferSurface(dpy, cfgs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            if (surf == EGL14.EGL_NO_SURFACE) return o.put("error", "no_surface")
            if (!EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) return o.put("error", "make_current")
            o.put("vendor", GLES20.glGetString(GLES20.GL_VENDOR) ?: "")
            o.put("renderer", GLES20.glGetString(GLES20.GL_RENDERER) ?: "")
            o.put("version", GLES20.glGetString(GLES20.GL_VERSION) ?: "")
            o.put("glsl", GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION) ?: "")
            return o
        } catch (t: Throwable) {
            return o.put("error", t.javaClass.simpleName)
        } finally {
            try { EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) } catch (_: Throwable) {}
            if (surf != EGL14.EGL_NO_SURFACE) try { EGL14.eglDestroySurface(dpy, surf) } catch (_: Throwable) {}
            if (ctx != EGL14.EGL_NO_CONTEXT) try { EGL14.eglDestroyContext(dpy, ctx) } catch (_: Throwable) {}
            // No eglTerminate: the default display is process-wide and the X/lorie side may share it.
        }
    }

    // -------------------------------------------------------------------------------------------- Vulkan

    private fun vulkan(ctx: Context): JSONObject {
        val pm = ctx.packageManager
        val o = JSONObject()
        val fv = pm.systemAvailableFeatures.firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }
        val fl = pm.systemAvailableFeatures.firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL }
        o.put("present", fv != null)
        if (fv != null) {
            val v = fv.version
            o.put("api", "${v ushr 22}.${(v ushr 12) and 0x3ff}.${v and 0xfff}").put("raw", v)
        }
        if (fl != null) o.put("level", fl.version)
        o.put("vk_1_1", pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, 0x401000))
        o.put("vk_1_3", pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, 0x403000))
        return o
    }

    // ----------------------------------------------------------------------------------------------- NPU

    private fun npu(): JSONObject {
        val socMfr = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else ""
        val socModel = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else ""
        val fam = socFamily(socMfr, socModel, Build.HARDWARE, Build.BOARD)
        val o = JSONObject().put("hint_only", true)
            .put("family_hint", fam.npu)
            .put("note", "hints, not proof: proof is a working accelerator session (plan v3)")
        if (fam.htp.isNotEmpty()) o.put("hexagon_htp", fam.htp)
        // dlopen-free: only "is the vendor lib there and readable". Never loaded.
        val libs = JSONObject()
        for (p in listOf("/vendor/lib64/libcdsprpc.so", "/vendor/lib64/libQnnHtp.so", "/vendor/lib64/libneuron_adapter.so",
            "/vendor/lib64/libedgetpu_util.so")) {
            val f = File(p)
            libs.put(p, JSONObject().put("exists", f.exists()).put("readable", f.exists() && f.canRead()))
        }
        o.put("libs", libs)
        o.put("android_npu_feature_api37", false)   // PackageManager.FEATURE_NEURAL_PROCESSING_UNIT is API 37+
        return o
    }

    // ------------------------------------------------------------------------------------------- summary

    /** Readable multi-line summary for the Hardware card. Pure JSON → text (unit-testable). */
    fun summary(j: JSONObject): String = buildString {
        val d = j.optJSONObject("device") ?: JSONObject()
        val s = j.optJSONObject("soc") ?: JSONObject()
        val g = j.optJSONObject("gpu") ?: JSONObject()
        val gl = g.optJSONObject("gl") ?: JSONObject()
        val vk = j.optJSONObject("vulkan") ?: JSONObject()
        val n = j.optJSONObject("npu") ?: JSONObject()
        appendLine("${d.optString("manufacturer")} ${d.optString("model")} · Android ${d.optString("android")} (API ${d.optInt("sdk")})")
        appendLine("ABI: " + (d.optJSONArray("abis")?.let { a -> (0 until a.length()).joinToString { a.optString(it) } } ?: "?"))
        val socName = s.optString("name_hint").ifEmpty { s.optString("family") }
        appendLine("SoC: ${s.optString("manufacturer")} ${s.optString("model")}" + if (socName.isNotEmpty()) " ($socName)" else "")
        val r = gl.optString("renderer")
        appendLine("GPU: " + if (r.isNotEmpty()) "$r · ${gl.optString("version")}" else "? (${gl.optString("error", "no GL")})")
        val nodes = g.optJSONObject("nodes") ?: JSONObject()
        val open = nodes.keys().asSequence().filter { nodes.optJSONObject(it)?.optBoolean("open_ro") == true }.toList()
        appendLine("GPU node: " + if (open.isEmpty()) "none openable" else open.joinToString())
        appendLine("Vulkan: " + if (vk.optBoolean("present")) vk.optString("api") else "no")
        val htp = n.optString("hexagon_htp")
        appendLine("NPU (hint): ${n.optString("family_hint")}" + if (htp.isNotEmpty()) " HTP $htp" else "")
        val cams = j.optJSONObject("cameras")
        if (cams != null) {
            val arr = cams.optJSONArray("cameras") ?: JSONArray()
            appendLine("Cameras: ${arr.length()}")
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val phys = c.optJSONArray("physical_ids")?.length() ?: 0
                val mp = c.optDouble("megapixels", 0.0)
                appendLine("  #${c.optString("id")} ${c.optString("facing")}" +
                    (if (mp > 0) " ${"%.1f".format(java.util.Locale.US, mp)}MP" else "") +
                    (c.optJSONArray("focal_mm")?.let { if (it.length() > 0) " f=${it.optDouble(0)}mm" else "" } ?: "") +
                    (if (phys > 0) " logical($phys)" else ""))
            }
        }
    }
}
