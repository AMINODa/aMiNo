# aMiNo TikTok Like Hider

LSPatch/Xposed module (no root) that hides TikTok UI elements: like button, counts,
comment/share/bookmark buttons, follow button, username, caption, music.

**Package:** `com.amino.tiktoklikehider` — **Scope:** `com.zhiliaoapp.musically`, `com.zhiliaoapp.musically.lite`

## Versions

| Tag | Features |
| --- | --- |
| `tiktok-like-hider-v1.0` | First build: hides like button (verified in the field). |
| `tiktok-like-hider-v1.1` | Settings UI (11 categories, Arabic RTL), launcher icon, config broadcast. |
| `tiktok-like-hider-v1.2` | ConfigProvider channel (settings survive restart), numeric-sibling count hiding, French/Arabic labels, CAND diagnostics in LSPatch log. |
| `tiktok-like-hider-v1.3` | **Persistent diagnostics**: the hook inside TikTok broadcasts every CAND/HID/INFO line to the module app where `DiagReceiver` stores them in `filesDir/amino_diag.txt`; new `DiagActivity` screen (refresh / copy all / share / clear) — replaces the ephemeral LSPatch log view. Plus: Arabic definite-article label matching («التعليقات»، «المشاركة»…) with normalized contains, neg-token guard, receiver exported fix (config broadcasts were blocked on Android 13+ by `RECEIVER_NOT_EXPORTED`), raw-config logging, `diag` flag in config JSON, obfuscated-id candidate budget (800 unique views), launcher icon restored. |

## Build

```
scripts/build_like_hider.sh v1.3
```

Pipeline: `aapt2` (resources+manifest+assets) → ECJ (app sources + compile-only Xposed
stubs) → `d8` (stubs excluded from dex) → insert `classes.dex` → `zipalign` → `apksigner`
(`amino/signing/debug.keystore`, SHA-256 `32b42ac8…04bbce`).

Requires: `/home/z/android-sdk` (platform-33 + build-tools-34) and `/home/z/dl/ecj.jar`.

## Architecture

- `MainHook` — hooks `View.dispatchAttachedToWindow`, `View.setVisibility` (re-hide guard),
  `View.setContentDescription`, `ContextWrapper.attachBaseContext` (config load + receiver).
- `Config` — 11 category `Spec`s: id/class token matching + multilingual content-description
  labels; numeric-count detection for sibling TextViews.
- `ConfigProvider` — settings JSON served to TikTok at every process start.
- `DiagReporter` (in TikTok process) → broadcast `ACTION_DIAG` →
  `DiagReceiver` (module app) → `filesDir/amino_diag.txt` → `DiagActivity` viewer.

## Settings delivery order

1. ContentProvider `call("get")` at TikTok process start (primary, works with module app closed)
2. Cache file inside TikTok `filesDir/amino_like_hider_config.json` (fallback)
3. `XSharedPreferences` (legacy fallback)
4. Live `ACTION_CONFIG_UPDATE` broadcast from the module app (exported receiver since v1.3)
