import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// IM ↔ Agent 桥接通道。
///
/// IM（bitjarvis）以 Flutter module 形式嵌入原生 Agent 宿主（add-to-app）。
/// IM 对话列表右下角的「贾维斯」入口被点击时，通过该通道请求原生侧
/// 收起 IM 界面、返回 Agent。
///
/// 独立运行 IM（`flutter run` / 原生 `android`、`ios` 目录出包）时，
/// 没有原生宿主注册该通道，调用会抛 [MissingPluginException]——
/// 静默忽略即可，不影响 IM 自身功能。
class AgentBridge {
  AgentBridge._();

  /// 与原生侧 `ImFlutterEngine`（Android）/ `IMEmbed`（iOS）约定的通道名。
  static const MethodChannel channel = MethodChannel('jarvis.im/agent_bridge');

  /// 嵌入标志的 SharedPreferences key。
  ///
  /// Android 宿主（ImFlutterEngine）在启动引擎前会向 Flutter 的
  /// SharedPreferences（文件 `FlutterSharedPreferences`，注意 Dart 插件读写
  /// 时 key 带 `flutter.` 前缀，故宿主需写入 `flutter.jarvis.im.embedded`）
  /// 写入 `true`；独立运行时该 key 不存在。
  static const String embeddedPrefKey = 'jarvis.im.embedded';

  static bool? _embeddedCache;

  /// 当前 Flutter 引擎是否嵌入在 Agent 宿主内运行。
  ///
  /// 结果会在首次读取后缓存（宿主在引擎启动前写好标志，运行期不变）。
  /// 任何异常（独立模式引擎刚启动等）一律按「未嵌入」处理，保证独立
  /// 出包行为不受影响。
  static Future<bool> isEmbedded() async {
    final cached = _embeddedCache;
    if (cached != null) return cached;
    var embedded = false;
    try {
      final prefs = await SharedPreferences.getInstance();
      embedded = prefs.getBool(embeddedPrefKey) ?? false;
    } catch (e) {
      debugPrint('[AgentBridge] isEmbedded read failed: $e');
    }
    _embeddedCache = embedded;
    return embedded;
  }

  /// 请求原生宿主收起 IM、返回 Agent 界面。
  static Future<void> exitToAgent() async {
    try {
      await channel.invokeMethod<void>('exitToAgent');
    } on MissingPluginException {
      // 独立运行模式：没有原生宿主在监听，忽略。
    } on PlatformException catch (e) {
      // 宿主在但拒绝/失败了——留在 IM 界面即可。
      debugPrint('[AgentBridge] exitToAgent failed: ${e.code} ${e.message}');
    }
  }
}