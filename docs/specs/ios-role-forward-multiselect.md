# iOS 角色多选转发 — 可执行实施规格

> 目标：在 iOS 上实现「在一个角色对话中，选中一条或多条消息转发给另一角色」，对齐 Android 已落地的 `forwardMessagesToRole` + 多选 UI。
> 落地环境：macOS + Xcode（本机 Windows 无法编译 Swift，以下为代码级规格，需在 Xcode 内实现并编译验证）。

## 0. 现状（单条转发，已实现）

调用链：
1. `src/ios/Views/Chat/ChatMessageViews.swift` — `ChatMessageRow.onForward: (() -> Void)?`；contextMenu 有「Forward to Role」按钮（`:425`、`:593`，`Label("Forward to Role", systemImage: "paperplane")`）调 `onForward()`。
2. `src/ios/Agent/MessageList/CollectionViewMessageListV3.swift:53` — `var onForwardMessage: ((UUID) -> Void)?`，把 UI 消息 UUID 往上传。
3. `src/ios/Views/Chat/AIChatView.swift:2575` — `onForwardMessage: { msgId in forwardSourceMessageId = msgId; ...; showForwardSheet = true }`。
4. `src/ios/Views/Chat/ForwardToRoleSheet.swift` — `sourceMessageId: UUID?`；`forward()`（`:150`）取单条消息调 `vm.forwardChatMessageToRole(msg, ...)`。
5. `src/ios/Agent/Chat/AIChatViewModel+Persistence.swift:1113` — `forwardChatMessageToRole(_ message: ChatMessage, ...)`：**直接从 ChatMessage 取文本**（用户消息取 `message.content`；assistant 取 text blocks joined），写一行 user 消息到目标会话。

## 1. 关键事实：id 模型

- `ChatMessage.id` 是 **UI UUID**，**不是** DB 行 id（见 `:1108-1110` 注释）。
- 多选后端 `forwardMessagesToRole(messageIds: [String], ...)`（`:1053`）要的是 **DB 行 id**（`store.loadMessages(...).filter { messageIds.contains($0.id) }`）。
- 因此**不要**走 UI-UUID→DB-id 映射（要新写映射且易错）。改为**新增一个 UI 级多消息 API**，复用单条「直接从 ChatMessage 取文本」策略，把 N 条扁平进**同一个**目标会话。

## 2. 新增 VM API（核心）

文件：`src/ios/Agent/Chat/AIChatViewModel+Persistence.swift`，在 `forwardChatMessageToRole`（`:1113-1157`）之后追加：

```swift
/// [T-role-forward] UI-level MULTI-message forward: same extraction strategy as
/// forwardChatMessageToRole (reads text straight off each ChatMessage, no DB-id
/// mapping), but flattens [messages] into ONE target session in display order.
/// Source rows untouched (copy semantics). Returns target session id, or nil
/// when nothing was forwardable.
func forwardChatMessagesToRole(
    _ messages: [ChatMessage],
    targetRoleId: String,
    targetSessionId: String?,
    autoRespond: Bool = true
) async -> String? {
    guard let sourceId = sessionId, !messages.isEmpty else { return nil }
    // Stable display order: index by position in vm.messages.
    let order: [UUID: Int] = Dictionary(uniqueKeysWithValues:
        vm.messages.enumerated().map { ($0.element.id, $0.offset) })
    let sorted = messages.sorted { (order[$0.id] ?? 0) < (order[$1.id] ?? 0) }
    var texts: [String] = []
    for m in sorted {
        let t: String
        if m.role == .user {
            t = m.content
        } else {
            t = m.blocks
                .filter { if case .text = $0.kind { return true } else { return false } }
                .map(\.content)
                .filter { !$0.isEmpty }
                .joined(separator: "\\n\\n")
        }
        let trimmed = t.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty { texts.append(t) }
    }
    guard !texts.isEmpty else { return nil }

    let store = ChatStore.shared
    let effectiveRoleId = RoleStore.roleById(targetRoleId)?.roleId ?? RoleStore.loadDefault().roleId
    let existing = targetSessionId.flatMap { await store.getSession($0) }
    let targetId: String
    if let existing {
        targetId = existing.id
    } else {
        targetId = await store.createSession(
            modelId: selectedModel.id,
            title: String(texts[0].prefix(50)),
            roleId: effectiveRoleId
        ).id
    }
    for t in texts {
        let raw = RawMessage(id: UUID().uuidString, sessionId: targetId,
                             role: .user, parts: [.text(t)], createdAt: Date())
        await store.appendMessage(raw)
    }

    if autoRespond {
        let targetProcessing = SessionActivityTracker.shared.activeSessions.contains(targetId)
        if !targetProcessing {
            if existing != nil { ViewModelCache.shared.remove(sessionId: targetId) }
            ViewModelCache.shared.stashPendingForwardRespond(targetId: targetId)
        }
    }
    AppLogger(category: "Forward").info("[ForwardUI] \\(texts.count) msg(s) → session=\(targetId.prefix(8)) role=\(effectiveRoleId) source=\(sourceId.prefix(8))")
    return targetId
}
```

> 注：`texts[0].prefix(50)` 与单条一致作为标题预览；autoRespond marker 只 stash 一次（目标会话级）。

## 3. ForwardToRoleSheet 多 id 改造

文件：`src/ios/Views/Chat/ForwardToRoleSheet.swift`

### 3.1 入参
把 `let sourceMessageId: UUID?`（`:14`）改为：
```swift
let sourceMessageIds: Set<UUID>   // 空集合 = 异常（调用方应保证非空）
```
（保留 `vm`、`pickRoleId`、`targetSessionId`、`autoRespond` 绑定不变。）

### 3.2 `forward()`（`:150-174`）
把「取单条」改为「取多条并调多消息 API」：
```swift
private func forward() async {
    let chosen = chosenRole
    let msgs = vm.messages.filter { sourceMessageIds.contains($0.id) }
    guard let chosenRole = chosen, !msgs.isEmpty else {
        dismiss()
        return
    }
    forwarding = true
    let targetId = await vm.forwardChatMessagesToRole(
        msgs,
        targetRoleId: chosenRole.roleId,
        targetSessionId: targetSessionId,
        autoRespond: autoRespond
    )
    forwarding = false
    dismiss()
    guard let targetId else { return }
    Task { @MainActor in
        NotificationCenter.default.post(
            name: .forwardDidComplete, object: nil,
            userInfo: ["targetSessionId": targetId]
        )
    }
}
```

> 导航/`forwardDidComplete` 监听不变（`AIChatView` 已监听并跳转目标会话）。

## 4. AIChatView 多选状态与接线

文件：`src/ios/Views/Chat/AIChatView.swift`

### 4.1 状态（`:465-469` 附近）
新增：
```swift
@State private var forwardSelecting = false
@State private var forwardSelectionIds: Set<UUID> = []
```
并把 `forwardSourceMessageId: UUID?`（`:465`）替换为 `forwardSourceMessageIds: Set<UUID> = []`（供 sheet 用）。

### 4.2 长按「转发」（`:2575`）— 单条入口仍可用
```swift
onForwardMessage: { msgId in
    forwardSourceMessageIds = [msgId]      // 单条也走多消息路径（集合大小 1）
    forwardPickRoleId = nil
    forwardTargetSessionId = nil
    forwardAutoRespond = true
    showForwardSheet = true
},
```

### 4.3 sheet 调用（`:758-766`）
`ForwardToRoleSheet(...)` 的 `sourceMessageId:` 改为 `sourceMessageIds: forwardSourceMessageIds`。

### 4.4 多选「Forward」入口（选择栏按钮）
选择栏（见 §5）的 Forward 按钮：
```swift
forwardSourceMessageIds = forwardSelectionIds
forwardPickRoleId = nil
forwardTargetSessionId = nil
forwardAutoRespond = true
showForwardSheet = true
```

### 4.5 监听 forwardDidComplete 后清多选态
在 `.onReceive(NotificationCenter.default.publisher(for: .forwardDidComplete))` 处理里追加：
```swift
forwardSelecting = false
forwardSelectionIds = []
```

## 5. CollectionViewMessageListV3 多选态接入点

文件：`src/ios/Agent/MessageList/CollectionViewMessageListV3.swift`（UICollectionView 后端，最复杂处）

### 5.1 把多选态注入协调器
该文件用一个 coordinator（类名形如 `MessageListCoordinatorV3`，搜索 `coord.onForwardMessage`/`onRetryMessage` 赋值处，约 `:83-85`）桥接回调。在 coordinator 增加：
```swift
var forwardSelecting: Bool = false
var forwardSelectionIds: Set<UUID> = []
var onToggleSelection: ((UUID) -> Void)? = nil       // cell tap in select mode
var onEnterSelect: (() -> Void)? = nil               // contextMenu "Select"
```
在 `AIChatView` 创建/更新协调器处（`:2575` 附近一起）注入这些绑定，与 `onForwardMessage` 同源。

### 5.2 cell tap 行为切换
在 coordinator 的 `collectionView(_:didSelectItemAt:)`（搜索该 delegate 方法）里：
```swift
if forwardSelecting {
    let id = messageId(at: indexPath)   // 复用现有「item → ChatMessage.id」解析
    onToggleSelection?(id)
    // 不要 deselect 默认高亮；或自行 toggle 一个选中样式
    return
}
// …原有打开/点击逻辑
```
> `messageId(at:)`：该文件已有 `messageIndex: [UUID: Int]`（`:3431`）与 `msg(_ id: UUID)` 解析，复用它从 indexPath → item → messageId。

### 5.3 选中样式
在 cell 配置（`cellForItemAt` 或 cell 的 `update`）里，当 `forwardSelectionIds.contains(messageId)` 时叠加一个勾选徽（右上角 `circle.checkmark` / `checkmark.circle.fill`），并让整 cell 可点（tap 切换）而非打开详情。

### 5.4 选择栏 overlay
在消息列表 SwiftUI 容器（`AIChatView` 里包裹 `CollectionViewMessageListV3` 的 VStack）顶部，条件渲染：
```swift
if forwardSelecting {
    HStack {
        Text("\(forwardSelectionIds.count)").bold()
        Spacer()
        Button("Forward") { /* §4.4 */ }.disabled(forwardSelectionIds.isEmpty)
        Button("Cancel") { forwardSelecting = false; forwardSelectionIds = [] }
    }
    .padding().background(.regularMaterial)
}
```

## 6. 长按「选择」入口

文件：`src/ios/Views/Chat/ChatMessageViews.swift`

在 contextMenu（`:425` 与 `:593` 两处「Forward to Role」按钮旁）新增「Select」按钮：
```swift
if let onEnterSelect = onEnterSelect {
    Button {
        onEnterSelect()
    } label: {
        Label(String(localized: "Select"), systemImage: "checkmark.circle")
    }
}
```
给 `ChatMessageRow` 加 `var onEnterSelect: (() -> Void)? = nil`，在 `CollectionViewMessageListV3` 协调器里接到 `onEnterSelect`（`forwardSelecting = true; forwardSelectionIds = []`）。

> 流式时隐藏「Select」（同 Retry/Edit 的 `onRetry==nil` gating：调用方在 streaming 时传 nil）。

## 7. 字符串

在 `Localizable.strings`（各 locale）加：
- `"Select"` = 选择 / Select / 選択…
- 选择栏的 `"Forward"`/`"Cancel"` 已存在，复用。

## 8. 落地顺序与验证

1. VM API（§2）— 独立，先做；编译。
2. ForwardToRoleSheet 多 id（§3）— 编译；单条长按转发回归正常（集合大小 1）。
3. AIChatView 状态 + sheet 调用 + 长按入口接线（§4.2/4.3/4.5）— 编译；单条转发端到端可用。
4. 多选态：协调器注入（§5.1）+ cell tap 切换（§5.2）+ 选中样式（§5.3）+ 选择栏（§5.4）— Xcode 跑模拟器，验证：长按「Select」→ tap 多条 → 选择栏计数 → Forward → sheet 选角色/会话 → 转发后跳目标会话、目标自动回复。
5. 长按「Select」入口（§6）+ 字符串（§7）。
6. 回归：单条「Forward to Role」仍可用；流式中 Select/Forward 隐藏；转发后源会话不动。

## 9. 约束（与 Android 一致，勿违反）

- 会话内角色不可变：转发永远写**新会话或目标角色已有会话**，不改当前会话。
- 转发消息 `role = .user`，`roleId` 不带（非目标角色产出）—— §2 已如此。
- autoRespond marker 会话级一次；目标正在流式则跳过。
- 不引入 UI-UUID→DB-id 映射（§1 理由）；多消息走 §2 的 UI 级 API。

## 10. 风险点

- `CollectionViewMessageListV3` 是 UICollectionView 后端，cell 复用与 `update` 里加选中样式要确保不与现有「激活消息高亮」「流式光标」冲突——在 cell 的 update 里把选中态作为最高优先级覆盖。
- `didSelectItemAt` 切到「切换选中」后，记得不要触发原「打开详情/选中文本」逻辑（早 `return`）。
- 选择栏 overlay 与现有顶栏（角色头像、模型选择）布局避让，建议 overlay 在 list 顶部、`.regularMaterial` 背景。
