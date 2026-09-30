package moe.shizuku.manager.terminal.linux

import java.io.File
import java.io.RandomAccessFile

/**
 * aMiNo r1387 — dependency diagnostic for the BUNDLED Linux binaries
 * (PRoot, talloc, …), parsed straight from the ELF file shipped in the APK.
 *
 * WHY: r1386 taught us the hard way that "the binary exists" says nothing about
 * "the binary runs". Before anything is pushed or downloaded, this reader makes
 * the runtime contract explicit and printable:
 *   - ELF class / endianness  (only ELF64 little-endian is used by aMiNo)
 *   - e_machine               (AArch64 vs x86-64 — must match the device ABI)
 *   - PT_INTERP               (the dynamic interpreter; an Android-compatible
 *                              binary points at /system/bin/linker64 — a glibc
 *                              build would point at /lib/ld-linux-aarch64.so.1
 *                              and can NEVER execute under the Android linker)
 *   - DT_NEEDED               (the exact shared libraries the Android linker
 *                              must resolve — e.g. libtalloc.so.2)
 *   - DT_RUNPATH              (Termux builds carry /data/data/com.termux/… here,
 *                              which does NOT exist on aMiNo devices — that is
 *                              why every exec MUST set LD_LIBRARY_PATH)
 *
 * This is a compact, read-only ELF64-LE parser — exactly what is needed for the
 * two binaries we ship; anything unexpected is reported honestly as a failure
 * instead of guessed at. It NEVER executes the file, only reads bytes.
 */
object ElfInspector {

    private const val PT_LOAD = 1L
    private const val PT_DYNAMIC = 2L
    private const val PT_INTERP = 3L

    private const val DT_NEEDED = 1L
    private const val DT_STRTAB = 5L
    private const val DT_RUNPATH = 29L

    data class Info(
        val ok: Boolean,
        val reason: String?,            // why parsing failed (honest, verbatim)
        val machine: String?,           // "aarch64" | "x86-64" | raw number
        val elfType: String?,           // "EXEC" | "DYN"
        val interpreter: String?,       // PT_INTERP contents, null if none
        val needed: List<String>,       // DT_NEEDED libraries in order
        val runpath: String?            // DT_RUNPATH, null if none
    ) {
        val isAndroidInterpreter: Boolean
            get() = interpreter == null || interpreter.startsWith("/system/bin/")
    }

    /** Human-readable one-liner for the install log and failure reports. */
    fun describe(f: File): String {
        val i = inspect(f)
        if (!i.ok) return "ELF unreadable (${i.reason})"
        return buildString {
            append("ELF64 ${i.machine ?: "?"} (${i.elfType ?: "?"})")
            if (i.interpreter != null) append(", interpreter $i.interpreter") else append(", no interpreter (static)")
            append(", needs: ${if (i.needed.isEmpty()) "(none)" else i.needed.joinToString(", ")}")
            if (i.runpath != null) append("; runpath=$i.runpath (absent prefix — LD_LIBRARY_PATH is required)")
        }
    }

    /** Extracts the failing library name from a bionic linker error line, if present. */
    fun failingLibraryIn(errorText: String): String? =
        Regex("library \"([^\"]+)\"").find(errorText)?.groupValues?.get(1)

    fun inspect(f: File): Info {
        if (!f.exists()) return fail("file not found: ${f.name}")
        return try {
            RandomAccessFile(f, "r").use { raf ->
                val head = ByteArray(64)
                if (raf.read(head) != 64) return fail("file shorter than the ELF header")
                if (head[0] != 0x7F.toByte() || head[1] != 'E'.code.toByte() ||
                    head[2] != 'L'.code.toByte() || head[3] != 'F'.code.toByte())
                    return fail("not an ELF file (bad magic)")
                if (head[4] != 2.toByte()) return fail("not ELF64 (class=${head[4]})")
                if (head[5] != 1.toByte()) return fail("not little-endian (data=${head[5]})")

                val eType = u16(head, 16)
                val eMachine = u16(head, 18)
                val ePhoff = u64(head, 32)
                val ePhentsize = u16(head, 54).toInt()
                val ePhnum = u16(head, 56).toInt()
                if (ePhentsize < 56 || ePhnum <= 0) return fail("bad program header table (entsize=$ePhentsize num=$ePhnum)")

                // program headers
                var interp: String? = null
                var dynVaddr = -1L; var dynOff = -1L; var dynSz = 0L
                val loads = ArrayList<Triple<Long, Long, Long>>() // vaddr, off, filesz
                for (i in 0 until ePhnum) {
                    raf.seek(ePhoff + i.toLong() * ePhentsize)
                    val ph = ByteArray(ePhentsize)
                    if (raf.read(ph) != ePhentsize) return fail("truncated program header #$i")
                    val pType = u32(ph, 0)
                    val pOff = u64(ph, 8)
                    val pVaddr = u64(ph, 16)
                    val pFilesz = u64(ph, 32)
                    when (pType) {
                        PT_INTERP -> interp = cstr(fileBytes(raf, pOff, pFilesz))
                        PT_DYNAMIC -> { dynVaddr = pVaddr; dynOff = pOff; dynSz = pFilesz }
                        PT_LOAD -> loads.add(Triple(pVaddr, pOff, pFilesz))
                    }
                }

                var needed = emptyList<String>()
                var runpath: String? = null
                if (dynOff >= 0) {
                    val nEntries = (dynSz / 16).toInt()
                    var strtabVaddr = -1L
                    val raw = ArrayList<Pair<Long, Long>>() // tag, val
                    raf.seek(dynOff)
                    for (i in 0 until nEntries) {
                        val e = ByteArray(16)
                        if (raf.read(e) != 16) break
                        val tag = i64(e, 0)
                        if (tag == 0L) break
                        raw.add(Pair(tag, u64(e, 8)))
                        if (tag == DT_STRTAB) strtabVaddr = u64(e, 8)
                    }
                    if (strtabVaddr > 0) {
                        val strOff = vaddrToFileOffset(loads, strtabVaddr)
                        needed = raw.filter { it.first == DT_NEEDED }.mapNotNull { (_, v) ->
                            try { cstr(fileBytes(raf, strOff + v, 256)) } catch (e: Exception) { null }
                        }
                        runpath = raw.firstOrNull { it.first == DT_RUNPATH }?.let { (_, v) ->
                            try { cstr(fileBytes(raf, strOff + v, 512)) } catch (e: Exception) { null }
                        }
                    }
                }

                Info(true, null, machineName(eMachine), typeName(eType), interp, needed, runpath)
            }
        } catch (e: Exception) {
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    // ---------- helpers ----------

    private fun fail(why: String) = Info(false, why, null, null, null, emptyList(), null)

    private fun u16(b: ByteArray, o: Int): Long = (b[o].toLong() and 0xFFL) or ((b[o + 1].toLong() and 0xFFL) shl 8)
    private fun u32(b: ByteArray, o: Int): Long = (u16(b, o)) or (u16(b, o + 2) shl 16)
    private fun u64(b: ByteArray, o: Int): Long = (u32(b, o)) or (u32(b, o + 4) shl 32)
    private fun i64(b: ByteArray, o: Int): Long = u64(b, o) // tags we care about are small positives; DT_NULL=0

    private fun machineName(m: Long): String? = when (m) {
        183L -> "aarch64"
        62L -> "x86-64"
        else -> "machine#$m"
    }

    private fun typeName(t: Long): String? = when (t) {
        2L -> "EXEC"
        3L -> "DYN"
        4L -> "CORE"
        else -> "type#$t"
    }

    /** PT_LOAD vaddr → file offset mapping (first matching segment wins). */
    private fun vaddrToFileOffset(loads: List<Triple<Long, Long, Long>>, vaddr: Long): Long {
        for ((v, off, filesz) in loads) if (vaddr >= v && vaddr < v + filesz) return off + (vaddr - v)
        return vaddr // identity fallback for simple binaries
    }

    private fun fileBytes(raf: RandomAccessFile, off: Long, len: Long): ByteArray {
        val n = minOf(len, 4096L).toInt()
        val b = ByteArray(n)
        raf.seek(off)
        val got = raf.read(b)
        return if (got == n) b else b.copyOf(maxOf(got, 0))
    }

    private fun cstr(b: ByteArray): String? {
        val end = b.indexOf(0.toByte())
        if (end == 0) return null
        return String(b, 0, if (end < 0) b.size else end, Charsets.UTF_8).trim()
    }
}
