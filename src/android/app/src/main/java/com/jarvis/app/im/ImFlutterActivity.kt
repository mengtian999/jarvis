package com.jarvis.app.im

import android.content.Context
import android.content.Intent
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/**
 * IM（bitjarvis Flutter module）的宿主 Activity。
 *
 * 引擎由 [ImFlutterEngine] 懒预热并缓存（destroyEngineWithActivity = false），
 * 本 Activity 只负责把缓存的引擎 attach 到屏幕，以及注册「贾维斯」退出
 * 通道：IM 里点击 jarvis 入口 → Dart 调 `exitToAgent` → 这里 finish()
 * 返回 Agent（栈底仍是 MainActivity，系统返回键同样回到 Agent）。
 */
class ImFlutterActivity : FlutterActivity() {

    companion object {
        /** 引擎与 Activity 生命周期解耦：退出 IM 不销毁引擎，二次进入秒开。 */
        fun buildIntent(context: Context): Intent =
            CachedEngineIntentBuilder(ImFlutterActivity::class.java, ImFlutterEngine.ENGINE_ID)
                .build(context)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, ImFlutterEngine.CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "exitToAgent" -> {
                        result.success(null)
                        finish()
                    }
                    else -> result.notImplemented()
                }
            }
    }
}