package com.fire17.linuxdesk.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Cameras v3 diagnostic for the Hardware screen: EXACTLY what the phone camera feature will expose (CameraServer's
 * /cams list), plus everything Tami needs to send back his real camera list in one copy-paste.
 *
 * Read-only: getCameraIdList + getCameraCharacteristics only, never openCamera, so it needs no CAMERA permission and
 * works with the switch OFF. Per camera: id, facing, focal length(s), logical parent / physical lenses, max YUV size,
 * max JPEG size, sensor pixel array, and whether the camera feature will list it (and why not).
 */
object CameraDiag {

    data class Entry(
        val id: String,
        val facing: String,
        val focalMm: List<Float>,
        val physicalOf: String?,          // logical parent this lens is opened through (null = top-level id)
        val physicalIds: List<String>,    // lenses inside this logical camera (empty = not logical)
        val maxYuv: Pair<Int, Int>?,
        val maxJpeg: Pair<Int, Int>?,
        val pixelArray: Pair<Int, Int>?,
        val streamSize: Pair<Int, Int>?,  // what CameraServer will stream (CameraServer.pickSize)
        val exposed: Boolean,
        val why: String?,                 // why not exposed
    )

    private fun facing(f: Int?) = CameraServer.facingName(f)

    private fun sizes(ch: CameraCharacteristics, fmt: Int): List<Pair<Int, Int>> = try {
        ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(fmt)?.map { it.width to it.height }
            ?: emptyList()
    } catch (_: Throwable) { emptyList() }

    private fun maxOf(l: List<Pair<Int, Int>>) = l.maxByOrNull { it.first.toLong() * it.second }

    private fun entry(cm: CameraManager, id: String, parent: String?, topIds: List<String>): Entry {
        val ch = try { cm.getCameraCharacteristics(id) } catch (t: Throwable) {
            return Entry(id, "unknown", emptyList(), parent, emptyList(), null, null, null, null, false,
                "characteristics: ${t.javaClass.simpleName}")
        }
        val yuv = sizes(ch, ImageFormat.YUV_420_888)
        val jpeg = sizes(ch, ImageFormat.JPEG)
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val logical = Build.VERSION.SDK_INT >= 28 &&
            caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
        val phys = if (logical && Build.VERSION.SDK_INT >= 28) try { ch.physicalCameraIds.sorted() } catch (_: Throwable) { emptyList() } else emptyList()
        val pa = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.let { it.width to it.height }
        val stream = CameraServer.pickSize(yuv)
        // Same rules as CameraServer.inventory(): top-level ids need YUV; a physical lens is listed if it is not also
        // a top-level id (then it is listed there), using its own YUV sizes or else its parent's.
        val (exposed, why) = when {
            parent != null && id in topIds -> false to "also a top-level camera (listed there)"
            parent == null && stream == null -> false to "no YUV_420_888 output"
            else -> true to null
        }
        return Entry(id, facing(ch.get(CameraCharacteristics.LENS_FACING)),
            ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList() ?: emptyList(),
            parent, phys, maxOf(yuv), maxOf(jpeg), pa, stream, exposed, why)
    }

    /** Every top-level camera, each followed by its physical lenses. */
    fun read(ctx: Context): List<Entry> {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return emptyList()
        val ids = try { cm.cameraIdList.toList() } catch (_: Throwable) { return emptyList() }
        val out = ArrayList<Entry>()
        for (id in ids) {
            val e = entry(cm, id, null, ids)
            out += e
            for (p in e.physicalIds) out += entry(cm, p, id, ids)
        }
        return out
    }

    private fun sz(p: Pair<Int, Int>?) = p?.let { "${it.first}x${it.second}" } ?: "-"

    /** Diag entry → the generic selector's input. Pure; testable. */
    fun toLens(e: Entry) = CameraSelect.Lens(
        id = e.id,
        facing = when (e.facing) { "back" -> CameraSelect.Facing.BACK; "front" -> CameraSelect.Facing.FRONT
            "external" -> CameraSelect.Facing.EXTERNAL; else -> CameraSelect.Facing.UNKNOWN },
        focalMm = e.focalMm.firstOrNull(),
        physicalOf = e.physicalOf,
        physicalIds = e.physicalIds,
        streamable = e.streamSize != null && e.why?.startsWith("characteristics") != true,
    )

    /** The generic choice (CameraSelect) over this device's entries: ordered exposed ids + default. Pure; testable. */
    fun select(list: List<Entry>): CameraSelect.Choice = CameraSelect.choose(list.map { toLens(it) })

    /** One line per camera, monospace-friendly. Pure; testable. */
    fun summary(list: List<Entry>): String = buildString {
        val shown = list.count { it.exposed }
        appendLine("Phone cameras for Linux: $shown of ${list.size} ids (${list.count { it.physicalOf == null }} top-level)")
        for (e in list) {
            val f = if (e.focalMm.isEmpty()) "f=?" else "f=" + e.focalMm.joinToString("/") { String.format(Locale.US, "%.2f", it) } + "mm"
            val kind = when {
                e.physicalOf != null -> "  lens of ${e.physicalOf}"
                e.physicalIds.isNotEmpty() -> "logical[${e.physicalIds.joinToString(",")}]"
                else -> "single"
            }
            append(if (e.physicalOf != null) "   " else " ")
            append("#${e.id} ${e.facing} $f $kind maxYUV=${sz(e.maxYuv)} maxJPEG=${sz(e.maxJpeg)} stream=${sz(e.streamSize)}")
            appendLine(if (e.exposed) " ✓" else " ✗ ${e.why}")
        }
    }

    /** The /cams-style JSON (plus diagnostics) for Copy. Uses org.json, like HwProbe. */
    fun json(list: List<Entry>): JSONObject {
        val arr = JSONArray()
        for (e in list) {
            val o = JSONObject().put("id", e.id).put("facing", e.facing)
                .put("focal_mm", JSONArray(e.focalMm.map { Math.round(it * 100) / 100.0 }))
                .put("physical_of", e.physicalOf ?: JSONObject.NULL)
                .put("physical_ids", JSONArray(e.physicalIds))
                .put("max_yuv", sz(e.maxYuv)).put("max_jpeg", sz(e.maxJpeg)).put("pixel_array", sz(e.pixelArray))
                .put("stream", sz(e.streamSize)).put("exposed", e.exposed)
            if (e.why != null) o.put("why", e.why)
            if (e.exposed) o.put("label", CameraServer.labelFor(
                when (e.facing) { "back" -> CameraCharacteristics.LENS_FACING_BACK; "front" -> CameraCharacteristics.LENS_FACING_FRONT
                    "external" -> CameraCharacteristics.LENS_FACING_EXTERNAL; else -> null },
                e.focalMm.firstOrNull(), e.id, e.physicalOf))
            arr.put(o)
        }
        val sel = select(list)
        return JSONObject().put("cameras", arr).put("exposed", list.count { it.exposed }).put("ids", list.size)
            .put("default", sel.default?.id ?: JSONObject.NULL)
            .put("order", JSONArray(sel.ordered.map { it.id }))
            .put("hidden", JSONObject(sel.hidden as Map<*, *>))
    }
}
