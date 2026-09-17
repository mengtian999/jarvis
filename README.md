<p align="center">
  <a href="https://bitjarvis.chat">官网</a>
  · <a href="README_EN.md">English</a>
</p>

<h1 align="center">Jarvis</h1>

<p align="center"><strong>同一个贾维斯，不同的 Agent</strong></p>

<p align="center">一个多角色的端侧 AI Agent —— 手机里住着一群各有分工的 Ta。</p>

<p align="center">移动端仓库（iOS · Android）</p>

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platforms](https://img.shields.io/badge/Platforms-iOS%20%7C%20Android-lightgrey.svg)](#beta-programme)

GitHub: **[mengtian999/jarvis](https://github.com/mengtian999/jarvis)**

<a href="https://apps.apple.com/app/id6759188481">
  <img alt="Download on the App Store" height="48" src="assets/badge-appstore.svg" />
</a>
&nbsp;
<a href="https://github.com/mengtian999/jarvis/releases">
  <img alt="Get the APK on GitHub" height="48" src="assets/badge-android.svg" />
</a>

> **本仓库为移动端（iOS / Android）。** 桌面端在 <https://github.com/mengtian999/Bitjarvis>；移动端 IM 在独立仓库，并计划与移动端合为一个 App。

![Jarvis on iOS — deep research, chat, agent runtime, integrations, iCloud sync and granular permissions](assets/screenshots.png)

---

## Jarvis 是什么

Jarvis 是一个完全跑在你自己设备上的 AI Agent：模型（Claude / GPT / Gemini……）使用你自己的账号或 API key，数据留在本地；它还通过沙盒化的 Alpine Linux 环境（iOS 上是 iSH，Android 上是 PRoot）为模型解锁一台真实可用的工作电脑——可以安装软件、运行脚本、操作真实文件，再配合浏览器自动化、可扩展技能、持久记忆和深层系统集成。

免费，完全开源。

**我们相信，在 AI 时代，产品优势不再来自技术设计与代码本身。最好的 Agent 来自与用户紧密的反馈循环——用户的期待与报告，最终收敛为产品。**

## 多角色（Multi-Role）

Jarvis 已从「单 Agent」进化为**多角色**：一个 App 里可以同时拥有多个「角色」——每个角色就是一位独立的 Ta，有自己的名字、说话风格、语言、人格设定和头像。

- **各角色独立人格** — 管家、健康、工作、研究……每个角色都有自己的身份设定，用自己的方式说话、办事。
- **会话绑定角色** — 新建对话时选择一个角色，这个会话就由 Ta 负责；随时可以切换到另一个角色继续聊。
- **角色之间接力** — 一个角色没做完的事，可以把消息 / 任务转发给另一个角色，跨角色分工协作。
- **云端同步** — 角色（含头像与说话风格）通过 CloudKit 在你的设备之间保持一致，一处修改、全端生效。

## 它能做什么

| | |
|---|---|
| **自带模型** | Claude、GPT、Gemini 及其他提供商——用自己的 API key 或账号登录 |
| **真实 Linux 沙盒** | 沙盒化的 Alpine Linux 环境在设备上运行，Agent 能装包、跑脚本、操作真实文件 |
| **设备集成** | 健康、日历、提醒、联系人、HomeKit、蓝牙、剪贴板、媒体、闹钟……以工具形式开放给 Agent |
| **浏览器自动化** | Agent 可以替你上网并和网页交互 |
| **技能与记忆** | 可扩展技能库 + 跨会话的持久记忆 |
| **工作区** | 用 `jarvis://workspace/` 把工作拆成独立上下文 |
| **原生卸载** | 重活或平台专属任务交给原生代码，而不是在沙盒里硬跑 |

## 实际用来做什么

一些大家真正在用的场景：

- **拍张照记下营养** — Jarvis 识别菜品、估算热量与宏量营养，写进 Apple Health。
- **醒来先听时间线** — 快捷指令让 Jarvis 拉取你的 X 时间线、总结、合成语音，当闹钟叫你。
- **群聊变任务清单** — 从 Telegram 群里拉消息，抽 bug 和待办、去重，归档进 Apple Reminders。
- **挂上 Obsidian 仓库** — 研究、整理，再以普通工作区方式把 Markdown 笔记写回仓库。
- **随手分享成日程** — 从 iOS 分享面板把页面 / 消息交给 Jarvis，时间地点自动变成一条日历事件。
- **多位 Ta 接力** — 一个角色写方案草稿，转发给另一个角色审阅润色；出门换手机，角色和会话一个不少。

**→ [OpenMinis/AwesomeMinis](https://github.com/OpenMinis/AwesomeMinis)** — 社区贡献的用例与工作流合集（健康、生产力、研究、财务、开发工具……）。

## Skills

**Skill** 就是带 `SKILL.md` 的文件夹——指令，以及可选的脚本、参考资料与资源——请求命中时按需加载。元数据常驻上下文用于触发；正文与资源只在真正使用时才载入。

Jarvis 有自己的工具体系，但不要求技能必须为它专门编写：**为 Claude、Codex、OpenClaw 或 Hermes Agent 编写的技能一般可以直接运行在 Jarvis 中。** 适配了 Jarvis 工具的技能运行得更好——能直接触达 Linux 壳、设备集成和原生卸载。

**→ [OpenMinis/MinisSkills](https://github.com/OpenMinis/MinisSkills)** — 为 Jarvis 从零编写的技能，以及为它移植的技能，覆盖 TTS、搜索、媒体下载、健康分析、云端 API 等。

## 评价

> "the most impressive indie app I've seen in a while"
>
> — Federico Viticci, [**Open Minis Is the iOS Agent I Wish Siri AI Could Be**](https://www.macstories.net/reviews/open-minis-is-the-ios-agent-i-wish-siri-ai-could-be/),
> MacStories (July 2026)

> "在很大程度上实现甚至局部超越了 Apple Intelligence"
>
> — Ye Han, [**这可能是 iPhone 最强 Agent 软件，没有之一 丨Open Minis 入门指南**](https://zhuanlan.zhihu.com/p/2045570157783807562),
> 知乎 / Zhihu (June 2026)

> "可能是 iOS 端最强 AI Agent"
>
> — [**Open Minis：可能是 iOS 端最强 AI Agent**](https://www.appinn.com/open-minis/),
> 小众软件 / Appinn (March 2026)

## 路线图：与移动端 IM 合成一个 App

移动端 IM 与 Jarvis 即将合并为同一个 App：**真人和多角色 Agent 的对话将进入同一界面**，一个入口、一套同步、一段上下文。

桌面端已经打通了「内嵌 IM + Agent 互通」：侧栏内嵌 Jarvis IM，一个 Jarvis IM 账号对应每个 Agent 的 AppService 虚拟用户（如 `@jarvis_home3f2a`）。移动端合体后，多端体验将完全一致——手机替你在群里回话的、和桌面上帮你写报告的，是同一群 Ta。

## Beta 计划

App Store 的发布常常滞后：每次更新都要等审核，稳定性不足时我们也会压住版本。TestFlight 版本总是最先落地新功能和修复。

**→ [加入 TestFlight beta](https://testflight.apple.com/join/3BdkA5c3)**

Android 的 APK 始终在 [releases 页面](https://github.com/mengtian999/jarvis/releases) 提供最新版。

---

## 从源码构建

Jarvis 在 App 里内置一套 Linux 沙盒，因此原生依赖（iOS 的 iSH、Android 的 PRoot、FFmpeg、LAME）与 Alpine rootfs 都**从源码构建**，而不是提交二进制。

**→ 完整首次构建指南见 [BUILDING.md](BUILDING.md)。**

简明版本：

```sh
git clone --recurse-submodules https://github.com/mengtian999/jarvis.git
cd jarvis

# iOS  — 注意顺序：FFmpeg 链接 LAME
./deps/build_lame.sh && ./deps/build_ffmpeg.sh
./deps/build_ish.sh && ./deps/prepare_alpine_rootfs.sh
open src/ios/Minis.xcodeproj

# Android — 需要 NDK r28+
./deps/build_proot.sh && ./scripts/prepare_android_sandbox.sh
cd src/android && ./gradlew :app:assembleDebug
```

`BUILDING.md` 覆盖每个平台的工具链要求、构建期定制模板，以及最可能遇到的故障排查。

---

## 仓库结构

```
src/ios/          iOS 应用（Swift / SwiftUI）+ 分享、Widget 与文件提供方扩展
src/android/      Android 应用（Kotlin / Compose）+ JNI 原生代码
src/shared/       两平台共享的资源
deps/             原生依赖构建脚本与托管源码
docs/specs/       架构与接口规范
scripts/          Rootfs 准备与开发工具
```

---

## 致谢

Jarvis 站在大量开源工作的肩膀上。感谢这些项目的维护者——完整目录（含版本与许可条款）见 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。

**沙盒——产品的核心：**

- **[iSH](https://github.com/ish-app/ish)** (GPLv3) — iOS 上的 Linux 用户态模拟，我们运行 [ARM64 fork](https://github.com/OpenMinis/ish-arm64)。
- **[PRoot](https://github.com/termux/proot)** (GPLv2) — Android 沙盒的用户态 chroot，[我们的 fork](https://github.com/OpenMinis/proot)，底层依赖 **[talloc](https://talloc.samba.org)** (LGPLv3+)。
- **[Alpine Linux](https://alpinelinux.org)** — 沙盒启动的 minirootfs。

**媒体与文本** — [FFmpeg](https://ffmpeg.org) (LGPL-2.1+)、[LAME](https://lame.sourceforge.io) (LGPL)、[cppjieba](https://github.com/yanyiwu/cppjieba) (MIT)、[KaTeX](https://katex.org) (MIT)。

**iOS** — [SwiftAnthropic](https://github.com/jamesrochabrun/SwiftAnthropic)、[SwiftMath](https://github.com/mgriebling/SwiftMath)、[RealTimeCutVADLibrary](https://github.com/helloooideeeeea/RealTimeCutVADLibrary)（均 MIT）、[swift-cmark](https://github.com/swiftlang/swift-cmark) (BSD-2-Clause)，以及 Apple / Swift Server Workgroup 包（Apache-2.0）。

**Android** — [AndroidX & Jetpack Compose](https://developer.android.com/jetpack)、[OkHttp](https://square.github.io/okhttp/)、[Coil](https://coil-kt.github.io/coil/)、[kotlinx](https://github.com/Kotlin) 序列化与协程、[multiplatform-markdown-renderer](https://github.com/mikepenz/multiplatform-markdown-renderer)、[Reorderable](https://github.com/Calvin-LL/Reorderable)、[ACRA](https://github.com/ACRA/acra)（均 Apache-2.0）、[Shizuku](https://github.com/RikkaApps/Shizuku-API) (MIT)。

---

## 许可证

Jarvis 以 **[GNU General Public License v3.0](LICENSE)** 授权。

应用链接了 GPL 组件——[iSH](https://github.com/OpenMinis/ish-arm64) (GPLv3) 与 [PRoot](https://github.com/OpenMinis/proot) (GPLv2)——因此整体作品以 GPLv3 分发。打包的第三方许可证列表见 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。

---

## 社区

- **Telegram**: [加入群组](https://t.me/+2NzhOJuzRyI1YmM1)
- **Issues**: 通过 [GitHub Issues](https://github.com/mengtian999/jarvis/issues) 提交 bug、功能请求与讨论

本仓库是私有开发树的镜像，因此**不接受 pull request**——没有地方让它们落地。Issues 是塑造产品的方式；[AwesomeMinis](https://github.com/OpenMinis/AwesomeMinis) 与 [MinisSkills](https://github.com/OpenMinis/MinisSkills) 都接受贡献。参见 [CONTRIBUTING.md](CONTRIBUTING.md)。