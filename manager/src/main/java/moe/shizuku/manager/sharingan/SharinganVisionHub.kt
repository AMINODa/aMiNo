package moe.shizuku.manager.sharingan

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * aMiNo 2.0 — Sharingan VISION (r1435): the eye that SEES, not just reads.
 *
 * Bridges the real captured screen IMAGE (a genuine PNG produced by `screencap`
 * through the aMiNo shell) to the LLM providers that support vision input:
 *  - Gemini        : inline_data { mime_type: image/png, data: base64 }
 *  - OpenAI-compat : image_url { url: "data:image/png;base64,..." }
 *    (same wire shape shared by OpenRouter / AgentRouter / Cloudflare).
 *
 * HONESTY RULES (unchanged from the Sharingan doctrine):
 *  - the image is a REAL frame captured on the device — never generated,
 *    never a placeholder; when capture fails there is NO image, and the
 *    callers say so instead of inventing one.
 *  - the staged image is consumed ONCE (read-once semantics, same pattern as
 *    SharinganContextHub), attached to the NEXT provider round.
 *  - OCR text stays available as a fallback: vision and text are complementary,
 *    never either/or.
 */
object SharinganVisionHub {

    private const val TAG = "SharinganVisionHub"

    /** Hard byte ceiling for a staged PNG (Gemini inline limit is 20MB; this is
     *  a safety bound so one frame can never blow the request budget). */
    private const val MAX_PNG_BYTES = 8 * 1024 * 1024

    data class StagedImage(
        val base64: String,
        val activity: String?,
        val frameHash: String,
        val capturedAtMs: Long,
        val source: String   // "screen_look" | "live_session" | "panel_snapshot"
    )

    /** The one image staged for the NEXT provider round (read-once). */
    @Volatile
    private var pending: StagedImage? = null

    /** The newest image of an ACTIVE live session (overwritten each changed
     *  frame; consumed by the next round while the session keeps running). */
    @Volatile
    internal var latestLive: StagedImage? = null

    fun hasPending(): Boolean = pending != null

    fun peek(): StagedImage? = pending

    /**
     * Capture a REAL frame NOW (screencap + md5 + topActivity) and stage its
     * PNG for the next round. Returns the staged image, or null on any failure
     * (shell down, screencap error, md5 unreadable, oversized PNG) — null is
     * an honest "no image this round", never a silent placeholder.
     */
    suspend fun stage(context: android.content.Context, source: String): StagedImage? {
        return withContext(Dispatchers.IO) {
            try {
                val frame = ScreenCapture.capture() ?: return@withContext null
                val b64 = readPngBase64(frame.pngPath) ?: return@withContext null
                val img = StagedImage(
                    base64 = b64,
                    activity = frame.activity,
                    frameHash = frame.hash,
                    capturedAtMs = System.currentTimeMillis(),
                    source = source
                )
                pending = img
                Log.i(TAG, "staged $source image (${b64.length} b64 chars, activity=${frame.activity?.take(80)})")
                img
            } catch (t: Throwable) {
                Log.w(TAG, "stage failed: ${t.message}")
                null
            }
        }
    }

    /** Stage the newest LIVE frame without a new capture (read-once). */
    fun stageFromLive(): StagedImage? {
        val img = latestLive ?: return null
        pending = img
        return img
    }

    /** Consume the staged image (or, during a live session, its newest frame). */
    fun consumeForNextRound(): StagedImage? {
        val p = pending
        if (p != null) { pending = null; return p }
        return if (SharinganLiveSession.active) latestLive else null
    }

    /**
     * Read the PNG bytes the shell owns — the app cannot open /data/local/tmp
     * files directly, so the bytes come back through the proven shell path
     * (`base64 -w0`). Oversized frames are refused honestly (null).
     * internal so SharinganLiveSession reuses the exact same truth path.
     */
    internal suspend fun readPngBase64(path: String): String? {
        val size = moe.shizuku.manager.terminal.linux.ShizukuExec.oneShot(
            "stat -c %s '$path' 2>/dev/null", 10_000
        ).output.trim().toLongOrNull() ?: return null
        if (size <= 0 || size > MAX_PNG_BYTES) return null
        val enc = moe.shizuku.manager.terminal.linux.ShizukuExec.oneShot(
            "base64 -w0 '$path' 2>/dev/null", 60_000
        )
        val b64 = enc.output.trim()
        if (b64.length < 100) return null
        // Real decode sanity check: corrupted/incomplete base64 must be refused.
        return try {
            val decoded = Base64.decode(b64, Base64.DEFAULT)
            // PNG magic check — a real screencap always starts with \x89PNG
            if (decoded.size < 8 || decoded[0] != 0x89.toByte() || decoded[1] != 0x50.toByte()) null
            else b64
        } catch (_: Throwable) { null }
    }

    fun clear() {
        pending = null
        latestLive = null
    }
}
