package moe.shizuku.manager.agent.auto

/**
 * CommandValidator — the safety gate of the autonomous loop.
 *
 * Architecture C rule: the model NEVER executes. Every command it plans must pass
 * this local validator first. Three tiers:
 *   ALLOW   — provably read-only inspection commands run without asking.
 *   CONFIRM — anything mutating / unknown / possibly exfiltrating data: the loop
 *             pauses and asks the user explicitly (human is the second defense line).
 *   BLOCK   — category-defining danger (reboot, flash, su, wipe...) never runs.
 *
 * Default for ANY unknown command is CONFIRM, never ALLOW (safe-by-default).
 */
object CommandValidator {

    enum class Tier { ALLOW, CONFIRM, BLOCK }
    data class Ruling(val tier: Tier, val reason: String)

    // ---------- BLOCK: never executed, no dialog even offered ----------

    private val blockRules = listOf(
        Regex("""\breboot\b""") to "reboots the device",
        Regex("""\bshutdown\b|\bpoweroff\b""") to "powers off the device",
        Regex("""\bfastboot\b""") to "fastboot mode",
        Regex("""\bflash[_a-z]*\b""") to "flashing partitions",
        Regex("""\bdd\b[^\n]*of=/dev/(block|mmcblk|sd[a-z])""") to "raw block-device write (dd)",
        Regex("""\bmk(fs|e2fs|f2fs|ext4)\b""") to "filesystem formatting",
        Regex("""\b(wipe|factory_?reset)\b""") to "factory wipe",
        Regex("""(^|\s)su(\s|$)""") to "privilege escalation (su)",
        Regex("""\bsudo\b""") to "privilege escalation (sudo)",
        Regex("""\bsetprop\b""") to "system property mutation",
        Regex("""\bmount\b[^\n]*remount""") to "remounting partitions",
        Regex("""^\s*(stop|start)\s*$""") to "stops/starts the Android framework",
        Regex("""\brm\s+-[a-z]*r[a-z]*f[a-z]*\s+/(|\s|\*)""") to "recursive delete from filesystem root",
        Regex("""\brm\s+-[a-z]*r[a-z]*f[a-z]*\s+/(system|data|vendor|product|odm)\b""") to "recursive delete in a system volume",
        Regex("""\bsendmail\b|\bsendmail\b""") to "sends data off-device",
        Regex("""\bservice\s+call\b""") to "raw binder service calls",
        // r1381 capability-audit hardening: privilege/security category guards
        Regex("""\bsetenforce\b""") to "disables SELinux enforcement",
        Regex("""\bmagisk\b""") to "root / privilege-escalation installation",
        Regex("""\bsm\s+(partition|forget|fumount|format)\b""") to "repartitions/formats storage",
        Regex("""\bsettings\s+put\s+\w+\s+adb_enabled\b""") to "would disable the ADB channel the agent needs"
    )

    // ---------- CONFIRM: explicit user approval required ----------

    private val confirmRules = listOf(
        Regex("""\bpm\s+(uninstall|clear|disable|enable|suspend|unsuspend|hide|unhide|install|reinstall|grant|revoke|reset-permissions|set-\w+)\b""") to "changes installed apps / permissions",
        Regex("""\bsettings\s+(put|delete)\b""") to "modifies system settings",
        Regex("""\bam\s+(force-stop|kill|crash|broadcast)\b""") to "affects running apps",
        Regex("""\bam\s+start\b""") to "launches components on the device",
        Regex("""\binput\b""") to "injects touches/keys (controls the device)",
        Regex("""\b(wm|wm\s+size|wm\s+density)\b[^\n]*(reset|[0-9]{3,})""") to "changes display size/density",
        Regex("""\bsvc\b""") to "toggles radios/services (wifi/data/usb)",
        Regex("""\b(kill|killall|pkill)\b""") to "kills processes",
        Regex("""\b(chmod|chown|chgrp|chcon)\b""") to "changes file permissions/ownership",
        Regex("""\b(mv|cp|mkdir|touch|truncate|shred|ln)\b""") to "modifies the filesystem",
        Regex("""\brm\b""") to "deletes files",
        Regex("""\b(cmd|content)\s+(call|write|insert|update|delete|uninstall|clear)\b""") to "mutates via cmd/content provider",
        Regex("""\b(getevent)\b""") to "streams raw input events",
        Regex("""^\s*(top|logcat|log)\s*$""") to "endless streaming command (use -n 1 / -d instead)",
        Regex("""\bnc\b|\bncat\b|\btelnet\b|\bftp\b|\bscp\b|\brsync\b""") to "moves data over the network",
        Regex("""\b(curl|wget|http|https)\b""") to "may send data outside the phone",
        Regex("""\bam\s+start[^#\n]*(http|https)""") to "opens an external link (data may leave the phone)",
        Regex("""\bifconfig\s+\w+\s+(up|down)\b|\bip\s+link\s+set\b""") to "changes network interfaces",
        Regex("""\bdumpsys\s+deviceidle\s+(whitelist|enable|disable)\b|\bcmd\s+deviceidle\b""") to "changes battery/doze policy",
        // ---- r1381 capability audit: explicit ASK-FIRST tier for every device domain ----
        Regex("""\bappops\s+set\b""") to "changes per-app behavior/permissions (appops)",
        Regex("""\bmedia\s+volume\b""") to "changes audio volume",
        Regex("""\bscreenrecord\b""") to "records the screen",
        Regex("""\bmonkey\b""") to "injects random input events",
        Regex("""\blocksettings\b""") to "changes lock-screen credentials/settings",
        Regex("""\bdumpsys\s+battery\s+(set|unplug|reset|disable|enable)\b""") to "alters battery simulation",
        Regex("""\bdevice_config\s+(put|delete|change)\b""") to "modifies device configuration",
        Regex("""\bcmd\s+(notification|wifi|bluetooth_manager|connectivity|telecom|camera|audio|display|battery|vibrator_manager|location|uimode|overlay|nfc)\b""") to "changes a device service via cmd",
        // ---- r1385: Linux user-space (apt/dpkg inside the container — still ASK-FIRST) ----
        Regex("""\bapt(-get)?\s+(update|install|upgrade|full-upgrade|dist-upgrade|remove|purge|autoremove|download|add-apt-repository)\b""") to "installs/modifies packages in the terminal environment",
        Regex("""\bdpkg\s+(-i|--install|--configure|--unpack|-r|--remove|--purge)\b""") to "installs/removes .deb packages",
        Regex("""\b(snap|flatpak)\b""") to "modifies packages via another package manager"
    )

    // ---------- ALLOW: provably read-only (first-word or first-two-words heads) ----------

    private val allowHeads = setOf(
        "df", "ls", "stat", "du", "cat", "head", "tail", "wc", "grep", "egrep", "fgrep",
        "find", "getprop", "ps", "id", "whoami", "groups", "getenforce", "sestatus",
        "uptime", "uname", "date", "printenv", "env", "netstat", "ss", "nproc", "free",
        "lsof", "md5sum", "sha1sum", "sha256sum", "vmstat", "iostat", "screencap",
        "ifconfig", // r1381: read forms only — up/down mutations are caught by confirmRules first
        "pwd", "which", "hostname", "basename", "dirname", "printf", "echo", "dpkg-query",
        "tree", "file", "less", "more", "sort", "uniq", "cut", "tr", "sed", "awk"
        // r1385: generic read/stream heads — sed -i and tee-style writes are caught by the guard below
    )

    private val allowTwoWord = setOf(
        "pm list", "pm path", "pm dump", "pm resolve-activity", "pm get-app-links",
        "settings get", "settings list",
        "cmd package list", "cmd activity get-recv-limits",
        "ip addr", "ip route", "ip rule", "ip link", "ip -s", "ip neigh",
        // r1381 capability audit: broader read surface for the super agent
        "content query", "content read",   // read content providers (sms/call_log/contacts/media)
        "appops get",                        // per-app ops inspection
        "service list",                      // binder services enumeration
        "dumpsys",                           // covered by head too, kept for clarity
        // r1385: Linux user-space read-only package queries
        "apt list", "apt show", "apt search", "apt policy", "apt-cache policy", "apt-cache search",
        "dpkg -l", "dpkg -s", "dpkg -L", "dpkg -p", "dpkg -C", "dpkg --list", "dpkg --audit"
    )

    private val chaining = Regex("""&&|\|\||;|`|\$\(""")
    private val newlines = Regex("""[\r\n]""")

    fun validate(command: String): Ruling {
        val cmd = command.trim()
        if (cmd.isEmpty()) return Ruling(Tier.BLOCK, "empty command")
        if (cmd.length > 2000) return Ruling(Tier.BLOCK, "command too long")

        // 1) hard blocks first - matched anywhere in the command
        for ((re, why) in blockRules) {
            if (re.containsMatchIn(cmd)) return Ruling(Tier.BLOCK, why)
        }

        // 2) subcommand injection markers always need a human
        if (chaining.containsMatchIn(cmd) && !isReadPipe(cmd)) {
            return Ruling(Tier.CONFIRM, "chained/subcommand expression")
        }
        if (newlines.containsMatchIn(cmd)) return Ruling(Tier.CONFIRM, "multi-line command")

        // 3) explicit confirm rules
        for ((re, why) in confirmRules) {
            if (re.containsMatchIn(cmd)) return Ruling(Tier.CONFIRM, why)
        }

        // 4) allow-list of provably read-only heads
        if (isReadOnly(cmd)) return Ruling(Tier.ALLOW, "read-only inspection")

        // 5) safe default: unknown => ask the user
        return Ruling(Tier.CONFIRM, "unknown command (safe default)")
    }

    /** A single pipeline of read-only stages ("ps -A | grep x") is still read-only. */
    private fun isReadPipe(cmd: String): Boolean {
        if (!cmd.contains('|')) return false
        if (chaining.containsMatchIn(cmd)) return false
        return cmd.split('|').all { it.isNotBlank() && isReadOnly(it.trim()) }
    }

    private fun isReadOnly(cmd: String): Boolean {
        val head = cmd.split(Regex("""\s+"""))
        val first = head.getOrNull(0) ?: return false
        val two = head.take(2).joinToString(" ")

        if (first == "dumpsys") return true // service dump is read-only
        if (allowHeads.contains(first)) {
            // screencap writes a file => keep it behind approval unless -p to stdout path in /data/local/tmp
            if (first == "screencap") return true // writes only to the given path on device tmp
            return true
        }
        if (two in allowTwoWord) {
            // pm list/path/dump/resolve, settings get/list, cmd package list, ip read forms
            return true
        }
        if (first == "logcat") {
            return cmd.contains(" -d") || cmd.contains(" -t") || cmd.startsWith("logcat -d") || cmd.startsWith("logcat -t")
        }
        // r1381: uiautomator dump = read-only UI hierarchy snapshot (how the agent "sees" the screen)
        if (first == "uiautomator") return cmd.contains("dump") && !cmd.contains("runtest")
        if (first == "top") return cmd.contains("-n")
        if (first == "ping") return cmd.contains("-c")
        if (first == "wm") return two == "wm size" && head.size == 2 || two == "wm density" && head.size == 2
        // r1385: sed/awk writes files in-place => never auto-ALLOW
        if (first == "sed" && cmd.contains(" -i")) return false
        // r1385: sleep is harmless but must not auto-run chained (chain check already handled)
        if (first == "sleep") return Regex("""^sleep\s+\d+$""").matches(cmd)
        return false
    }
}
