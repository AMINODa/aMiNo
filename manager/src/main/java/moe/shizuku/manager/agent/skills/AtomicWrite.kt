package moe.shizuku.manager.agent.skills

import java.io.File
import java.io.FileOutputStream

/**
 * 1.6 (r1421) — CRASH-SAFE SKILL WRITES (brief problem 5: "كتابة آمنة ضد الانقطاع").
 *
 * Every skill file used to be saved with [File.writeText] — a plain truncate-then-
 * write. A process death (OOM, user swipes the app away, battery pull) in the
 * middle of that write left a TRUNCATED JSON on disk, and the skill silently
 * disappeared from skill_list / refused to run — data loss with no honest report.
 *
 * The atomic pattern is the POSIX standard two-step:
 *   1. write the FULL content to a sibling temp file + fsync (data is durable
 *      before anything points at it),
 *   2. rename temp -> target (same directory, so it is an atomic metadata flip:
 *      readers see either the complete old file or the complete new file — never
 *      a half-written one).
 * If the rename itself fails on an exotic filesystem we fall back to the direct
 * write so saving never regresses. Callers keep their exact signatures.
 */
internal fun atomicWriteText(file: File, text: String) {
    val tmp = File(file.parentFile, file.name + ".tmp")
    try {
        FileOutputStream(tmp).use { fos ->
            fos.write(text.toByteArray(Charsets.UTF_8))
            fos.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            file.writeText(text)
        }
    } catch (e: Exception) {
        tmp.delete()
        // Last resort: behave exactly like the old direct write (callers already
        // wrap save paths in their own error reporting).
        file.writeText(text)
    }
}
