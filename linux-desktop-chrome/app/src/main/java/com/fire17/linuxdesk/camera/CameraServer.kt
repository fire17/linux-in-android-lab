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
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Cameras v1: ONE phone camera (the back main lens) served as MJPEG on loopback.
 *
 *   GET /stream?t=<token>   multipart/x-mixed-replace JPEG stream
 *   GET /info?t=<token>     {"name":"Phone camera","width":W,"height":H}
 *
 * - Binds 127.0.0.1 on a random port; every request must carry the per-session [token] (403 otherwise).
 * - The camera is opened when the first stream client connects and closed shortly after the last one leaves
 *   ([CLOSE_GRACE_MS]), so the privacy dot is only on while a page actually uses the camera.
 * - Camera2 → YUV_420_888 ImageReader → NV21 → YuvImage.compressToJpeg. Default 15 fps, 1280x720 if available.
 *
 * The caller must hold android.permission.CAMERA before [start]; this class never asks for it.
 */
class CameraServer(private val context: Context) {

    companion object {
        private const val TAG = "LinuxDesk.Camera"
        const val CLOSE_GRACE_MS = 3_000L
        private const val TARGET_W = 1280
        private const val TARGET_H = 720
        private const val JPEG_QUALITY = 70
        private const val BOUNDARY = "linuxdeskframe"

        fun newToken(): String {
            val b = ByteArray(16)
            SecureRandom().nextBytes(b)
            return b.joinToString("") { "%02x".format(it) }
        }

        /** Parse "GET /path?t=x HTTP/1.1" → (path, token). Pure; unit-testable. */
        fun parseRequestLine(line: String?): Pair<String, String?>? {
            if (line == null) return null
            val parts = line.trim().split(" ")
            if (parts.size < 2 || parts[0] != "GET") return null
            val target = parts[1]
            val q = target.indexOf('?')
            val path = if (q >= 0) target.substring(0, q) else target
            var token: String? = null
            if (q >= 0) {
                for (kv in target.substring(q + 1).split("&")) {
                    val eq = kv.indexOf('=')
                    if (eq > 0 && kv.substring(0, eq) == "t") token = kv.substring(eq + 1)
                }
            }
            return path to token
        }
    }

    val token: String = newToken()
    @Volatile var port: Int = 0
        private set

    private var server: ServerSocket? = null
    private val clients = AtomicInteger(0)
    private val lock = Object()
    @Volatile private var latestJpeg: ByteArray? = null
    @Volatile private var frameSeq = 0L

    private var camThread: HandlerThread? = null
    private var camHandler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var size: Pair<Int, Int> = TARGET_W to TARGET_H
    private var pendingClose: Runnable? = null

    /** The back camera id from CameraManager, or null if the phone has none. */
    fun backCameraId(): String? {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return cm.cameraIdList.firstOrNull { id ->
            cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        }
    }

    @Synchronized
    fun start(): Int {
        server?.let { return port }
        val ss = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
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
        Log.i(TAG, "MJPEG server on 127.0.0.1:$port")
        return port
    }

    @Synchronized
    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
        camHandler?.post { closeCamera() }
        camThread?.quitSafely()
        camThread = null
        camHandler = null
    }

    private fun handle(s: Socket) {
        s.use { sock ->
            sock.tcpNoDelay = true
            val input = sock.getInputStream().bufferedReader()
            val req = parseRequestLine(input.readLine())
            // drain headers
            while (true) { val h = input.readLine() ?: break; if (h.isEmpty()) break }
            val out = sock.getOutputStream()
            if (req == null) { simple(out, 400, "text/plain", "bad request"); return }
            val (path, tok) = req
            if (tok != token) { simple(out, 403, "text/plain", "forbidden"); return }
            when (path) {
                "/info" -> simple(out, 200, "application/json",
                    """{"name":"Phone camera","width":${size.first},"height":${size.second}}""")
                "/stream" -> stream(out)
                else -> simple(out, 404, "text/plain", "not found")
            }
        }
    }

    private fun simple(out: OutputStream, code: Int, type: String, body: String) {
        val b = body.toByteArray()
        val reason = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; else -> "Not Found" }
        out.write(("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${b.size}\r\n" +
            "Access-Control-Allow-Origin: *\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(b)
        out.flush()
    }

    private fun stream(out: OutputStream) {
        if (clients.incrementAndGet() == 1) camHandler?.post { cancelPendingClose(); openCamera() }
        else camHandler?.post { cancelPendingClose() }
        try {
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\n" +
                "Access-Control-Allow-Origin: *\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
            out.flush()
            var sent = -1L
            while (server != null) {
                val jpeg: ByteArray
                synchronized(lock) {
                    while (frameSeq == sent && server != null) lock.wait(1_000)
                    jpeg = latestJpeg ?: continue
                    sent = frameSeq
                }
                out.write(("--$BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n").toByteArray())
                out.write(jpeg)
                out.write("\r\n".toByteArray())
                out.flush()
            }
        } catch (_: Exception) {
            // client went away
        } finally {
            if (clients.decrementAndGet() == 0) scheduleClose()
        }
    }

    private fun cancelPendingClose() {
        pendingClose?.let { camHandler?.removeCallbacks(it) }
        pendingClose = null
    }

    private fun scheduleClose() {
        val h = camHandler ?: return
        h.post {
            cancelPendingClose()
            val r = Runnable { if (clients.get() == 0) closeCamera() }
            pendingClose = r
            h.postDelayed(r, CLOSE_GRACE_MS)
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (device != null) return
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = backCameraId() ?: run { Log.w(TAG, "no back camera"); return }
        val map = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        val pick = sizes.firstOrNull { it.width == TARGET_W && it.height == TARGET_H }
            ?: sizes.filter { it.width <= TARGET_W }.maxByOrNull { it.width * it.height }
            ?: sizes.firstOrNull()
        if (pick != null) size = pick.width to pick.height
        val r = ImageReader.newInstance(size.first, size.second, ImageFormat.YUV_420_888, 2)
        reader = r
        r.setOnImageAvailableListener({ rd ->
            val img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
            try { publish(toJpeg(img)) } catch (e: Exception) { Log.w(TAG, "jpeg: $e") } finally { img.close() }
        }, camHandler)
        try {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cd: CameraDevice) {
                    if (clients.get() == 0 && server == null) { cd.close(); return }
                    device = cd
                    @Suppress("DEPRECATION")
                    cd.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            session = s
                            val rb = cd.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                            rb.addTarget(r.surface)
                            rb.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(15, 15))
                            try { s.setRepeatingRequest(rb.build(), null, camHandler) } catch (e: Exception) {
                                Log.w(TAG, "repeat: $e")
                            }
                            Log.i(TAG, "camera $id streaming ${size.first}x${size.second}")
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) { Log.w(TAG, "configure failed") }
                    }, camHandler)
                }
                override fun onDisconnected(cd: CameraDevice) { cd.close(); device = null }
                override fun onError(cd: CameraDevice, error: Int) { Log.w(TAG, "camera error $error"); cd.close(); device = null }
            }, camHandler)
        } catch (e: Exception) {
            Log.w(TAG, "openCamera: $e")
        }
    }

    private fun closeCamera() {
        try { session?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        session = null; device = null; reader = null
        synchronized(lock) { latestJpeg = null; lock.notifyAll() }
        Log.i(TAG, "camera closed")
    }

    private fun publish(jpeg: ByteArray) {
        synchronized(lock) {
            latestJpeg = jpeg
            frameSeq++
            lock.notifyAll()
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
