# Minis → Jarvis Rename Map

**Status:** Authoritative reference for the Minis → Jarvis rename.
**Scope:** Full cut-over. Jarvis is a brand-new application; no legacy-user
data compatibility is required. Every `minis` identifier becomes `jarvis`.

This document is the single source of truth for the rename. Every participant
must apply **exactly** these mappings — do not improvise variants. When a
mapping is applied, check its box. When a whole category is done, mark the
section.

A companion scanner lives at `scripts/scan_jarvis_residue.py`. Run it after
each stage to verify no `minis` residue remains in the layers already migrated.

---

## Layer 1 — User-visible brand strings (`Minis` → `Jarvis`)  ✅ STAGE 1 COMPLETE

Display text shown to end users. Safe to change; no behavioral impact.

Applied via `scripts/rename_brand_strings.py` (mask-protected rewriter) +
hand edits for `Info.plist`, `settings.gradle.kts`, `bashism_rules.json`,
`network_security_config.xml`. Verified clean with
`scripts/scan_jarvis_residue.py --stage 1` — remaining hits are all
Layer 2/3/4 identifiers (`minis://`, `/var/minis`, `Minis.xcodeproj`,
`com.openminis`, `Minis.app`) or meta-files (this map, the scripts), which
are intentionally out of scope for Stage 1.

| Location | Original | New | Done |
|---|---|---|---|
| iOS `AboutView.swift` `Text("Minis")` | `Minis` | `Jarvis` | ☑ |
| iOS `ContentView.swift` sidebar fallback (4 sites) | `"Minis"` | `"Jarvis"` | ☑ |
| iOS `ContentView.swift` export role label | `"Minis"` | `"Jarvis"` | ☑ |
| iOS `SoulSettingsView.swift` placeholder | `"Minis"` | `"Jarvis"` | ☑ |
| iOS `SoulStore.swift` default SOUL.md `name:` | `"Minis"` | `"Jarvis"` | ☑ |
| iOS `MinisUserAgent.swift` UA string `Minis/<ver>` | `Minis/` | `Jarvis/` | ☑ |
| iOS `Info.plist` URL name `Minis Share` | `Minis Share` | `Jarvis Share` | ☑ |
| iOS permission strings | already `Jarvis` | — | ☑ |
| Android `strings.xml` brand word (default + 8 `values-*`) | `Minis` / `关于 Minis` … | `Jarvis` / `关于 Jarvis` / `贾维斯` … | ☑ |
| Android `AgentForegroundService.kt` notif title | `"Minis"` | `"Jarvis"` | ☑ |
| Android `ToolOverlayController.kt` tool label fallback | `"Minis"` | `"Jarvis"` | ☐ |
| Android `ClipboardOffloadHandler.kt` clip label | `"Minis"` | `"Jarvis"` | ☐ |
| Android `AccessibilityOffloadHandler.kt` help text | `Minis` | `Jarvis` | ☐ |
| Android `AndroidManifest.xml` `android:label` refs | `@string/app_name` | unchanged (already Jarvis) | ☑ |
| Docs `README.md` / `BUILDING.md` / `CONTRIBUTING.md` brand prose | `OpenMinis` / `Minis` | `Jarvis` | ☐ |

## Layer 2 — URL schemes & deep links  ✅ STAGE 2.1 COMPLETE

Applied via `scripts/rename_url_scheme.py` (mask-protected rewriter). Three
substitutions: `minis://`→`jarvis://`, bare `"minis"` scheme literal→`"jarvis"`,
`minis-mcp`→`jarvis-mcp`. `minis-mcp-cli`/`minis-mcp-daemon` (Layer 3 CLI)
and `com.openminis.app` (Layer 4 Bundle-ID scheme) were masked and survived.
92 files written, 0 errors.

| Original | New | Done |
|---|---|---|
| `minis://` | `jarvis://` | ☑ |
| `minis-mcp` (OAuth callback scheme) | `jarvis-mcp` | ☑ |
| iOS `Info.plist` `CFBundleURLSchemes` | `minis`, `minis-mcp`, `com.openminis.app` | `jarvis`, `jarvis-mcp`, `com.jarvis.app`¹ | ☑ |
| Android `AndroidManifest.xml` `<data android:scheme>` | `minis`, `minis-mcp` | `jarvis`, `jarvis-mcp` | ☑ |
| iOS `BrowserUseManager.swift` `forURLScheme:"minis"` (×3 files) | `minis` | `jarvis` | ☑ |
| iOS `AIChatViewModel+RequestBudget.swift` `url.scheme == "minis"` | `minis` | `jarvis` | ☑ |
| Android `DeepLinkHandler.kt` `uri.scheme != "minis"` | `minis` | `jarvis` | ☑ |
| Android `BrowserUseManager.kt` `url.scheme != "minis"` | `minis` | `jarvis` | ☑ |
| Android `MinisOpenUrlBroker.kt` scheme allow-list | `minis` | `jarvis` | ☑ |

¹ `com.openminis.app` scheme intentionally left for Stage 3 (Bundle ID rename);
  it will become `com.jarvis.app` when the Bundle ID changes.


## Layer 3 — Sandbox paths & offload CLI names

Behavioral identifiers the agent itself depends on. Must be changed
atomically with system prompts, skills, and rootfs images.

| Original | New | Done |
|---|---|---|
| `/var/minis/` (sandbox mount root) | `/var/jarvis/` | ☑ ² |
| `Library/MinisChat` (iOS persistent dir) | `Library/JarvisChat` | ☐ |
| `MinisChat/minis/<sessionId>/` | `JarvisChat/jarvis/<sessionId>/` | ☐ |
| `MinisChat/minis.db` | `JarvisChat/jarvis.db` | ☐ |
| `MinisFileProvider` (App Group subdir) | `JarvisFileProvider` | ☐ |
| `MinisConfig` (App Group subdir) | `JarvisConfig` | ☐ |
| `minis-config` (offload CLI) | `jarvis-config` | ☑ ³ |
| `minis-mcp-cli` (CLI + `usr/local/lib/minis-mcp-cli/` dir) | `jarvis-mcp-cli` | ☑ ³ |
| `minis-browser-use` | `jarvis-browser-use` | ☑ ³ |
| `minis-model-use` | `jarvis-model-use` | ☑ ³ |
| `minis-sessions-cli` | `jarvis-sessions-cli` | ☑ ³ |
| `minis-debug` | `jarvis-debug` | ☑ ³ |
| `minis-scheduled` | `jarvis-scheduled` | ☑ ³ |
| `/tmp/minis-mcp-daemon.port` | `/tmp/jarvis-mcp-daemon.port` | ☑ ³ |
| `minis.db` (Android database) | `jarvis.db` | ☑ ³ |
| PS1 `root@minis:` | `root@jarvis:` | ☑ ² |
| rootfs build scripts | `minis` | `jarvis` | ☑ ² |

² Stage 2.2 done via `scripts/rename_sandbox_paths.py` — 147 source files,
848 `/var/minis`→`/var/jarvis` replacements incl. rootfs profile scripts and
`deps/prepare_alpine_rootfs.sh`. **The Alpine minirootfs must be regenerated
before the app can run** (run `deps/prepare_alpine_rootfs.sh` +
`scripts/prepare_android_sandbox.sh` on a Linux/macOS host, then rebuild iSH
and proot). Until then the agent shell cannot find its `/var/jarvis` mount
points. Remaining `/var/minis` hits live only inside the `minis-mcp-cli` tool
dir (renamed atomically in Stage 2.3) and build-output caches.

³ Stage 2.3 done via `scripts/rename_offload_cli.py` — 125 Android files, 627
offload-CLI replacements + 3 filesystem renames (`minis-mcp-cli`→`jarvis-mcp-cli`
in both `bin/` and `lib/`, `minis-open`→`jarvis-open`). `minis.db`→`jarvis.db`
(Android DB name) included. Android `compileDebugKotlin` passes (exit 0).
iOS `MinisChat`/`MinisFileProvider`/`MinisConfig` (rows 79-83) are iOS-only
and deferred per the user's "skip iOS" instruction.

## Layer 4 — System identifiers (Bundle ID / App Group / project names)

Changing these makes Jarvis a distinct app from Minis on every device.

| Original | New | Done |
|---|---|---|
| iOS Bundle ID `com.openminis.app` | `com.jarvis.app` | ☐ |
| iOS ext IDs `.ShareExtension` / `.FileProvider` / `.AgentWidget` | `com.jarvis.app.*` | ☐ |
| iOS test IDs `com.openminis.MinisTests` / `MinisUITests` | `com.jarvis.JarvisTests` / `JarvisUITests` | ☐ |
| App Group `group.com.openminis.app` | `group.com.jarvis.app` | ☐ |
| BGTask `com.openminis.app.liveactivity-refresh` | `com.jarvis.app.liveactivity-refresh` | ☐ |
| Android `applicationId` / `namespace` `com.openminis.app` | `com.jarvis.app` | ☑ ⁴ |
| Android Kotlin package dir `com/openminis/app/` | `com/jarvis/app/` | ☑ ⁴ |
| Gradle `rootProject.name = "Minis"` | `"Jarvis"` | ☑ |
| Xcode `Minis.xcodeproj` → `Jarvis.xcodeproj` | — | ☐ |
| `Minis.entitlements` → `Jarvis.entitlements` | — | ☐ |
| `Minis.app` / `MinisShare.appex` / `MinisFileProvider.appex` product names | `Jarvis*` | ☐ |

⁴ Stage 3 (Android) done via `scripts/rename_bundleid_android.py` — 529 files,
2975 `com.openminis.app`→`com.jarvis.app` replacements + full source-tree move
(`com/openminis/app`→`com/jarvis/app` in main/test/androidTest; 50 dir entries).
`compileDebugKotlin` passes (exit 0). The Android manifest's relative `.Xxx`
names resolve against the new namespace automatically. iOS Bundle ID / App
Group / Xcode project (rows 118-122, 126-128) are deferred per the user's
"skip iOS" instruction.

## Layer 5 — Code symbols (DEFERRED)

Class / property names carrying `Minis`. **Not changed in this pass** —
deferred to a later refactor-only PR to keep git history reviewable and to
avoid entangling cosmetic renames with behavioral ones.

Examples (record only): `MinisApp`, `MinisURLSchemeHandler`, `MinisImageProvider`,
`MinisOpenUrlBroker`, `MinisMarkdownParser`, `MinisShareSheet`, `AskMinisIntent`,
`MinisAccessibilityService`, `MinisNotificationListenerService`, `resolveMinisURL`,
`minisAppGroupRoot`, `minisConfigRoot`, `sharedMinisSchemeHandler`,
`ensureMinisSymlinks`, `MinisFsRouter`, `MinisUserAgent`, `MinisMediaViews`,
`MinisDebugLogReader`, `MinisShortcutsProvider`, `MinisConfigPermissionStore`,
`MinisURLPathDecoding`.

## Layer 6 — External assets (parallel, non-blocking)

| Item | Done |
|---|---|
| Website `openminis.app` → `jarvis.app` (or chosen domain) | ☐ |
| GitHub org `OpenMinis` → new org; submodule URLs in `.gitmodules` | ☐ |
| `AwesomeMinis` / `MinisSkills` repos | ☐ |
| App Store Connect: new Jarvis app + Bundle ID cert + App Groups cap | ☐ |
| `assets/` badges & screenshots | ☐ |
| `THIRD_PARTY_LICENSES.md` self-references | ☐ |

---

## v1.13 sync (2026-09-03)

Merged upstream OpenMinis v1.13 (`09fc199..4ef2900`, +483 files / ~107k
lines: backup & restore to folders + rclone remotes, provider/thinking/
storage work, chat & markdown & theme, iSH submodule advance, locale
helper scripts) onto the Jarvis fork (base = `09fc199`, v1.12).

- All v1.13 content was re-run through the rename pipeline
  (brand / URL scheme / sandbox paths / runtime ids / dotted ids /
  bundle id) in LF-normalized space. 189 of 209 conflicts auto-merged
  after normalization; the rest were resolved by hand.
- Upstream refactored `ChatScreen` to own its `ChatViewModel` (callback
  parameters removed from `AppNavigation`). The roles chat wiring was
  RE-PORTED onto the v1.13 architecture: the role-picker / forward sheets
  render block is back in `ChatScreen`, and `onNewChatWithRole` is
  threaded `AppNavigation → ChatSplitScaffoldRoute → ChatScreen`
  (role-bound draft route `__new__<uuid>__role__<roleId>`). Verified by
  `:app:compileDebugKotlin` / `:app:compileDebugUnitTestKotlin` — both
  green.
- Android DB: the fork schema reset stands (`version = 1`,
  `fallbackToDestructiveMigration`); upstream's migration chain,
  exported schema json and `MessageAttributionMigrationTest` were
  dropped accordingly.
- iOS: `minis.*` runtime ids and `minis.db` intentionally unchanged
  (Layer 3/4 deferred); `Info.plist` rebuilt from v1.13 + scheme
  renames (`jarvis`, `jarvis-mcp`, `com.jarvis.app`); BGTask ids and
  the upstream backup scheme `com.openminis.app.jarvisbak` kept to match
  the (unrenamed) code side.
- iSH submodule advanced to the 1.13 build's commit; its `.gitmodules`
  branch now tracks `master`.

---

## Execution order

1. **Stage 0** — this doc + `scripts/scan_jarvis_residue.py`. Baseline build green.
2. **Stage 1** — Layer 1 (brand strings). Zero risk. Verify app name shows Jarvis.
3. **Stage 2** — Layer 2 + Layer 3 (protocols & paths), per subsystem:
   2.1 `minis://` scheme · 2.2 `/var/minis/` + rootfs regen · 2.3 offload CLI · 2.4 `MinisChat` dirs + App Group subdirs.
4. **Stage 3** — Layer 4 (Bundle ID / App Group / project). Last; breaks old debug installs.
5. **Stage 4** — Layer 6 (external), in parallel.
6. **Stage 5** — Layer 5 (code symbols), deferred.

## High-risk verification (pre-launch checklist)

- [ ] iSH / PRoot sandbox boots; `shell_execute` returns exit 0 (not just "starts").
- [ ] `jarvis://` renders in Markdown (WKURL scheme handler wired in all 3 iOS files).
- [ ] Deep links open Settings on iOS (Safari tap) and Android (`am start -d jarvis://settings`).
- [ ] Anthropic OAuth redirect uses `com.jarvis.app`; MCP callback uses `jarvis-mcp`.
- [ ] Alpine minirootfs regenerated (`prepare_*` scripts); `unzip -l *.apk | grep libproot` shows 3 `.so`.
- [ ] FileProvider exposes "On My iPhone → Jarvis"; no stale Minis entry.
- [ ] AccessibilityService / NotificationListener labels correct in system Settings.
- [ ] CloudKit schema self-consistent; first sync clean.
- [ ] All 8 `values-*` localized strings free of `Minis`.
- [ ] Instrumented tests green with updated `/var/jarvis` assertions.

