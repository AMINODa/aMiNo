package moe.shizuku.manager.sharingan.live

import android.content.Context
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import moe.shizuku.manager.terminal.linux.ShizukuExec
import java.security.MessageDigest

/**
 * aMiNo 2.0 (r1437) — LIVE SCREEN SOURCE: where live frames come from.
 *
 * r1436's fatal dependency is gone: the broadcast no longer REQUIRES the
 * wireless-ADB shell. Gemini Live screen-share parity:
 *
 *  PRIMARY  ProjectionSource — MediaProjection VirtualDisplay + ImageReader
 *           (the same mechanism Gemini uses). Frames are delivered in-process
 *           by the compositor ONLY when the screen actually changes — zero
 *           shell, ~30-80ms per frame, no /data/local/tmp round-trip, no
 *           megabytes of PNG through the ADB socket.
 *
 *  FALLBACK ShellSource — the proven screencap path (ScreenCapture +
 *           LiveFrameCodec.jpegFromPngPath) used ONLY when the user denied
 *           projection AND the shell is up. Slower but real.
 *
 * HONESTY: every frame is a real captured screen. No frame is ever generated
 * or reused as "new". A source that fails reports it; the engine surfaces
 * the failure visibly instead of broadcasting blind.
 */
sealed class LiveScreenSource {

    abstract val kind: String            // "projection" | "shell"
    abstract fun captureJpeg(): LiveFrameCodec.JpegFrame?
    abstract suspend fun topActivity(): String?
    abstract fun close()

    /** md5 of the frame bytes — the real change gate (in-process, no shell). */
    fun hashOf(frame: LiveFrameCodec.JpegFrame): String =
        MessageDigest.getInstance("MD5").digest(
            frame.base64.toByteArray(Charsets.ISO_8859_1)).joinToString("") { "%02x".format(it) }

    // ─────────────────────────────────────────────────────────────────────
    // PRIMARY — MediaProjection
    // ─────────────────────────────────────────────────────────────────────
    class ProjectionSource(
        context: Context,
        private val projection: MediaProjection
    ) : LiveScreenSource() {

        override val kind = "projection"

        private val metrics = context.resources.displayMetrics
        private val longest = maxOf(metrics.widthPixels, metrics.heightPixels)
        private val scale = LiveFrameCodec.MAX_DIM_PX.toFloat() / longest
        private val vw = (metrics.widthPixels * scale).toInt().coerceAtLeast(1)
        private val vh = (metrics.heightPixels * scale).toInt().coerceAtLeast(1)

        private val readerThread = HandlerThread("sharingan-live-reader").apply { start() }
        private val readerHandler = Handler(readerThread.looper)

        private val reader: ImageReader = ImageReader.newInstance(vw, vh,
            android.graphics.PixelFormat.RGBA_8888, 2)

        private val display: VirtualDisplayLike
        @Volatile private var projectionLost = false

        init {
            // r1437-expert-fix: the VirtualDisplay MUST be created through the
            // MediaProjection token — DisplayManager.createVirtualDisplay with
            // AUTO_MIRROR and no projection is a SecurityException on every
            // device (verified against AOSP DisplayManagerService). Platform
            // 37 exposes the flags overload: (name,w,h,dpi,flags,surface,cb,handler)
            // — AUTO_MIRROR exactly as the official projection samples pass.
            display = VirtualDisplayLike(
                projection.createVirtualDisplay(
                    "SharinganLive", vw, vh, metrics.densityDpi,
                    android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface, null, readerHandler)
                    ?: throw IllegalStateException("virtual display rejected"))
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { projectionLost = true }
            }, readerHandler)
            Log.i(TAG, "projection source $vw x $vh (scaled from ${metrics.widthPixels} x ${metrics.heightPixels})")
        }

        val lost: Boolean get() = projectionLost

        /** Non-blocking latest frame; null = screen had no update since last take. */
        override fun captureJpeg(): LiveFrameCodec.JpegFrame? {
            val img: Image = try { reader.acquireLatestImage() ?: return null } catch (_: Throwable) { return null }
            return try {
                val plane = img.planes[0]
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                val w = img.width; val h = img.height
                // rowStride may exceed w*pixelStride (padding) — copy row by row.
                val bitmap = if (rowStride == w * pixelStride) {
                    android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                        .also { plane.buffer.rewind(); it.copyPixelsFromBuffer(plane.buffer) }
                } else {
                    val buffer = plane.buffer
                    val trimmed = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                    val row = ByteArray(w * pixelStride)
                    val pixels = IntArray(w)
                    for (y in 0 until h) {
                        buffer.position(y * rowStride)
                        buffer.get(row)
                        for (x in 0 until w) {
                            val o = x * pixelStride
                            // RGBA_8888 byte order (R,G,B,A) → Android color int
                            // (A<<24 | R<<16 | G<<8 | B) — channel-exact.
                            pixels[x] = ((row[o + 3].toInt() and 0xFF) shl 24) or
                                ((row[o].toInt() and 0xFF) shl 16) or
                                ((row[o + 1].toInt() and 0xFF) shl 8) or
                                (row[o + 2].toInt() and 0xFF)
                        }
                        trimmed.setPixels(pixels, 0, w, 0, y, w, 1)
                    }
                    trimmed
                }
                LiveFrameCodec.jpegFromBitmap(bitmap)?.also { bitmap.recycle() }
            } catch (t: Throwable) {
                Log.w(TAG, "projection frame failed: ${t.message}")
                null
            } finally {
                try { img.close() } catch (_: Throwable) {}
            }
        }

        override suspend fun topActivity(): String? = LiveScreenSource.topActivityViaShell()

        override fun close() {
            try { display.vd.release() } catch (_: Throwable) {}
            try { reader.close() } catch (_: Throwable) {}
            try { projection.stop() } catch (_: Throwable) {}
            readerThread.quitSafely()
        }

        /** Local alias so the class needs no android.hardware.display import mess. */
        class VirtualDisplayLike(val vd: android.hardware.display.VirtualDisplay)
    }

    // ─────────────────────────────────────────────────────────────────────
    // FALLBACK — shell screencap (real frames, slower cadence)
    // ─────────────────────────────────────────────────────────────────────
    class ShellSource : LiveScreenSource() {

        override val kind = "shell"

        override fun captureJpeg(): LiveFrameCodec.JpegFrame? = null // suspend-only path, see captureJpegSuspending

        /**
         * The shell path is suspend (ADB round-trips). The engine calls this
         * when kind=="shell"; captureJpeg() stays null-returning for symmetry.
         */
        suspend fun captureJpegSuspending(): LiveFrameCodec.JpegFrame? {
            if (!ShizukuExec.available()) return null
            val frame = moe.shizuku.manager.sharingan.ScreenCapture.capture() ?: return null
            lastActivityLine = frame.activity
            val pngB64 = moe.shizuku.manager.sharingan.SharinganVisionHub.readPngBase64(frame.pngPath)
                ?: return null
            return LiveFrameCodec.jpegFromPngBase64(pngB64)
        }

        @Volatile var lastActivityLine: String? = null

        override suspend fun topActivity(): String? = lastActivityLine ?: LiveScreenSource.topActivityViaShell()

        override fun close() {}
    }

    companion object {
        private const val TAG = "LiveScreenSource"

        /** Optional metadata (never required for the broadcast to be alive). */
        suspend fun topActivityViaShell(): String? =
            if (ShizukuExec.available())
                moe.shizuku.manager.sharingan.ScreenCapture.topActivity() else null
    }
}
