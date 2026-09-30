# aMiNo Agent — Capability Audit (r1381)

> The full inspection of everything the agent can **read** and **control** on the device,
> done one domain at a time. This is the document the safety model is built on:
> every command in the autonomous loop passes `CommandValidator` before execution,
> and the tier below decides whether it runs silently, pauses for the user, or is rejected.

Execution context: the agent runs commands as the **ADB `shell` user (uid 2000)**
through the wireless-ADB session. No root. What `shell` can do on Android, the agent can do — no more, no less.

## Tiers

| Tier | Meaning |
|------|---------|
| **ALLOW** | Provably read-only inspection. Runs automatically, no dialog. |
| **CONFIRM** | Mutates something / privacy-sensitive / unknown. The loop **pauses** and the user explicitly approves every single command (Allow / Deny dialog with the reason). |
| **BLOCK** | Category-defining danger. Never executed, no dialog is even offered. Safe default for any unknown command = CONFIRM, never ALLOW. |

## The capability matrix

### 1. Location / GPS
| | Commands |
|---|---|
| Read (ALLOW) | `dumpsys location`, `settings get secure location_mode`, `settings get secure location_providers_allowed`, `dumpsys activity service` location providers |
| Control (CONFIRM) | `settings put secure location_mode 0/1/3`, `settings put secure location_providers_allowed +gps,-network`, `appops set <pkg> android:fine_location allow/ignore` |
| Limits | Precise one-shot location fix needs an app (the agent can read the last known fix from `dumpsys location`). |

### 2. Calls / Telecom
| | Commands |
|---|---|
| Read (ALLOW) | `dumpsys telecom`, `dumpsys phone`, `content query --uri content://call_log/calls`, `dumpsys telephony.registry` |
| Control (CONFIRM) | `am start -a android.intent.action.DIAL -d tel:…` (open dialer), `am start -a android.intent.action.CALL -d tel:…` (place the call), `input keyevent KEYCODE_ENDCALL / KEYCODE_CALL`, `cmd telecom …` |
| Limits | No silent call recording (Android blocks it shell-wide). Answering a call via keyevent depends on the OEM phone app. |

### 3. SMS / MMS
| | Commands |
|---|---|
| Read (ALLOW) | `content query --uri content://sms` (inbox/sent…), `dumpsys telecom` messaging parts |
| Control (CONFIRM) | `am start -a android.intent.action.SENDTO -d smsto:…` (open composer with recipient/body), `service call` is BLOCKED |
| Limits | Silent SMS **sending** is intentionally not exposed (abuse vector); the agent opens the composer instead. |

### 4. Contacts
| | Commands |
|---|---|
| Read (ALLOW) | `content query --uri content://contacts/…`, `content query --uri content://com.android.contacts` |
| Control (CONFIRM) | `content insert/update/delete --uri content://com.android.contacts/…` |

### 5. Screen / Display
| | Commands |
|---|---|
| Read (ALLOW) | `screencap -p /data/local/tmp/shot.png` + `cat`, `uiautomator dump` (screen structure), `dumpsys window`, `dumpsys display`, `wm size`, `wm density` (no args = read) |
| Control (CONFIRM) | `settings put system screen_brightness N`, `settings put system screen_off_timeout N`, `settings put system user_rotation N`, `settings put system accelerometer_rotation 0/1`, `wm size/density N|reset`, `input keyevent KEYCODE_POWER` (screen off/on), `screenrecord --time-limit N` |
| Limits | `screenrecord` is capped by the engine's 20 s per-command timeout; long recordings need several runs. The screenshot lands **on the device** (`/data/local/tmp` or `/sdcard/Download`) — file *pull* to the phone running aMiNo is a tool-level TODO, not a shell limit. |

### 6. Camera / Torch
| | Commands |
|---|---|
| Read (ALLOW) | `dumpsys media.camera` (cameras, params, torch state) |
| Control (CONFIRM) | `am start -a android.media.action.STILL_IMAGE_CAMERA` (open camera app), `am start -a android.media.action.IMAGE_CAPTURE`, `cmd camera set-torch-mode <id> true/false` (OEM-dependent), `input keyevent KEYCODE_CAMERA` |
| Limits | No silent photo/video capture without a user-visible app — by design (privacy law + shell restrictions). Torch via `cmd camera` works on modern Android; some OEMs need `settings put system flashlight` fallbacks. |

### 7. Audio / Microphone / Volume / DND
| | Commands |
|---|---|
| Read (ALLOW) | `dumpsys audio`, `dumpsys media.audio_flinger`, `dumpsys media_session`, `settings get system volume_*`, `getenforce`-style `dumpsys` audio policy |
| Control (CONFIRM) | `media volume --stream N --set V`, `input keyevent KEYCODE_VOLUME_UP/DOWN/MUTE`, `cmd audio …`, `cmd notification set_dnd on/off/priority/alarms`, `settings put global zen_mode N` |
| Limits | Mic streaming is not possible from shell — only reading which app holds the mic (`dumpsys audio` recorder sessions). |

### 8. Notifications
| | Commands |
|---|---|
| Read (ALLOW) | `dumpsys notification --noredact` (active + recent notifications) |
| Control (CONFIRM) | `cmd notification post -S bigtext -t "title" tag "body"` (post a notification), `cmd notification set_dnd …`, `cmd notification allow_dnc/allow_listener` |

### 9. Wi-Fi / Network / Airplane mode
| | Commands |
|---|---|
| Read (ALLOW) | `dumpsys wifi`, `dumpsys connectivity`, `ip addr/route/rule/neigh`, `ifconfig`, `netstat`, `ss`, `ping -c`, `cmd wifi status` via dumpsys, `settings get global wifi_on`, `dumpsys netstats` |
| Control (CONFIRM) | `svc wifi enable/disable`, `svc data enable/disable`, `cmd wifi set-wifi-enabled enabled/disabled`, `settings put global airplane_mode_on 0/1` + `am broadcast -a android.intent.action.AIRPLANE_MODE`, `cmd connectivity airplane-mode enable/disable`, `ifconfig wlan0 up/down`, `cmd wifi connect-network …` |
| Limits | Joining a hidden/enterprise network may need UI; saved-network listing works via `cmd wifi list-networks` (CONFIRM — it can expose PSKs on old Android). |

### 10. Bluetooth / NFC
| | Commands |
|---|---|
| Read (ALLOW) | `dumpsys bluetooth_manager`, `dumpsys nfc` |
| Control (CONFIRM) | `svc bluetooth enable/disable`, `cmd bluetooth_manager enable/disable`, `svc nfc enable/disable`, `cmd nfc enable/disable` |

### 11. Battery / Power / Doze
| | Commands |
|---|---|
| Read (ALLOW) | `dumpsys battery` (level, health, temp, voltage, charge counter), `dumpsys batterystats`, `dumpsys deviceidle` |
| Control (CONFIRM) | `dumpsys deviceidle whitelist +<pkg>`, `cmd deviceidle …`, `dumpsys battery set/unplug/reset` (simulation — sandbox only), `svc power stayon …` |
| BLOCK | `reboot`, `shutdown`, `poweroff` — never executed. |

### 12. Apps / Packages / Permissions
| | Commands |
|---|---|
| Read (ALLOW) | `pm list packages/features/users/…`, `pm path`, `pm dump`, `dumpsys package <pkg>`, `appops get <pkg>`, `cmd package list` |
| Control (CONFIRM) | `pm install/uninstall/clear/enable/disable/disable-user/suspend/unsuspend/hide/unhide`, `pm grant/revoke <pkg> <permission>`, `pm reset-permissions`, `appops set`, `am force-stop`, `am kill`, `kill <pid>`, `monkey` (stress/input), `cmd package compile/dexopt` |
| Notes | `pm grant/revoke` works for **runtime** permissions because `shell` holds `GRANT_RUNTIME_PERMISSIONS` — this is how the agent installs/repairs apps end-to-end. |

### 13. Files / Storage
| | Commands |
|---|---|
| Read (ALLOW) | `ls`, `cat`, `head/tail`, `du`, `df`, `stat`, `find`, `grep`, `md5sum/sha256sum`, `wc` |
| Control (CONFIRM) | `mkdir`, `touch`, `cp`, `mv`, `rm` (non-recursive-root), `chmod/chown/chcon`, `ln`, `truncate` |
| BLOCK | `mkfs/e2fsck/f2fs`, `dd of=/dev/block…`, `sm partition/forget`, `rm -rf /` or into system volumes |

### 14. Processes / Memory / CPU
| | Commands |
|---|---|
| Read (ALLOW) | `ps -A`, `top -n 1`, `free`, `nproc`, `vmstat`, `lsof`, `dumpsys meminfo/cpuinfo/procstats`, `uptime`, `uname` |
| Control (CONFIRM) | `kill`, `killall`, `pkill`, `am force-stop/kill`, `renice` |

### 15. Input automation (remote control)
| | Commands |
|---|---|
| Read (ALLOW) | `uiautomator dump` (widget tree → coordinates), `dumpsys input_method` (focus), `getevent` stays CONFIRM (raw stream) |
| Control (CONFIRM) | `input tap x y`, `input swipe x1 y1 x2 y2 ms`, `input text "…"`, `input keyevent <code>` — full remote control of any app, always behind approval |

### 16. System properties / configuration
| | Commands |
|---|---|
| Read (ALLOW) | `getprop`, `getenforce`, `settings get/list`, `content query --uri content://settings/…` |
| Control (CONFIRM) | `device_config put/delete` |
| BLOCK | `setprop` (system property mutation), `setenforce` (SELinux) |

### 17. Users / Lock screen
| | Commands |
|---|---|
| Read (ALLOW) | `pm list users`, `dumpsys user`, `locksettings get-disabled` |
| Control (CONFIRM) | `locksettings …`, `am switch-user`, `pm install --user …` |
| Notes | Changing lock credentials is gated by CONFIRM and needs the old credential — the agent cannot bypass it. |

### 18. Clipboard
| | Commands |
|---|---|
| Limits | Android 10+ **blocks clipboard access for background/shell processes** — honest limitation. Workaround used by the agent: `input text` (types content), or a focused field + `input keyevent PASTE`. |

### 19. Data exfiltration guards
| | Commands |
|---|---|
| CONFIRM (explicit reason) | `curl`, `wget`, `http(s)`, `nc/ncat/telnet/ftp/scp/rsync`, `am start` with external links |
| BLOCK | `sendmail`, `service call` |

### 20. Permission self-heal & app-identity user data (r1382)
| | Mechanism |
|---|---|
| Shell identity boost | `pm grant com.android.shell <perm>` for READ_CALL_LOG, READ_CONTACTS, READ_SMS, READ_PHONE_STATE, CALL_PHONE, ACCESS_FINE/COARSE_LOCATION, CAMERA, RECORD_AUDIO, READ/WRITE_EXTERNAL_STORAGE — the com.android.shell package declares these, so `pm grant` succeeds; content providers (call log / SMS / contacts) then answer `content query`. Tool: `grant_permissions` (chat) / ASK-FIRST command (auto loop) + "Boost ADB shell identity" button on the Permissions page. Every grant is verified with a real provider probe. |
| App identity fallback | The aMiNo app itself declares and requests (official dialogs, Permissions page "Grant all") READ_CALL_LOG / READ_CONTACTS / READ_SMS / READ_PHONE_STATE / media / notifications; the `user_data` tool (kind = calls / sms / contacts) reads through the app's ContentResolver — works even when the shell identity stays blocked. |
| Auto-loop self-heal | When a read fails with Permission Denial, the planner/self-corrector is instructed to `pm grant com.android.shell <perm>` (ASK-FIRST) and retry the same read. |

## 21. Linux user-space environment (r1385 — Debian 12 via PRoot)
A REAL Debian 12 (bookworm) user-space can be installed INSIDE aMiNo — no root, fully separate from ADB / Termux / the local app shell.

| Aspect | Reality |
|---|---|
| What it is | Debian 12 rootfs + PRoot; one persistent bash per session inside the container (cwd/env survive), separate stdout/stderr, real exit codes |
| Where it runs | `/data/local/tmp/amino-linux` (bin/, lib/, rootfs/, tmp/), executed BY the aMiNo service as the ADB shell identity (uid 2000) — because Android 10+ W^X forbids exec() from app-private storage |
| Where the archive lives | aMiNo private storage (`files/linux/debian-rootfs.tar.gz|xz`), kept for Reset |
| Integrity | Primary: official Docker `library/debian` registry — manifest by tag → layer blob verified byte-by-byte against its sha256 digest. Fallback: cdimage.debian.org cloud rootfs verified against the official SHA512SUMS |
| Bundled binaries | proot (termux build, bionic), libtalloc, libandroid-shmem, xz + liblzma, **toybox tar (r1386 — static musl, the bundled extraction tool)** — shipped as jniLibs (`libamino_*.so`), pushed to the runtime dir and exec-verified before use |
| Pre-flight (all real) | CPU ABI (arm64/amd64), app storage ≥ 700 MB, /data ≥ 800 MB, network, aMiNo service running, **filesystem capability probe (r1386): writability + symlink + hardlink creation tested with real syscalls at the actual install path, BEFORE any download; the backing fstype is read from /proc/mounts; shared storage (/sdcard/FUSE) is refused by a hard path guard** |
| Extraction (r1386) | The device's own tar is NEVER trusted: the bundled toybox tar extracts the rootfs with stderr captured to files; on failure the failing paths + the original errno text + the tool version are logged (and shown in the UI/agent result) |
| Post-extract audit (r1386) | `/bin` must be a real symlink → `usr/bin`, the tree must contain ≥ 300 symlinks (the real layer has 642), and `/etc/os-release`, `/usr/bin/bash`, `/usr/bin/apt-get` must exist — a silently-skipped symlink pass can never be reported as success |
| Verification gate | READY only after a real boot through PRoot returns `uid=0` + a Debian `/etc/os-release` line + `bash --version` + `apt-get --version`; every failure keeps the real error and state BROKEN — **a device whose storage denies symlink creation is refused BEFORE downloading anything and left NOT_INSTALLED with the exact reason, never half-installed** |
| Resumable install (r1386) | The verified archive stays in app storage; a retry re-hashes it and skips the download; the transferred archive is sha256-verified ON the device before extraction; every failure cleans its temp tree and the device tarball |
| PRoot runtime gate (r1387) | Debian is only downloaded AFTER PRoot itself proved it runs on the device: an **ELF dependency diagnostic** (machine/ABI, dynamic interpreter, DT_NEEDED, RUNPATH — parsed from the bundled binary before install) is logged; the bundled proot is exec-tested as `LD_LIBRARY_PATH=<runtime>/lib proot --version` with a **strict version-token gate** (no pipeline masking, linker error text can never pass again); then a **minimal-root smoke test** (a tiny root built from the bundled toybox: `proot -0 -r miniroot /bin/sh -c 'id; cat /etc/os-release'`; rebuilt stepwise in r1389 — see Smoke-root rebuild) must show fake-root `uid=0` + a readable os-release BEFORE the Debian archive is fetched |
| Runtime-failure honesty (r1387) | If PRoot cannot execute (e.g. `CANNOT LINK EXECUTABLE … library "libtalloc.so.2" not found` — the Android linker ignores the Termux RUNPATH, LD_LIBRARY_PATH is mandatory), the failing library name, the COMPLETE linker error and the ELF diagnostic are shown verbatim, and the state is set to **NOT_INSTALLED — never BROKEN**: Debian was never touched and the storage already passed the preflight |
| Extractor runtime gate (r1388) | The r1386 gate ran the multicall binary bare and piped it through `head`: toybox's NORMAL applet banner (`[ acpi arch ascii … ]`, rc=0) was misread as "the bundled extractor does not run on this device". Now: `toybox --version` with the REAL rc (no pipeline) + a strict version token, then an **end-to-end tiny-archive test** — regular file + directory + symlink + dangling symlink are packed with `toybox tar -cf` and extracted with `tar -xf` on the real runtime filesystem and verified BEFORE any Debian archive is touched. Service runtime evidence (`id`, cwd, LD_LIBRARY_PATH/PATH, extractor mode/size) is recorded first. Failures distinguish **EXEC_FAIL** (binary never ran: rc 126/127, permission/format/linker text) from **CAP_FAIL** (ran, but verification mismatch) and always carry the exact command, working directory, exit code, stdout and stderr verbatim — never a generic message |
| Smoke-root rebuild (r1389) | The r1387 smoke-root build escaped `$BASE` into the device shell — the service shell has no `BASE` variable, so `ln -sf toybox $BASE/miniroot/bin/$a` expanded to `/miniroot/bin/sh` and failed with `ln: cannot create symbolic link from 'toybox' to '/miniroot/bin/sh': No such file or directory` (reproduced byte-for-byte in CI with the bundled binary). The build is now stepwise with every mkdir/cp/chmod/ln/printf exit code checked: verified clean → merged-/usr tree (`/usr/bin` first, `/bin → usr/bin` as a RELATIVE symlink — `/bin` is never treated as a normal directory) → toybox exec-verified in place → **directory listing inspected before any link is created** → per-applet `ln` with fail-fast `LN_FAIL applet=<name>` → os-release written and read back → post-setup verification (`test -e`, `test -x`, `readlink -f` must resolve to `<miniroot>/usr/bin/toybox`, direct exec of `/bin/id` and `/bin/cat` through the `/bin` symlink). Second latent bug fixed: the bundled toybox 0.8.11 ships **no `sh` applet**, so the old `/bin/sh -c 'id; cat …'` smoke command could never exec (rc=127) — the smoke run now execs `/usr/bin/id` (→ `uid=0` fakeroot) and `/usr/bin/cat /etc/os-release` DIRECTLY under proot, no shell. Failures report the exact failing command, real rc, stdout, stderr AND the directory listing captured at the moment of failure (tree left in place for inspection); the smoke root is only removed after a full pass |
| Agent tools | `linux_env_status` (now includes the `fs_probe` result: path, fstype, write/symlink/hardlink, bundled extractor), `linux_env_install` (confirm=false → real pre-flight plan incl. symlink support; confirm=true → install+audit+verify), `linux_env_update`, `linux_env_reset`, `linux_env_remove`, `linux_env_acceptance_tests` (7 tests with real evidence) |
| UI | Linux environment page (status card, pre-flight, install/update/reset/remove, acceptance-test runner, honesty notes) + "Linux — install/manage" in the terminal env picker |
| Honesty contract | `id` inside the container shows uid=0 — that is PRoot FAKEROOT, not real root; the host identity stays shell uid 2000; Android limits are not bypassed; root-mode services are auto-dropped via `su 2000` wrapping so nothing ever runs as real root |
| Command safety | CommandValidator now knows apt/dpkg: read-only queries (`apt list/show/search`, `dpkg -l/-s`) = ALLOW; `apt update/install/upgrade`, `dpkg -i` = CONFIRM; the BLOCK tier applies inside the container too |

Acceptance tests (runnable in-app, one tap): `/etc/os-release` · bash · `id`/`pwd`/`uname -a` · `apt-get update` · install a tool + verify it runs · session keeps cwd after `cd` · stopping a long-running process (in-container kill + out-of-band session stop).

## What the agent can NOT do (by design)
- Root / privilege escalation: `su`, `sudo`, `magisk`, `setenforce` → BLOCK.
- Boot/recovery/partition damage: `reboot`, `fastboot`, `flash*`, `dd` to block devices, `mkfs`, `sm partition` → BLOCK.
- Kill the agent's own channel: `settings put global adb_enabled 0` → BLOCK.
- Framework suicide: bare `stop` / `start` → BLOCK.
- Raw binder attacks: `service call` → BLOCK.
- Factory wipe: `wipe`, `factory_reset` → BLOCK.

## The app's own permissions (AndroidManifest)
The aMiNo app itself stays minimal; the heavy lifting is delegated to the ADB shell identity:
`INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE(_SPECIAL_USE)`, `RECEIVE_BOOT_COMPLETED`,
`POST_NOTIFICATIONS`, `WRITE_SECURE_SETTINGS`, `REQUEST_INSTALL/DELETE_PACKAGES`,
`READ_MEDIA_*`, `NEARBY_WIFI_DEVICES`, `BIND_ACCESSIBILITY_SERVICE` (r1373 automation),
`INTERACT_ACROSS_USERS_FULL` + `USE_LOOPBACK_INTERFACE` (Shizuku/starter mechanics).
Nothing camera/location/mic is requested by the app itself — those capabilities flow through the
shell user and are always gated by the tier table above.
