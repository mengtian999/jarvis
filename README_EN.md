<p align="center">
  <a href="https://bitjarvis.chat">Official Site</a>
  · <a href="README.md">中文</a>
</p>

<h1 align="center">Jarvis</h1>

<p align="center"><strong>One Jarvis, different agents</strong></p>

<p align="center">A multi-role, on-device AI agent — a whole cast of helpers living in your pocket.</p>

<p align="center">Mobile repository (iOS · Android)</p>

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platforms](https://img.shields.io/badge/Platforms-iOS%20%7C%20Android-lightgrey.svg)](#beta-programme)

GitHub: **[mengtian999/jarvis](https://github.com/mengtian999/jarvis)**


&nbsp;
<a href="https://github.com/mengtian999/jarvis/releases">
  <img alt="Get the APK on GitHub" height="48" src="assets/badge-android.svg" />
</a>

> **This repository is the mobile client (iOS / Android).** The desktop client lives at <https://github.com/mengtian999/Bitjarvis>; the mobile IM client is in its own repository and is planned to merge into this app.


---

## What is Jarvis

Jarvis is an AI agent that runs entirely on your own device: models (Claude / GPT / Gemini…) use your own account or API key, and data stays local. It also gives the model a real, usable work computer through a sandboxed Alpine Linux environment (iSH on iOS, PRoot on Android) — install software, run scripts, work with real files — combined with browser automation, extensible skills, persistent memory, and deep system integration.

Free, and fully open source.

**We believe that in the age of AI, technical design and code are no longer
where a product's advantage lies. The best agent emerges from a tight feedback
loop with the people who use it — their expectations and their reports are
what converge on the product.**

## Multi-Role

Jarvis has evolved from a *single agent* to **multiple roles**: one app can hold many "roles" at once — each role is an independent agent with its own name, speaking style, language, personality, and avatar.

- **Independent personalities** — a butler, a health coach, a work assistant, a researcher… each role has its own identity and does things its own way.
- **Sessions bound to a role** — pick a role when you start a chat and that session belongs to them; switch to another role whenever you like.
- **Handoffs between roles** — when one role is halfway through something, forward the messages / task to another role for cross-role collaboration.
- **Cloud sync** — roles (including avatar and style) stay consistent across your devices via CloudKit — change once, updated everywhere.

## What it does

| | |
|---|---|
| **Bring your own model** | Claude, GPT, Gemini and other providers, via your own API keys or account sign-in. |
| **A real Linux shell** | A sandboxed Alpine Linux environment runs on-device — the agent can install packages, run scripts, and work with real files. |
| **Device integration** | Health, Calendar, Reminders, Contacts, HomeKit, Bluetooth, Clipboard, Media, Alarms and more, exposed to the agent as tools. |
| **Browser automation** | The agent can browse and interact with the web on your behalf. |
| **Skills & memory** | Extensible skills plus persistent memory across sessions. |
| **Workspaces** | Organise work into separate contexts, addressable via `jarvis://workspace/`. |
| **Native offloads** | Heavy or platform-specific work is handed to native code instead of the sandbox. |

## What you can do with Jarvis

A few things people actually use it for:

- **Photograph a meal, log the nutrition** — Jarvis identifies the dishes, estimates calories and macros, and writes them to Apple Health.
- **Wake up to your timeline** — a shortcut makes Jarvis fetch your X timeline, summarise it, synthesise speech, and play it as your alarm.
- **Turn group chatter into tasks** — pull messages from a Telegram group, extract bugs and action items, deduplicate them, and file them into Apple Reminders.
- **Mount your Obsidian vault** — research, clean up and write Markdown notes back into the vault as a normal workspace.
- **Share anything into a calendar event** — send a page or message to Jarvis via the iOS Share Sheet and it creates the event, time and place included.
- **Hand off between your agents** — one role drafts a proposal, forwards it to another role for review and polish; leave home, switch devices — roles and sessions all stay with you.



## Skills

A **skill** is a folder with a `SKILL.md` file — instructions, and optionally scripts, references and assets — that the agent loads on demand when a request matches it. Metadata stays in context for triggering; the body and bundled resources load only when the skill is actually used.

Jarvis has its own tool system, but it does not require skills written specifically for it: **skills built for Claude, Codex, OpenClaw or Hermes Agent generally run in Jarvis as-is.** Skills adapted to Jarvis' tools simply run better — they can reach the Linux shell, device integrations and native offloads directly.




## Roadmap: merging with the mobile IM into one app

The mobile IM client and Jarvis are about to merge into a single app: **conversations with real people and with your multi-role agents will live in one interface** — one entry point, one sync, one context.

On the desktop this is already wired up — "embedded IM + agent interop": an embedded Jarvis IM in the sidebar, where one Jarvis IM account maps to an AppService virtual user per agent (e.g. `@jarvis_home3f2a`). After the merge, the experience will be identical across devices — the same cast that answers your group chats on your phone is the one writing your reports on your desktop.

## Beta programme

App Store releases can lag behind: every update waits on review, and we hold builds back when stability warrants it. The TestFlight build is where fixes and new features land first.

**→ [Join the TestFlight beta](https://testflight.apple.com/join/3BdkA5c3)**

On Android, the [releases page](https://github.com/mengtian999/jarvis/releases) always carries the latest APK.

---

## Building from source

Jarvis ships a Linux sandbox inside the app, so the native dependencies (iSH on iOS, PRoot on Android, FFmpeg, LAME) and the Alpine rootfs are **built from source** rather than committed as binaries.

**→ See [BUILDING.md](BUILDING.md) for the full first-build guide.**

The short version:

```sh
git clone --recurse-submodules https://github.com/mengtian999/jarvis.git
cd jarvis

# iOS  — order matters: FFmpeg links against LAME
./deps/build_lame.sh && ./deps/build_ffmpeg.sh
./deps/build_ish.sh && ./deps/prepare_alpine_rootfs.sh
open src/ios/Minis.xcodeproj

# Android — needs NDK r28+
./deps/build_proot.sh && ./scripts/prepare_android_sandbox.sh
cd src/android && ./gradlew :app:assembleDebug
```

`BUILDING.md` covers the toolchain requirements per platform, the build-time customization templates, and a troubleshooting section for the failure modes you are most likely to hit.

---

## Repository layout

```
src/ios/          iOS app (Swift / SwiftUI) + share, widget and file-provider extensions
src/android/      Android app (Kotlin / Compose) + JNI native code
src/shared/       Assets shared by both platforms
deps/             Native dependency build scripts and vendored sources
docs/specs/       Architecture and interface specifications
scripts/          Rootfs preparation and developer tooling
```

---

## Acknowledgements

Jarvis stands on a great deal of open-source work. Our thanks to the maintainers of these projects — the full inventory, with versions and license terms, is in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).

**The sandbox — the heart of the product:**

- **[iSH](https://github.com/ish-app/ish)** (GPLv3) — Linux usermode emulation on iOS. We run [an ARM64 fork](https://github.com/OpenMinis/ish-arm64).
- **[PRoot](https://github.com/termux/proot)** (GPLv2) — user-space chroot for the Android sandbox, via [our fork](https://github.com/OpenMinis/proot); **[talloc](https://talloc.samba.org)** (LGPLv3+) underpins it.
- **[Alpine Linux](https://alpinelinux.org)** — the minirootfs the sandbox boots.

**Media & text** — [FFmpeg](https://ffmpeg.org) (LGPL-2.1+), [LAME](https://lame.sourceforge.io) (LGPL), [cppjieba](https://github.com/yanyiwu/cppjieba) (MIT), [KaTeX](https://katex.org) (MIT).

**iOS** — [SwiftAnthropic](https://github.com/jamesrochabrun/SwiftAnthropic), [SwiftMath](https://github.com/mgriebling/SwiftMath), [RealTimeCutVADLibrary](https://github.com/helloooideeeeea/RealTimeCutVADLibrary) (all MIT), [swift-cmark](https://github.com/swiftlang/swift-cmark) (BSD-2-Clause), and the Apple / Swift Server Workgroup packages (Apache-2.0).

**Android** — [AndroidX & Jetpack Compose](https://developer.android.com/jetpack), [OkHttp](https://square.github.io/okhttp/), [Coil](https://coil-kt.github.io/coil/), [kotlinx](https://github.com/Kotlin) serialization & coroutines, [multiplatform-markdown-renderer](https://github.com/mikepenz/multiplatform-markdown-renderer), [Reorderable](https://github.com/Calvin-LL/Reorderable), [ACRA](https://github.com/ACRA/acra) (all Apache-2.0), and [Shizuku](https://github.com/RikkaApps/Shizuku-API) (MIT).

---

## License

Jarvis is licensed under the **[GNU General Public License v3.0](LICENSE)**.

The app links GPL-licensed components — [iSH](https://github.com/OpenMinis/ish-arm64) (GPLv3) and [PRoot](https://github.com/OpenMinis/proot) (GPLv2) — so the combined work is distributed under GPLv3. Bundled third-party licenses are listed in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).

---

## Community

- **Telegram**: [Join the group](https://t.me/+2NzhOJuzRyI1YmM1)
- **Issues**: Bug reports, feature requests and discussion via [GitHub Issues](https://github.com/mengtian999/jarvis/issues)
