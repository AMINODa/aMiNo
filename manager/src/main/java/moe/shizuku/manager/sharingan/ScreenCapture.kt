package moe.shizuku.manager.sharingan

import moe.shizuku.manager.terminal.linux.ShizukuExec

/**
 * Captures ONE screen frame through the aMiNo service shell — the same proven
 * path as ScreenPerception.capture() (screencap -p → /data/local/tmp).
 *
 * Alpha honesty notes:
 *  - The frame hash is the REAL md5 of the captured PNG computed on-device
 *    (screencap PNGs are deterministic: identical screen → identical bytes).
 *    It is a change detector, nothing more — never presented as content.
 *  - Screen TEXT in alpha comes from `uiautomator dump` (the real view text of
 *    the foreground window). ML Kit pixel OCR lands in 1.5.x behind the same
 *    interface (PLAN_aMiNo2.md §3.2).
 *  - Pixel data stays on device in /data/local/tmp — the app never copies
 *    screenshot bytes into its private storage in this alpha.
 */
object ScreenCapture {

    private const val GLOB = "/data/local/tmp/amino_sharingan_*.png"
    private const val KEEP_LAST = 5

    data class Frame(
        val hash: String,       // md5 of the PNG — real on-device value
        val activity: String?,  // topResumedActivity (or null when unreadable)
        val pngPath: String
    )

    /** Capture one frame. null = capture failed (service down / screencap error). */
    suspend fun capture(): Frame? {
        if (!ShizukuExec.available()) return null
        val path = "$GLOB".replace("*", System.currentTimeMillis().toString())
        val cap = ShizukuExec.oneShot("screencap -p '$path'; echo CAP_RC=\$?", 30_000)
        if (!cap.ok || !cap.output.contains("CAP_RC=0")) return null
        val md5 = ShizukuExec.oneShot("md5sum '$path' 2>/dev/null | cut -d' ' -f1", 10_000)
        val hash = md5.output.trim().take(32)
        if (hash.length != 32) return null
        return Frame(hash = hash, activity = topActivity(), pngPath = path)
    }

    /** Foreground activity — read, never guessed. Empty string when unreadable. */
    suspend fun topActivity(): String? {
        val s = ShizukuExec.oneShot(
            "dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity || " +
            "dumpsys window 2>/dev/null | grep -m1 mCurrentFocus", 15_000
        )
        val line = s.output.trim()
        return line.ifBlank { null }?.take(220)
    }

    /** Read the visible UI text via uiautomator dump (real view text, truncated). */
    suspend fun screenText(maxChars: Int = 2000): String? {
        if (!ShizukuExec.available()) return null
        val dumpPath = "/data/local/tmp/amino_sharingan_ui.xml"
        val d = ShizukuExec.oneShot(
            "uiautomator dump '$dumpPath' >/dev/null 2>&1; echo DUMP_RC=\$?", 20_000
        )
        if (!d.output.contains("DUMP_RC=0")) return null
        val txt = ShizukuExec.oneShot(
            "sed -e 's/<node[^>]*text=\"\"[^>]*>/ /g' '$dumpPath' 2>/dev/null | " +
            "grep -o 'text=\"[^\"]\\{1,\\}\"' | sed 's/text=\"//;s/\"$//' | " +
            "head -c $maxChars", 15_000
        )
        return txt.output.ifBlank { null }
    }

    /** Housekeeping: keep only the newest KEEP_LAST frame files. */
    suspend fun pruneOld() {
        ShizukuExec.oneShot(
            "ls -t $GLOB 2>/dev/null | tail -n +${KEEP_LAST + 1} | xargs -r rm -f", 10_000
        )
    }

    /** Remove every frame file this module created. */
    suspend fun cleanupAll() {
        ShizukuExec.oneShot("rm -f $GLOB /data/local/tmp/amino_sharingan_ui.xml", 10_000)
    }
}
