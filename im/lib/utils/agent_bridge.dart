import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

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