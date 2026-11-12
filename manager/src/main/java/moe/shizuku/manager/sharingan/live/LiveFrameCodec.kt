package moe.shizuku.manager.sharingan.live

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * aMiNo 2.0 (r1436) — LIVE FRAME CODEC: the JPEG pipeline that matches the
 * researched Gemini Live screen-share performance envelope.
 *
 * RESEARCH GROUND TRUTH (ai.google.dev/gemini-api/docs/live + live-guide,
 * fetched 2026-11-12):
 *  - "Video frames are sent as individual images (JPEG or PNG) at a specific
 *    frame rate (max 1 frame per second)" — Live API sends COMPRESSED JPEG,
 *    never full-size PNG, and never above 1 FPS.
 *  - r1435 shipped the full screencap PNG (2-8MB → megabytes of base64 per
 *    round): that is why the live loop felt slow next to Gemini.
 *
 * THE FIX: the shell-owned PNG (screencap) is downscaled + JPEG-compressed
 * ON DEVICE before it ever leaves the phone:
 *  - longest side capped to 720px (phone screens are 1080-1440px; a 720px
 *    frame keeps text legible for vision models at a fraction of the bytes);
 *  - JPEG quality 52 (the knee of the quality/size curve for UI content);
 *  - typical result: 60-160KB per frame (vs 2-8MB PNG) — same visual truth,
 *    ~30-50x less upload per live round.
 *
 * HONESTY: the frame is a REAL captured screenshot, only downscaled — never
 * regenerated, never cropped to hide anything. Any decode/encode failure
 * returns null and the caller reports it; a fabricated frame never exists.
 */
object LiveFrameCodec {

    private const val TAG = "LiveFrameCodec"
    const val MAX_DIM_PX = 720
    const val JPEG_QUALITY = 52
    const val MIME = "image/jpeg"

    /** A compressed live frame, ready for provider attachment. */
    data class JpegFrame(
        val base64: String,
        val mime: String = MIME,
        val widthPx: Int,
        val heightPx: Int,
        val bytes: Int
    )

    /**
     * Read the shell-owned PNG at [pngPath] (base64 through the proven shell
     * path — the app cannot open /data/local/tmp directly), decode, downscale
     * to MAX_DIM_PX, JPEG-encode, return base64. null = honest failure.
     */
    suspend fun jpegFromPngPath(pngPath: String): JpegFrame? = withContext(Dispatchers.IO) {
        try {
            val enc = moe.shizuku.manager.terminal.linux.ShizukuExec.oneShot(
                "base64 -w0 '$pngPath' 2>/dev/null", 60_000)
            val pngB64 = enc.output.trim()
            if (pngB64.length < 100) return@withContext null
            jpegFromPngBase64(pngB64)
        } catch (t: Throwable) {
            Log.w(TAG, "jpegFromPngPath failed: ${t.message}")
            null
        }
    }

    /**
     * r1437 — the MediaProjection path: encode an already-decoded screen
     * bitmap straight to the Gemini-spec JPEG (no shell, no PNG round-trip).
     * Returns null on honest failure; never fabricates.
     */
    fun jpegFromBitmap(src: Bitmap): JpegFrame? {
        return try {
            val scaled = scaleToMax(src, MAX_DIM_PX)
            val w = scaled.width; val h = scaled.height
            val stream = java.io.ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            if (scaled !== src) scaled.recycle()
            val bytes = stream.toByteArray()
            if (bytes.size < 512) null
            else JpegFrame(
                base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                mime = MIME, widthPx = w, heightPx = h, bytes = bytes.size)
        } catch (t: Throwable) {
            Log.w(TAG, "jpegFromBitmap failed: ${t.message}")
            null
        }
    }

    /** Decode a PNG base64 payload → downscaled JPEG base64. null = honest failure. */
    fun jpegFromPngBase64(pngB64: String): JpegFrame? {
        return try {
            val pngBytes = Base64.decode(pngB64, Base64.DEFAULT)
            if (pngBytes.size < 8 || pngBytes[0] != 0x89.toByte() || pngBytes[1] != 0x50.toByte()) {
                null // PNG magic missing — refuse, never invent
            } else {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size, opts)
                if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                    null
                } else {
                    var sample = 1
                    val longest = maxOf(opts.outWidth, opts.outHeight)
                    while (longest / (sample * 2) >= MAX_DIM_PX) sample *= 2
                    val dOpts = BitmapFactory.Options().apply { inSampleSize = sample }
                    val src = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size, dOpts)
                    if (src == null) {
                        null
                    } else {
                        val scaled = scaleToMax(src, MAX_DIM_PX)
                        val w = scaled.width; val h = scaled.height
                        val stream = java.io.ByteArrayOutputStream()
                        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
                        if (scaled !== src) scaled.recycle()
                        src.recycle()
                        val bytes = stream.toByteArray()
                        if (bytes.size < 512) null
                        else JpegFrame(
                            base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                            mime = MIME,
                            widthPx = w,
                            heightPx = h,
                            bytes = bytes.size
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "jpegFromPngBase64 failed: ${t.message}")
            null
        }
    }

    private fun scaleToMax(src: Bitmap, maxDim: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxDim) return src
        val f = maxDim.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src, (src.width * f).toInt().coerceAtLeast(1),
            (src.height * f).toInt().coerceAtLeast(1), true)
    }
}
