package com.jarvis.app.im

import android.content.Context
import com.jarvis.app.logging.AppLogger
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.embedding.engine.dart.DartExecutor

/**
 * IM（bitjarvis Flutter module）引擎的预热与缓存。
 *
 * 与官方 add-to-app cached-engine 模式一致：
 *  - `FlutterEngine(context)` 构造时自动注册 module 的 GeneratedPluginRegistrant；
 *  - `executeDartEntrypoint(createDefault())` 跑起 Dart 侧 `main()`（IM 的
 *    FluffyChatApp GUI）；
 *  - 缓存进 [FlutterEngineCache]，之后 [ImFlutterActivity] 通过
 *    `withCachedEngine(ENGINE_ID)` 秒开，且引擎在两次进入 IM 之间保持
 *    热身状态（Matrix 同步不中断）。
 */
object ImFlutterEngine {
    private const val TAG = "ImLauncher"

    const val ENGINE_ID = "jarvis_im_engine"

    /** 与 IM Dart 侧 [com.jarvis.app.im.AgentBridge]（agent_bridge.dart）约定的通道名。 */
    const val CHANNEL = "jarvis.im/agent_bridge"

    fun isWarm(): Boolean = FlutterEngineCache.getInstance().get(ENGINE_ID) != null

    /**
     * 懒预热：首次进入 IM 时创建并缓存引擎；已存在则直接复用。
     * 模块未集成（:flutter 工程缺失）等极端情况下返回 null 并记录日志，
     * 调用方不应崩溃。
     */
    fun ensure(context: Context): FlutterEngine? {
        FlutterEngineCache.getInstance().get(ENGINE_ID)?.let { return it }
        // [T-im-merge] main() 里会读 jarvis.im.embedded 决定是否跳过
        // background-fetch 分支。必须在 engine 构造 + executeDartEntrypoint
        // 之前写入，否则 main() 读到默认值 null，会走 background-fetch
        // 分支 return，IM 界面保持黑屏。Flutter SharedPreferences 用的
        // Android 文件名是 flutter.shared_preferences。注意 Dart 侧
        // shared_preferences 插件读写时 key 会自动加 `flutter.` 前缀
        // （AgentBridge.isEmbedded / voip_plugin.dart 均依赖此标志），
        // 因此这里必须写入带前缀的 key。
        context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            .edit().putBoolean("flutter.jarvis.im.embedded", true).commit()
        return runCatching {
            FlutterEngine(context.applicationContext).also { engine ->
                engine.dartExecutor.executeDartEntrypoint(
                    DartExecutor.DartEntrypoint.createDefault(),
                )
                FlutterEngineCache.getInstance().put(ENGINE_ID, engine)
            }
        }.onFailure {
            AppLogger.error(TAG, "Failed to start the IM FlutterEngine: ${it.message}")
        }.getOrNull()
    }
}