package moe.shizuku.manager.agent.perception

import android.util.Xml
import kotlinx.coroutines.delay
import moe.shizuku.manager.agent.AgentTools
import moe.shizuku.manager.terminal.linux.ShizukuExec
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * v1.3 SUPER AGENT — Screen Intelligence (perception + actuation layer).
 *
 * Inspired by the uiautomator2 / MobileAgent-Android school (the user's research:
 * Screen Intelligence phase): the agent SEES the real screen as a structured
 * element list (resource-id, text, label, center coordinates) and ACTS on it with
 * real input injection, then VERIFIES with a fresh read. Act -> Verify is mandatory.
 *
 * Everything runs through the aMiNo Shizuku service (shell uid 2000) — the same
 * proven channel as the whole terminal stack, no wireless ADB needed:
 *  - perception  = `uiautomator dump` (the platform's own hierarchy extractor)
 *                  + `dumpsys window` (which app/activity is focused right now)
 *  - actuation   = `input tap|swipe|text|keyevent` (the adb-identical channel)
 *  - capture     = `screencap -p` (PNG kept on the device, last 5 pruned)
 *
 * No OCR in v1.3 (deliberate: bundling Tesseract would triple the APK for a
 * marginal gain): the tree exposes every text/label the platform already knows;
 * pixel-only content (photos) is reported as not readable — honest by design.
 * Tesseract stays a separate tool candidate for a later release (user's own
 * layering: Tesseract is a Tool, never a Skill).
 */
object ScreenPerception {

    private const val DUMP_PATH = "/data/local/tmp/amino_ui_dump.xml"
    private const val SCREEN_GLOB = "/data/local/tmp/amino_screen_*.png"

    /** Real availability check — never claims perception without the service. */
    fun serviceReady(): Boolean = runCatching { ShizukuExec.available() }.getOrDefault(false)

    private fun unavailable(): AgentTools.ToolResult = AgentTools.ToolResult(
        false,
        "screen tools need the aMiNo service (Shizuku) running — connect it from the Adb screen first"
    )

    // ------------------------------------------------------------------ perceive

    /**
     * Reads the CURRENT screen: focused app/activity + every element the platform
     * exposes (clickable OR carrying text/label), each with its center coordinates
     * so the model can target screen_act precisely.
     */
    suspend fun read(maxElements: Int): AgentTools.ToolResult {
        if (!serviceReady()) return unavailable()

        // which app/activity is on top right now (navigation awareness)
        val focus = ShizukuExec.oneShot(
            "dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' | head -n 2", 15_000
        ).output.trim()

        // the platform hierarchy dump (retry once: animated windows are never idle)
        var dump = ShizukuExec.oneShot(
            "uiautomator dump '$DUMP_PATH' 2>&1; echo DUMP_RC=\$?", 30_000)
        if (dump.rc != 0 || dump.output.contains("ERROR", ignoreCase = true)) {
            delay(1200)
            dump = ShizukuExec.oneShot(
                "uiautomator dump '$DUMP_PATH' 2>&1; echo DUMP_RC=\$?", 30_000)
        }
        if (dump.rc != 0 || dump.output.contains("ERROR", ignoreCase = true))
            return AgentTools.ToolResult(
                false,
                "uiautomator dump failed (rc=${dump.rc}): ${dump.output.take(300)} — the focused window may " +
                    "block hierarchy extraction (animation/system dialog). Wait a second and retry, " +
                    "or use screen_capture"
            )

        val xml = ShizukuExec.oneShot("cat '$DUMP_PATH' 2>/dev/null; rm -f '$DUMP_PATH'", 15_000).output
        val els = parse(xml)
        if (els.isEmpty())
            return AgentTools.ToolResult(false, "the hierarchy XML was empty — retry screen_read once")

        val cap = maxElements.coerceIn(10, 200)
        val sb = StringBuilder()
        if (focus.isNotBlank())
            sb.append("WINDOW: ").append(focus.lineSequence().joinToString(" | ")).append('\n')
        sb.append("ELEMENTS idx|click|class|resource-id|text|content-desc|center — use centers with screen_act:\n")
        var shown = 0
        for ((i, e) in els.withIndex()) {
            if (shown >= cap) {
                sb.append("… +${els.size - cap} more elements (call again with a higher max_elements)\n")
                break
            }
            sb.append("${i} ${if (e.clickable) 'Y' else 'N'} ${e.cls} ${e.id} \"${e.text}\" \"${e.desc}\" @(${e.cx},${e.cy})\n")
            shown++
        }
        sb.append("total=${els.size}, shown=$shown. NOTE: text/labels only — pixel-only content (images) is NOT OCR-readable in this version.")
        return AgentTools.ToolResult(true, sb.toString())
    }

    private data class El(
        val cls: String, val id: String, val text: String, val desc: String,
        val clickable: Boolean, val cx: Int, val cy: Int
    )

    private val BOUNDS = Regex("\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]")

    /** Streaming XML parse — keeps only meaningful nodes (clickable or labelled). */
    private fun parse(xml: String): List<El> {
        if (!xml.contains("<hierarchy") && !xml.contains("<node")) return emptyList()
        val out = ArrayList<El>()
        try {
            val p = Xml.newPullParser()
            p.setInput(StringReader(xml))
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && p.name == "node") {
                    val text = p.getAttributeValue(null, "text") ?: ""
                    val desc = p.getAttributeValue(null, "content-desc") ?: ""
                    val clickable = p.getAttributeValue(null, "clickable") == "true"
                    if (clickable || text.isNotBlank() || desc.isNotBlank()) {
                        val b = BOUNDS.find(p.getAttributeValue(null, "bounds") ?: "")
                        if (b != null) {
                            val (x1, y1, x2, y2) = b.destructured
                            out.add(
                                El(
                                    (p.getAttributeValue(null, "class") ?: "").substringAfterLast('.'),
                                    p.getAttributeValue(null, "resource-id") ?: "",
                                    text.take(60), desc.take(40), clickable,
                                    (x1.toInt() + x2.toInt()) / 2, (y1.toInt() + y2.toInt()) / 2
                                )
                            )
                        }
                    }
                }
                ev = p.next()
            }
        } catch (_: Exception) {
            // return whatever was collected before a malformed tail
        }
        return out
    }

    // -------------------------------------------------------------------- act

    private val KEY_CODES = mapOf(
        "back" to 4, "home" to 3, "enter" to 66, "delete" to 67,
        "up" to 19, "down" to 20, "left" to 21, "right" to 22,
        "volume_up" to 24, "volume_down" to 25, "power" to 26, "recents" to 187,
        "tab" to 61, "escape" to 111, "menu" to 82, "search" to 84,
        "page_up" to 92, "page_down" to 93, "media_play_pause" to 85
    )

    private fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /**
     * Real input injection through the service (the adb-identical `input` channel):
     * tap / longtap / swipe / text / key / wait. The tool description DEMANDS a
     * fresh screen_read afterwards — claiming success without verification is the
     * one failure mode this layer must never allow.
     */
    suspend fun act(args: JSONObject): AgentTools.ToolResult {
        if (!serviceReady()) return unavailable()
        val action = args.optString("action", "").lowercase()
        val i = { k: String -> args.optInt(k, Int.MIN_VALUE) }
        val bad = { v: Int -> v < 0 }

        val cmd = when (action) {
            "tap" -> {
                val (x, y) = i("x") to i("y")
                if (bad(x) || bad(y))
                    return AgentTools.ToolResult(false, "tap requires x,y (element centers from screen_read)")
                "input tap $x $y"
            }
            "longtap" -> {
                val (x, y) = i("x") to i("y")
                if (bad(x) || bad(y))
                    return AgentTools.ToolResult(false, "longtap requires x,y")
                "input swipe $x $y $x $y 800"
            }
            "swipe" -> {
                val (x1, y1, x2, y2) = listOf(i("x"), i("y"), i("x2"), i("y2"))
                if (bad(x1) || bad(y1) || bad(x2) || bad(y2))
                    return AgentTools.ToolResult(false, "swipe requires x,y,x2,y2")
                val ms = args.optInt("duration_ms", 300).coerceIn(50, 5000)
                "input swipe $x1 $y1 $x2 $y2 $ms"
            }
            "text" -> {
                val t = args.optString("text", "")
                if (t.isEmpty())
                    return AgentTools.ToolResult(false, "text requires the text field")
                // `input text` encodes spaces as %s and takes a single argument
                "input text ${shellQuote(t.replace(" ", "%s"))}"
            }
            "key" -> {
                val k = args.optString("key", "").lowercase()
                val code = KEY_CODES[k] ?: k.toIntOrNull()
                    ?: return AgentTools.ToolResult(false,
                        "key must be one of ${KEY_CODES.keys} or a numeric keycode")
                "input keyevent $code"
            }
            "wait" -> {
                delay(args.optInt("ms", 500).coerceIn(100, 10_000).toLong())
                return AgentTools.ToolResult(true, "waited")
            }
            else -> return AgentTools.ToolResult(
                false, "action must be tap | longtap | swipe | text | key | wait")
        }

        val s = ShizukuExec.oneShot("$cmd; echo ACT_RC=\$?", 20_000)
        val ok = s.output.contains("ACT_RC=0")
        return if (ok) AgentTools.ToolResult(
            true, "$action OK — VERIFY NOW with a fresh screen_read before telling the user it worked"
        ) else AgentTools.ToolResult(
            false,
            "$action FAILED rc=${s.rc}: ${s.output.replace("ACT_RC=\\d+".toRegex(), "").trim().take(200)}"
        )
    }

    // ---------------------------------------------------------------- capture

    /**
     * Full-screen PNG via `screencap` (no MediaProjection prompt needed from the
     * shell uid). The file stays on the device for the user / future vision models;
     * only the 5 newest are kept.
     */
    suspend fun capture(): AgentTools.ToolResult {
        if (!serviceReady()) return unavailable()
        val p = "/data/local/tmp/amino_screen_${System.currentTimeMillis()}.png"
        val s = ShizukuExec.oneShot("screencap -p '$p'; echo CAP_RC=\$?", 30_000)
        if (!s.output.contains("CAP_RC=0"))
            return AgentTools.ToolResult(false, "screencap failed: ${s.output.take(200)}")
        ShizukuExec.oneShot("ls -t $SCREEN_GLOB 2>/dev/null | tail -n +6 | xargs rm -f 2>/dev/null", 10_000)
        return AgentTools.ToolResult(
            true,
            "screenshot saved on device: $p (PNG, kept in /data/local/tmp — v1.3 has no OCR; " +
                "use screen_read for the text/element view)"
        )
    }
}
