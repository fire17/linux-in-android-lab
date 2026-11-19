package com.fire17.linuxdesk.hw

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cameras plan phase 0 (notes/cameras-plan.md): the camera INVENTORY, read-only.
 *
 * Never opens a camera (no openCamera, no session, no ImageReader) and needs NO CAMERA permission:
 * CameraManager.getCameraIdList() and getCameraCharacteristics() are callable without it. Since API 29 a few
 * keys come back null without the permission; CameraCharacteristics.getKeysNeedingPermission() names them.
 * We check every key we read against that list on the device itself and report it under `needs_permission`
 * (it is skipped, never requested). Per the docs that list is lens intrinsics/pose/distortion and sensor
 * calibration keys, none of which we read, so `needs_permission` is expected to be empty (UNVERIFIED on the Fold6).
 */
object CameraProbe {

    private val READ_KEYS: List<CameraCharacteristics.Key<*>> = listOf(
        CameraCharacteristics.LENS_FACING,
        CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS,
        CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE,
        CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE,
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES,
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL,
        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP,
    )

    fun inventory(ctx: Context): JSONObject {
        val cm = ctx.getSystemService(CameraManager::class.java) ?: return JSONObject().put("error", "no_camera_service")
        val ids = cm.cameraIdList
        val out = JSONArray()
        val gated = sortedSetOf<String>()
        for (id in ids) out.put(describe(cm, id, gated, withPhysical = true))
        val o = JSONObject().put("count", ids.size).put("cameras", out)
            .put("opened_any", false)   // by construction: this file has no openCamera call
            .put("needs_permission", JSONArray(gated.toList()))
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                o.put("concurrent_sets", JSONArray(cm.concurrentCameraIds.map { JSONArray(it.sorted()) }))
            } catch (t: Throwable) { o.put("concurrent_sets_error", t.javaClass.simpleName) }
        }
        return o
    }

    private fun describe(cm: CameraManager, id: String, gated: MutableSet<String>, withPhysical: Boolean): JSONObject {
        val o = JSONObject().put("id", id)
        val ch = try { cm.getCameraCharacteristics(id) } catch (t: Throwable) {
            return o.put("error", t.javaClass.simpleName)
        }
        // Which of OUR keys would need the permission here? Those are skipped (we never ask for CAMERA).
        val needing: Set<String> = if (Build.VERSION.SDK_INT >= 29) {
            try { ch.keysNeedingPermission.map { it.name }.toSet() } catch (_: Throwable) { emptySet() }
        } else emptySet()
        val skip = READ_KEYS.map { it.name }.filter { it in needing }.toSet()
        gated.addAll(skip)
        fun <T> read(k: CameraCharacteristics.Key<T>): T? =
            if (k.name in skip) null else try { ch.get(k) } catch (_: Throwable) { null }

        o.put("facing", when (read(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_BACK -> "back"
            CameraCharacteristics.LENS_FACING_FRONT -> "front"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
            else -> "unknown"
        })
        read(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.let { f ->
            o.put("focal_mm", JSONArray(f.map { round2(it.toDouble()) }))
        }
        read(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.let {
            o.put("sensor_mm", JSONObject().put("w", round2(it.width.toDouble())).put("h", round2(it.height.toDouble())))
        }
        read(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.let {
            o.put("pixel_array", "${it.width}x${it.height}").put("megapixels", round2(it.width.toLong() * it.height / 1e6))
        }
        o.put("hw_level", when (read(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "legacy"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "limited"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "full"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "level_3"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "external"
            null -> "unknown"
            else -> "other"
        })
        val caps = read(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val logical = Build.VERSION.SDK_INT >= 28 &&
            caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
        o.put("logical_multi_camera", logical)
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let {
                    o.put("zoom", JSONArray().put(round2(it.lower.toDouble())).put(round2(it.upper.toDouble())))
                }
            } catch (_: Throwable) {}
        }
        if (logical && Build.VERSION.SDK_INT >= 28) {
            val phys = try { ch.physicalCameraIds.sorted() } catch (_: Throwable) { emptyList() }
            o.put("physical_ids", JSONArray(phys))
            if (withPhysical && phys.isNotEmpty()) {
                o.put("physical", JSONArray(phys.map { describe(cm, it, gated, withPhysical = false) }))
            }
        }
        read(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.let { map ->
            val sizes = try { map.getOutputSizes(ImageFormat.YUV_420_888) } catch (_: Throwable) { null }
            if (sizes != null) {
                o.put("yuv_top3", JSONArray(sizes.sortedByDescending { it.width.toLong() * it.height }
                    .take(3).map { "${it.width}x${it.height}" }))
            }
        }
        return o
    }

    private fun round2(d: Double) = Math.round(d * 100) / 100.0
}
