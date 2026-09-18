import 'dart:async';

import 'package:bitjarvis/config/themes.dart';
import 'package:bitjarvis/l10n/l10n.dart';
import 'package:bitjarvis/utils/agent_bridge.dart';
import 'package:flutter/material.dart';

/// 「贾维斯」入口 FAB —— 从 IM 返回 Agent 界面。
///
/// 视觉与动效完全对齐 [StartChatFab]（新的对话按钮）：
/// `extended == true` 时在图标右侧以 AnimatedSize 展开「贾维斯」文字，
/// 收起时只留圆形图标；展开/收起的时机由调用方（ChatListView）传入，
/// 与「新的对话」按钮完全同步。
///
/// 布局上它位于「新的对话」按钮的**正上方**（见 ChatListView 的
/// floatingActionButton 组合）。
class JarvisEntryFab extends StatelessWidget {
  final bool extended;

  const JarvisEntryFab({this.extended = false, super.key});

  @override
  Widget build(BuildContext context) {
    return FloatingActionButton.extended(
      heroTag: 'jarvis_entry_fab',
      backgroundColor: Theme.of(context).colorScheme.primary,
      foregroundColor: Theme.of(context).colorScheme.onPrimary,
      onPressed: () => unawaited(AgentBridge.exitToAgent()),
      extendedIconLabelSpacing: extended ? 10 : 0,
      extendedPadding: extended
          ? null
          : const EdgeInsets.symmetric(horizontal: 16),
      label: AnimatedSize(
        alignment: Alignment.centerLeft,
        duration: FluffyThemes.animationDuration,
        curve: FluffyThemes.animationCurve,
        child: extended
            ? Text(L10n.of(context).jarvis)
            : const SizedBox.shrink(),
      ),
      icon: ClipOval(
        child: Image.asset(
          'assets/jarvis.png',
          width: 24,
          height: 24,
          fit: BoxFit.cover,
        ),
      ),
    );
  }
}