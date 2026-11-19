package com.fire17.linuxdesk.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Cameras v2: EVERY phone camera, served as MJPEG on loopback.
 *
 *   GET /cams?t=<token>               JSON inventory: [{"id","label","facing","focalMm","physicalOf"}]
 *   GET /stream?t=<token>[&cam=<id>]  multipart/x-mixed-replace JPEG stream of that camera (default: first back one)
 *   GET /info?t=<token>[&cam=<id>]    {"name","width","height"} (v1 compatible)
 *   OPTIONS *                         CORS / Private-Network-Access preflight (no data, no token needed)
 *
 * Inventory ([inventory]) = every id in CameraManager.cameraIdList, plus (API 28+) each physical lens of a logical
 * multi-camera that is not itself in the list and reports YUV sizes. A physical lens is opened through its logical
 * parent with OutputConfiguration.setPhysicalCameraId.
 *
 * - Binds 127.0.0.1 on [preferredPort] if free (so a page's port survives switch OFF→ON), else a random port.
 *   Every GET must carry [token] (403 otherwise).
 * - Each camera opens when its first stream client connects and closes [CLOSE_GRACE_MS] after its last one leaves.
 *   Opening a camera first closes any OTHER camera that has no clients (phones cap concurrently open cameras).
 * - Camera2 → YUV_420_888 ImageReader → NV21 → YuvImage.compressToJpeg. 15 fps, ≤1280x720.
 * - Every response carries Access-Control-Allow-Origin: * and Access-Control-Allow-Private-Network: true.
 *
 * The caller must hold android.permission.CAMERA before [start]; this class never asks for it.
 */
class CameraServer(
    private val context: Context,
    val token: String = newToken(),
    private val preferredPort: Int = 0,
) {

    companion object {
        private const val TAG = "LinuxDesk.Camera"
        const val CLOSE_GRACE_MS = 3_000L
        private const val TARGET_W = 1280
        private const val TARGET_H = 720
        private const val JPEG_QUALITY = 70
        private const val BOUNDARY = "linuxdeskframe"
        private const val CORS = "Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Private-Network: true\r\n"

        fun newToken(): String {
            val b = ByteArray(16)
            SecureRandom().nextBytes(b)
            return b.joinToString("") { "%02x".format(it) }
        }

        data class Request(val method: String, val path: String, val params: Map<String, String>) {
            val token: String? get() = params["t"]
        }

        /** Parse "GET /path?a=b HTTP/1.1". Pure; unit-testable. Null for garbage. */
        fun parseRequest(line: String?): Request? {
            if (line == null) return null
            val parts = line.trim().split(" ")
            if (parts.size < 2 || parts[0].isEmpty()) return null
            val target = parts[1]
            val q = target.indexOf('?')
            val path = if (q >= 0) target.substring(0, q) else target
            val params = LinkedHashMap<String, String>()
            if (q >= 0) {
                for (kv in target.substring(q + 1).split("&")) {
                    val eq = kv.indexOf('=')
                    if (eq > 0) params[kv.substring(0, eq)] = java.net.URLDecoder.decode(kv.substring(eq + 1), "UTF-8")
                }
            }
            return Request(parts[0], path, params)
        }

        /** v1 helper: GET only → (path, token). */
        fun parseRequestLine(line: String?): Pair<String, String?>? =
            parseRequest(line)?.takeIf { it.method == "GET" }?.let { it.path to it.token }

        fun facingName(facing: Int?): String = when (facing) {
            CameraCharacteristics.LENS_FACING_BACK -> "back"
            CameraCharacteristics.LENS_FACING_FRONT -> "front"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
            else -> "unknown"
        }

        /** "Phone back 6.3 mm (camera 0)" / "Phone back 2.2 mm (lens 3 of 0)". Unique per id. Pure; testable. */
        fun labelFor(facing: Int?, focalMm: Float?, id: String, physicalOf: String?): String {
            val f = focalMm?.let { " " + String.format(Locale.US, "%.1f", it) + " mm" } ?: ""
            val where = if (physicalOf != null) "lens $id of $physicalOf" else "camera $id"
            return "Phone ${facingName(facing)}$f ($where)"
        }

        /** Pick the stream size: exact 1280x720, else the largest ≤1280 wide, else the first. Pure; testable. */
        fun pickSize(sizes: List<Pair<Int, Int>>): Pair<Int, Int>? =
            sizes.firstOrNull { it.first == TARGET_W && it.second == TARGET_H }
                ?: sizes.filter { it.first <= TARGET_W }.maxByOrNull { it.first.toLong() * it.second }
                ?: sizes.firstOrNull()

        private fun jsonStr(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        /** [Cam]s → CameraSelect → the same cams, default first, then the generic order. Pure; testable. */
        fun orderCams(found: List<Cam>): List<Cam> {
            val parents = found.filter { it.physicalOf != null }.groupBy { it.physicalOf!! }
            val lenses = found.map { c ->
                CameraSelect.Lens(c.id, when (c.facing) {
                    CameraCharacteristics.LENS_FACING_BACK -> CameraSelect.Facing.BACK
                    CameraCharacteristics.LENS_FACING_FRONT -> CameraSelect.Facing.FRONT
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> CameraSelect.Facing.EXTERNAL
                    else -> CameraSelect.Facing.UNKNOWN
                }, c.focalMm, c.physicalOf, parents[c.id]?.map { it.id } ?: emptyList())
            }
            val byKey = found.associateBy { it.id + "@" + (it.physicalOf ?: "") }
            return CameraSelect.choose(lenses).ordered.mapNotNull { byKey[it.id + "@" + (it.physicalOf ?: "")] }
        }
    }

    /** One exposed camera. [physicalOf] = the logical camera it is opened through (null = open [id] directly). */
    data class Cam(
        val id: String,
        val facing: Int?,
        val focalMm: Float?,
        val physicalOf: String?,
        val size: Pair<Int, Int>,
    ) {
        val label: String get() = labelFor(facing, focalMm, id, physicalOf)
        fun json(): String = "{\"id\":${jsonStr(id)},\"label\":${jsonStr(label)},\"facing\":\"${facingName(facing)}\"," +
            "\"focalMm\":${focalMm ?: "null"},\"physicalOf\":${physicalOf?.let { jsonStr(it) } ?: "null"}," +
            "\"width\":${size.first},\"height\":${size.second}}"
    }

    @Volatile var port: Int = 0
        private set

    private var server: ServerSocket? = null
    private var camThread: HandlerThread? = null
    private var camHandler: Handler? = null
    private val streams = HashMap<String, CamStream>()   // touched only on camHandler, except lookups under lock
    @Volatile var cams: List<Cam> = emptyList()
        private set

    /** Every camera this phone can stream, back ones first. */
    fun inventory(): List<Cam> {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = try { cm.cameraIdList.toList() } catch (e: Exception) { Log.w(TAG, "cameraIdList: $e"); CameraLog.error("-", "list", e); emptyList() }
        val out = ArrayList<Cam>()
        for (id in ids) {
            val ch = try { cm.getCameraCharacteristics(id) } catch (_: Exception) { continue }
            val size = yuvSize(ch) ?: continue
            out += Cam(id, ch.get(CameraCharacteristics.LENS_FACING), focal(ch), null, size)
            if (Build.VERSION.SDK_INT >= 28 && isLogicalMulti(ch)) {
                for (pid in ch.physicalCameraIds) {
                    if (pid in ids || out.any { it.id == pid }) continue
                    val pch = try { cm.getCameraCharacteristics(pid) } catch (_: Exception) { null }
                    val psize = pch?.let { yuvSize(it) } ?: size
                    out += Cam(pid, pch?.get(CameraCharacteristics.LENS_FACING) ?: ch.get(CameraCharacteristics.LENS_FACING),
                        pch?.let { focal(it) }, id, psize)
                }
            }
        }
        return order(out)
    }

    /** v4: order + default from the generic rules (CameraSelect), default first. Pure over [Cam]s; testable. */
    fun order(found: List<Cam>): List<Cam> = orderCams(found)

    /** v1 compatibility: the first back camera id, or null. */
    fun backCameraId(): String? = inventory().firstOrNull { it.facing == CameraCharacteristics.LENS_FACING_BACK }?.id

    private fun isLogicalMulti(ch: CameraCharacteristics): Boolean {
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return false
        return Build.VERSION.SDK_INT >= 28 &&
            caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
    }

    private fun focal(ch: CameraCharacteristics): Float? =
        ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()

    private fun yuvSize(ch: CameraCharacteristics): Pair<Int, Int>? {
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val sizes: Array<Size> = map.getOutputSizes(ImageFormat.YUV_420_888) ?: return null
        return pickSize(sizes.map { it.width to it.height })
    }

    @Synchronized
    fun start(): Int {
        server?.let { return port }
        cams = inventory()
        val lo = InetAddress.getByName("127.0.0.1")
        val ss = (if (preferredPort > 0) try { ServerSocket(preferredPort, 8, lo) } catch (_: Exception) { null } else null)
            ?: ServerSocket(0, 8, lo)
        server = ss
        port = ss.localPort
        camThread = HandlerThread("linuxdesk-cam").also { it.start() }
        camHandler = Handler(camThread!!.looper)
        thread(name = "linuxdesk-cam-accept", isDaemon = true) {
            while (!ss.isClosed) {
                val s = try { ss.accept() } catch (_: SocketException) { break }
                thread(name = "linuxdesk-cam-client", isDaemon = true) { handle(s) }
            }
        }
        Log.i(TAG, "MJPEG server on 127.0.0.1:$port, ${cams.size} cameras: ${cams.joinToString { it.id }}")
        return port
    }

    @Synchronized
    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
        val h = camHandler
        h?.post { synchronized(streams) { streams.values.forEach { it.close() }; streams.clear() } }
        camThread?.quitSafely()
        camThread = null
        camHandler = null
    }

    private val running: Boolean get() = server != null

    private fun handle(s: Socket) {
        s.use { sock ->
            sock.tcpNoDelay = true
            val input = sock.getInputStream().bufferedReader()
            val req = parseRequest(input.readLine())
            while (true) { val h = input.readLine() ?: break; if (h.isEmpty()) break }
            val out = sock.getOutputStream()
            if (req == null) { simple(out, 400, "text/plain", "bad request"); return }
            if (req.method == "OPTIONS") { preflight(out); return }
            if (req.method != "GET") { simple(out, 400, "text/plain", "bad request"); return }
            if (req.token != token) { simple(out, 403, "text/plain", "forbidden"); return }
            val cam = req.params["cam"]?.let { id -> cams.firstOrNull { it.id == id } } ?: if (req.params["cam"] == null) cams.firstOrNull() else null
            when (req.path) {
                "/cams" -> simple(out, 200, "application/json", cams.joinToString(",", "[", "]") { it.json() })
                "/info" -> if (cam == null) simple(out, 404, "text/plain", "no such camera") else
                    simple(out, 200, "application/json",
                        "{\"name\":${jsonStr(cam.label)},\"width\":${cam.size.first},\"height\":${cam.size.second}}")
                "/stream" -> if (cam == null) simple(out, 404, "text/plain", "no such camera") else stream(out, cam)
                else -> simple(out, 404, "text/plain", "not found")
            }
        }
    }

    private fun preflight(out: OutputStream) {
        out.write(("HTTP/1.1 204 No Content\r\n$CORS" +
            "Access-Control-Allow-Methods: GET\r\nAccess-Control-Allow-Headers: *\r\nAccess-Control-Max-Age: 600\r\n" +
            "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray())
        out.flush()
    }

    private fun simple(out: OutputStream, code: Int, type: String, body: String) {
        val b = body.toByteArray()
        val reason = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; else -> "Not Found" }
        out.write(("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${b.size}\r\n$CORS" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(b)
        out.flush()
    }

    private fun streamFor(cam: Cam): CamStream = synchronized(streams) { streams.getOrPut(cam.id) { CamStream(cam) } }

    private fun stream(out: OutputStream, cam: Cam) {
        val st = streamFor(cam)
        st.attach()
        try {
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\n$CORS" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
            out.flush()
            var sent = -1L
            while (running && !st.failed) {
                val jpeg = st.next(sent) ?: continue
                sent = jpeg.first
                out.write(("--$BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.second.size}\r\n\r\n").toByteArray())
                out.write(jpeg.second)
                out.write("\r\n".toByteArray())
                out.flush()
            }
        } catch (_: Exception) {
            // client went away
        } finally {
            st.detach()
        }
    }

    /** One camera's capture pipeline and its viewers. Camera calls run on camHandler. */
    private inner class CamStream(val cam: Cam) {
        private val clients = AtomicInteger(0)
        private val lock = Object()
        private var latest: ByteArray? = null
        private var seq = 0L
        @Volatile var failed = false
        private var device: CameraDevice? = null
        private var session: CameraCaptureSession? = null
        private var reader: ImageReader? = null
        private var pendingClose: Runnable? = null
        private val executor = Executor { r -> camHandler?.post(r) }

        val idle: Boolean get() = clients.get() == 0

        fun attach() {
            failed = false
            val first = clients.incrementAndGet() == 1
            camHandler?.post { cancelPendingClose(); if (first || device == null) { closeOtherIdle(); open() } }
        }

        fun detach() {
            if (clients.decrementAndGet() > 0) return
            val h = camHandler ?: return
            h.post {
                cancelPendingClose()
                val r = Runnable { if (idle) close() }
                pendingClose = r
                h.postDelayed(r, CLOSE_GRACE_MS)
            }
        }

        /** Next frame newer than [after], or null after a 1 s wait. */
        fun next(after: Long): Pair<Long, ByteArray>? = synchronized(lock) {
            if (seq == after && running && !failed) lock.wait(1_000)
            val j = latest ?: return null
            if (seq == after) null else seq to j
        }

        private fun publish(jpeg: ByteArray) = synchronized(lock) { latest = jpeg; seq++; lock.notifyAll() }

        private fun fail(stage: String, e: Throwable? = null, detail: String? = null) {
            Log.w(TAG, "camera ${cam.id}: $stage ${e ?: detail ?: ""}")
            CameraLog.error(cam.id, stage, e, detail)   // → sys.logs, so the lead's pulls see it
            failed = true
            close()
        }

        /** JPEG failures can repeat every frame: log the first one per open, not 15 a second. */
        @Volatile private var jpegLogged = false
        private fun jpegError(e: Exception) { if (!jpegLogged) { jpegLogged = true; CameraLog.error(cam.id, "jpeg", e) } }

        private fun cancelPendingClose() {
            pendingClose?.let { camHandler?.removeCallbacks(it) }
            pendingClose = null
        }

        private fun closeOtherIdle() {
            val others = synchronized(streams) { streams.values.filter { it !== this && it.idle } }
            others.forEach { it.close() }
        }

        @SuppressLint("MissingPermission")
        private fun open() {
            if (device != null) return
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val (w, h) = cam.size
            val r = ImageReader.newInstance(w, h, ImageFormat.YUV_420_888, 2)
            reader = r
            r.setOnImageAvailableListener({ rd ->
                val img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
                try { publish(toJpeg(img)) } catch (e: Exception) { Log.w(TAG, "jpeg: $e"); jpegError(e) } finally { img.close() }
            }, camHandler)
            val openId = cam.physicalOf ?: cam.id
            try {
                cm.openCamera(openId, object : CameraDevice.StateCallback() {
                    override fun onOpened(cd: CameraDevice) {
                        if (idle || !running) { cd.close(); return }
                        device = cd
                        configure(cd, r)
                    }
                    override fun onDisconnected(cd: CameraDevice) { cd.close(); device = null; fail("disconnected") }
                    override fun onError(cd: CameraDevice, error: Int) { cd.close(); device = null; fail("device_error", detail = "CameraDevice error $error") }
                }, camHandler)
            } catch (e: Exception) {
                fail("open", e)
            }
        }

        private fun configure(cd: CameraDevice, r: ImageReader) {
            val cb = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    try {
                        val rb = cd.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        rb.addTarget(r.surface)
                        rb.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(15, 15))
                        s.setRepeatingRequest(rb.build(), null, camHandler)
                        Log.i(TAG, "camera ${cam.id} streaming ${cam.size.first}x${cam.size.second}")
                    } catch (e: Exception) { fail("repeat", e) }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { fail("configure", detail = "onConfigureFailed") }
            }
            try {
                if (Build.VERSION.SDK_INT >= 28) {
                    val oc = OutputConfiguration(r.surface)
                    if (cam.physicalOf != null) oc.setPhysicalCameraId(cam.id)
                    cd.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(oc), executor, cb))
                } else {
                    @Suppress("DEPRECATION")
                    cd.createCaptureSession(listOf(r.surface), cb, camHandler)
                }
            } catch (e: Exception) {
                fail("session", e)
            }
        }

        fun close() {
            cancelPendingClose()
            try { session?.close() } catch (_: Exception) {}
            try { device?.close() } catch (_: Exception) {}
            try { reader?.close() } catch (_: Exception) {}
            session = null; device = null; reader = null
            synchronized(lock) { latest = null; lock.notifyAll() }
            Log.i(TAG, "camera ${cam.id} closed")
        }
    }

    /** YUV_420_888 → NV21 (handles row/pixel stride) → JPEG. */
    private fun toJpeg(img: Image): ByteArray {
        val w = img.width; val h = img.height
        val nv21 = ByteArray(w * h * 3 / 2)
        val y = img.planes[0]; val u = img.planes[1]; val v = img.planes[2]
        val yb = y.buffer; var pos = 0
        for (row in 0 until h) {
            yb.position(row * y.rowStride)
            yb.get(nv21, pos, w)
            pos += w
        }
        val ub = u.buffer; val vb = v.buffer
        val ps = u.pixelStride; val rs = u.rowStride
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                val i = row * rs + col * ps
                nv21[pos++] = vb.get(i)
                nv21[pos++] = ub.get(i)
            }
        }
        val bos = ByteArrayOutputStream(w * h / 4)
        YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), JPEG_QUALITY, bos)
        return bos.toByteArray()
    }
}
