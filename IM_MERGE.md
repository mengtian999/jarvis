# 贾维斯合并：Agent + IM → 一个 App

> [T-im-merge] 将移动端 IM（`im/`，Flutter / FluffyChat fork，包名 `bitjarvis`）
> 以 **Flutter module（add-to-app）** 形式嵌入原生 Agent App（iOS: SwiftUI
> `src/ios`，Android: Jetpack Compose `src/android`）。
> 打开 App 首先是 Agent；右上角图标进入 IM；IM 内可一键返回 Agent。

## 入口一览

| 方向 | 位置 | 图标 | 实现 |
|---|---|---|---|
| Agent → IM | Agent 会话列表**右上角**（iOS: `ContentView.swift` sidebarToolbarContent 最右；Android: `SessionListScreen.kt` TopAppBar actions 最右） | 浅色 `imq.png` / 深色 `ims.png`（Android: `drawable/im_entry_light.png` + `drawable/im_entry_dark.png`，按 `ChatColors.isDark` 在代码中选取——不能用 drawable-night 限定符，因其只跟随系统 uiMode 而非 App 的应用内主题；iOS: `IMEntry.imageset` 按 luminosity 外观自动切换） | 缓存引擎的 FlutterActivity / FlutterViewController |
| IM → Agent | IM 对话列表右下角，**「新的对话」按钮正上方** | `jarvis.png`（`im/assets/jarvis.png`） | `im/lib/pages/chat_list/jarvis_entry_fab.dart`，经 MethodChannel `jarvis.im/agent_bridge` 调 `exitToAgent`，原生侧收起 IM |

「贾维斯」文字与「新的对话」**完全同步**展开/收起：列表滚到顶部时一起展开
（同款 `AnimatedSize` 动效），下滑离开顶部时一起收起为圆形图标。
文案走 IM 的 l10n：`jarvis` 词条 —— 中文「贾维斯」、繁体/粤语「賈維斯」、
日语「ジャーヴィス」、韩语「자비스」、俄语「Джарвис」，其余语言 "Jarvis"
（57 个 `intl_*.arb` 已全部写入；`lib/l10n/l10n*.dart` 已同步手改，
下次 `flutter gen-l10n` 会按 arb 重新生成并保持一致）。

## 首次集成步骤

1. **生成 Flutter module 临时工程**（`im/pubspec.yaml` 已声明
   `flutter: module: androidPackage: chat.bitjarvis.im`）：

   ```bash
   cd im
   flutter pub get     # 生成 .android/ 与 .ios/
   ```

2. **Android（Windows/macOS/Linux 均可）**：无需额外步骤——
   `src/android/settings.gradle.kts` 已通过
   `apply(from = File(settingsDir, "../im/.android/include_flutter.groovy"))`
   引入 `:flutter` 与各插件工程，`app/build.gradle.kts` 已依赖
   `implementation(project(":flutter"))`。直接：

   ```bash
   cd src/android
   ./gradlew :app:assembleDebug
   ```

3. **iOS（需 macOS）**：`src/ios/Podfile` 已配置 `flutter_application_path`：

   ```bash
   cd src/ios
   pod install
   # 之后用 Minis.xcworkspace（而非 Minis.xcodeproj）打开构建
   ```

## 关键文件

**IM（Flutter）**
- `im/lib/pages/chat_list/jarvis_entry_fab.dart` —— 贾维斯入口 FAB（新增）
- `im/lib/pages/chat_list/chat_list_view.dart` —— FAB 区改为「贾维斯在上、新的对话在下」
- `im/lib/utils/agent_bridge.dart` —— `exitToAgent` 通道（新增）
- `im/pubspec.yaml` —— 新增 `module:` 声明
- `im/lib/l10n/intl_*.arb` + `im/lib/l10n/l10n*.dart` —— `jarvis` 词条

**Agent Android**
- `src/android/settings.gradle.kts` —— Flutter module 集成 + `PREFER_PROJECT`
- `src/android/app/build.gradle.kts` —— `implementation(project(":flutter"))`
- `src/android/app/src/main/java/com/jarvis/app/im/ImFlutterEngine.kt` —— 引擎懒预热/缓存（新增）
- `src/android/app/src/main/java/com/jarvis/app/im/ImFlutterActivity.kt` —— IM 宿主 Activity + 退出通道（新增）
- `src/android/app/src/main/AndroidManifest.xml` —— 注册 ImFlutterActivity
- `src/android/app/src/main/java/com/jarvis/app/ui/sessions/SessionListScreen.kt` —— 右上角 IM 按钮（`onImClick`）
- `src/android/app/src/main/java/com/jarvis/app/ui/navigation/ChatSplitScaffold.kt` —— 接线启动 IM
- `res/drawable/ic_im_entry.png`（imq）+ `res/drawable-night/ic_im_entry.png`（ims）
- `res/values*/strings.xml` —— `sessionlist_open_im`（EN/zh/zh-rTW）

**Agent iOS**
- `src/ios/Views/ContentView.swift` —— 工具栏 IM 按钮 + `IMEmbed` 控制器（引擎/通道/展示）
- `src/ios/Assets.xcassets/IMEntry.imageset`（imq+ims，深浅外观）、`JarvisEntry.imageset`（jarvis）
- `src/ios/Podfile` —— podhelper 集成（新增）

## 注意事项

- **引擎生命周期**：两端都采用「缓存引擎」模式 —— 退出 IM 不销毁引擎，
  Matrix 同步保持热身，二次进入秒开。
- **独立 IM 出包**：`im/android`、`im/ios` 原生目录仍保留；但 `im/` 声明为
  module 后，请勿再在 `im/` 下直接 `flutter build apk`（module 模式下该命令
  行为不同），独立出包继续走 `im/android` 的 gradle/fastlane 流程。
- `.android/`、`.ios/` 为 `flutter pub get` 生成的临时工程，可重新生成；
  是否入库由团队自行决定（建议入库以保证宿主构建可复现）。
- Android 宿主 `repositoriesMode` 从 `FAIL_ON_PROJECT_REPOS` 放宽为
  `PREFER_PROJECT`（Flutter Gradle 插件需要向 project 级添加
  `download.flutter.io` 仓库），对现有构建无行为影响。
