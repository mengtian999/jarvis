pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // [T-im-merge] 从 FAIL_ON_PROJECT_REPOS 放宽为 PREFER_PROJECT：
    // Flutter 的 Gradle 插件（:flutter 工程）会向 project 级 repositories
    // 添加 download.flutter.io 的 Maven 仓库以拉取引擎构件；
    // FAIL_ON_PROJECT_REPOS 会在配置期直接报错。PREFER_PROJECT 下
    // settings 仓库仍是兜底，项目级声明优先，行为安全。
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
        // [T-android-vad] RealTimeCutVADLibraryForAndroid ships via JitPack
        // only. Same author and same underlying stack (Silero + ONNX Runtime +
        // WebRTC APM) as the RealTimeCutVADLibrary SPM package iOS already
        // uses, so both platforms segment speech with the same model and the
        // same tunables.
        maven { url = uri("https://jitpack.io") }
        // rclone.aar — the backup feature's remote destinations (SMB / WebDAV /
        // SFTP / S3 / FTP). Not published to any Maven repo: it is built from
        // deps/rclone-mobile by `deps/build_rclone_android.sh`, which is also
        // what produces the iOS XCFramework from the same Go sources and the
        // same trimmed backend list. Treated as a build artifact, not a vendored
        // binary — see docs/backup-restore-design.md §6.2.
        flatDir { dirs("app/libs") }
    }
}

rootProject.name = "Jarvis"

// ── [T-im-merge] Flutter IM module（bitjarvis, add-to-app）─────────────────
//
// IM 位于仓库根 im/，其 pubspec.yaml 已声明 `flutter: module:`。
// 首次集成或 IM 依赖变化后，需在 <repo>/im 下执行一次 `flutter pub get`
// 生成 .android/ 临时工程。下面通过 include_flutter.groovy 把 :flutter
// 与各插件工程纳入本构建；该脚本内部会识别 `apply from:` 上下文
// （模板注释明确支持此用法），并为 flutter-gradle-plugin 注册 includeBuild。
apply(from = File(settingsDir, "../../im/.android/include_flutter.groovy"))

include(":app")
