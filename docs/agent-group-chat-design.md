# Agent 群聊（多角色群聊会话）技术实施方案

> `[T-group-chat]` 用户可把多个角色拉进一个群聊会话：`@` 点名让特定成员回答，
> `@全体` 全员轮流必答；成员各自带人格、工具与模型，按顺序在同一时间线发言。
> 双端（iOS / Android）1:1 镜像实现，遵循现有 `Mirrors iOS/Android` 注释约定。

## 0. 已拍板决策（本文档的前提）

| # | 决策 | 内容 |
|---|---|---|
| D1 | 群聊做在**原生 Agent 侧**，不做在 IM（Matrix）侧 | IM 线不立项。理由：消息层已有作者概念（`messages.role_id`），UI 已按作者渲染；IM 侧无 bot/appservice 基础，gateway 身份刻意只在宿主持有 |
| D2 | `@全体` = **全员轮流必答**，不做智能跳过 | 「成员可跳过」通过群设置里的**单成员参与开关**实现（关闭即不参与全员轮流）；`@` 点名不受该开关限制 |
| D3 | 主持角色：群管理中设置，**默认无** | `host_role_id` 可空。设了之后：主持人 prompt 追加主持职责段，默认排在每轮**最后**发言 |
| D4 | 模型配置进 `role.json`（角色的一部分）；**群管理可开启群级模型覆盖**（开启后本群所有成员使用指定统一模型），默认关 | `role.model` 为空 = 跟随当前默认模型，现有行为不变。优先级：**群覆盖 > 角色模型 > 会话默认**（§5） |
| D5 | 一次群回合 = **顺序**执行 N 个成员的 agent loop | 保持「每会话同时只有一个 loop」不变量；后面的发言者天然看到前面成员本轮的回复 |
| D6 | 删除群 = 级联删除该群的会话（UI 二次确认） | 不做孤儿会话 / 软删除 |

## 1. 范围与总体架构

```
┌────────────────────────── 原生 Agent App ──────────────────────────┐
│  会话列表/新建会话 ──> 建群 / 选群（新入口）                          │
│  ChatVM（群会话分支）                                               │
│   ├─ parseMentions(text)          @解析                             │
│   ├─ runGroupRound(members)       顺序调度，逐成员：                 │
│   │    speakerRole = member.role                                 │
│   │    provider   = resolve(role.model) ── 兜底默认模型            │
│   │    prompt     = identitySection(speakerRole) + groupSection() │
│   │    history    = rewriteHistoryFor(speakerRole.roleId)  ← 署名改写│
│   │    runAgentLoop(...)          ← 复用现有 loop（工具/流式/持久化）│
│  GroupStore（CRUD，DB 持久化，双端）                                 │
│  RoleStore（role.json + model 字段）                                │
│  messages 表零改动：assistant 行 role_id 即发言者                    │
└────────────────────────────────────────────────────────────────────┘
```

- **消息表零改动**：`messages.role_id`（仅 assistant 行非空）就是群聊发言者；气泡作者头部（iOS `AssistantMessageHeader`、Android `AssistantHeader(roleId:)`）直接复用。
- **普通会话完全不动**：`sessions.role_id` 语义不变；群会话以 `sessions.group_id != NULL` 区分。
- 路由选人 / Agent 互 @ 已从计划中移除（§8 的移除记录）；`max_rounds` 列仅为零成本预留，将来若做无需迁移。

## 2. 数据模型与迁移

### 2.1 `agent_groups` 表（双端同构）

```sql
CREATE TABLE agent_groups (
  id            TEXT PRIMARY KEY,          -- uuid
  name          TEXT NOT NULL,
  instruction   TEXT,                      -- 群公告/群级设定，可空
  members_json  TEXT NOT NULL,             -- 见下，数组顺序 = 轮流顺序
  host_role_id  TEXT,                      -- 主持角色，可空（默认无）
  model_override_json TEXT,                -- 群级统一模型 {"modelId":"…","providerInstanceId":"…"}，NULL=关闭（默认关）
  max_rounds    INTEGER NOT NULL DEFAULT 1,-- 预留列（零成本）：Agent 互 @ 最大轮数，本轮不做
  created_at    INTEGER/REAL NOT NULL,     -- Android: ms；iOS: REAL epoch
  updated_at    INTEGER/REAL NOT NULL
);
```

`members_json`：

```json
[{"roleId": "persona-architect", "participatesInRound": true},
 {"roleId": "persona-health",    "participatesInRound": false}]
```

- **不加外键约束**，`host_role_id`/成员引用都不做 FK —— 与 `sessions.folder_id` 同等处理（plain column + index）。角色删除后的失效处理见 §3.3。
- iOS `ChatStore` 新增该表 + CRUD（与 `folders` 表同一层级、同一同步注册方式，见 §7）；Android 新增 Room `@Entity` + DAO。

### 2.2 `sessions` 加 `group_id` 列

- DDL：`ALTER TABLE sessions ADD COLUMN group_id TEXT`，加索引 `index_sessions_group_id`。
- 群会话创建时 `group_id = 群id`、`role_id = NULL`；标题默认 = 群名（**不走自动标题生成**，允许手动改名）。
- 双端 `ChatSession` 结构体的 equality/hash **必须包含 `groupId`**（遵守 `ChatSessionEntity.kt` / `ChatStore.swift` 中关于列表 diff 的注释约定）。
- 群会话**不出现**在按角色筛选的列表中（Android `SessionListViewModel.roleFilter`、iOS `RoleNavigationBus.showRoleHistory` 过滤时排除 `group_id != null`）。

### 2.3 `role.json` 加可选 `model` 字段

```json
{ "name": "老王", "style": "...", "lang": "zh", "body": "...",
  "model": { "modelId": "claude-opus-4-xxx", "providerInstanceId": "inst-123" } }
```

- 存**完整引用**（`modelId` + `providerInstanceId`），与 `messages` 表模型归属快照列（`model_id`/`provider_type`/`provider_instance_id`）同一引用格式——用户会有多个 provider 实例挂同名模型。
- 字段缺省 / `null` = 跟随当前默认模型。老版本客户端忽略新字段，前向兼容。
- 双端 `RoleMetadata` 增加对应字段；iOS `SyncedRole` 增加 `modelJson: String?`（`F.optionalString`，LWW 照旧），Android 备份导出的是原始 `roles/` 文件，自动带上。

### 2.4 Android Room 迁移治理（**先于一切**，Phase 0）

现状：`AppDatabase` `version = 1` + `fallbackToDestructiveMigration()` + `exportSchema = false`；而 `app/build.gradle.kts` 已按 `[T-android-downgrade-compat]` 配好 `room.schemaLocation` 并写明「schema JSON 应提交、用 MigrationTestHelper 验证」的意图——只是从未启用。

步骤：

1. `exportSchema = true`，先**在 version=1 状态下**构建一次，提交 `schemas/…/1.json`（基线）。
2. 实体加列/加表（`ChatSessionEntity.groupId`、新 `AgentGroupEntity`、`MessageEntity` 不动），`version = 2`，编写：

```kotlin
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS agent_groups (...)")   // 与 @Entity 注解严格一致（含索引）
        db.execSQL("ALTER TABLE sessions ADD COLUMN group_id TEXT")   // 先 ALTER 后建索引（索引引用新列）
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_group_id ON sessions(group_id)")
    }
}
```

3. `addMigrations(MIGRATION_1_2)`；`fallbackToDestructiveMigration()` 保留作为兜底但正常路径不再触发。
4. `MigrationTestHelper` 迁移测试（1→2 及回退 no-op），进 CI。

> 注意 Room 校验的是实体注解生成的期望 schema，`CREATE TABLE` 必须逐列对齐（默认值、NOT NULL、索引名），以测试为准而非肉眼。

### 2.5 iOS 迁移

沿用 `ChatStore.createTables()` 末尾的幂等模式（`addColumnIfMissing` / `CREATE TABLE IF NOT EXISTS`），加 `group_id` 列与 `agent_groups` 表即可，无版本号机制、无风险。

## 3. GroupStore（双端）

| | iOS | Android |
|---|---|---|
| 存储 | `ChatStore`（actor）内新增表 + SQL（仿 `folders`） | `data/db/AgentGroupEntity.kt` + `GroupDao`，进 `AppDatabase.entities` |
| 门面 | `Agent/Session/GroupStore.swift`（enum + async funcs，API 风格仿 `RoleStore`） | `agent/GroupStore.kt`（object，`prime(context)` + `StateFlow`，仿 `RoleStore.kt`） |
| 通知 | `Notification.Name.groupChanged` / 内存 snapshot 缓存 | `groupsSnapshot: StateFlow<List<AgentGroup>>` |

API（双端同名语义）：

```
createGroup(name, memberRoleIds, hostRoleId?, instruction?) -> Group
updateGroup(id, ...)                    // 改名/公告/主持/成员列表（含顺序与参与开关）
deleteGroup(id)                         // 显式事务：删群行 + 该群全部会话（消息由 messages→sessions FK CASCADE 带走）
resolveMembers(group) -> [ResolvedMember]  // 过滤已删除角色的成员，见 §3.3
```

### 3.1 群头像

不存头像。UI 用成员头像堆叠渲染（2-4 张，多于此显示数量角标）。`agent_groups` 无 avatar 列。

### 3.2 轮流顺序

`members_json` 数组顺序即轮流顺序（建群时的勾选顺序，群管理里可拖动调整）。`host_role_id` 非空时主持人**固定最后**（无论其在数组中的位置；被 `@` 点名时仍在最后）。

### 3.3 失效成员（角色被删）

`resolveMembers` 用 `RoleStore.resolveRole` 逐个解析；解析失败的成员从返回列表剔除，但**不回写** `members_json`。群管理页显示灰色「已删除」行 + 一键清理按钮（显式 `updateGroup` 移除）。正在进行的群回合遇到失效成员：跳过并在时间线插一条 `systemInfo` 行（「⚠️ 成员 xxx 已删除，已跳过」）。

## 4. 发送流水线改造（核心）

### 4.1 「当前发言者」抽象：`speakerRole`

现有流水线处处引用「会话角色」（iOS `sessionRole`，Android `_sessionRole`）做四件事：(a) provider/模型解析、(b) `baseSystemPrompt`/`buildSystemPrompt()` 的 `identitySection`、(c) assistant 行落库时盖 `roleId`、(d) typing indicator 名字。

改造：引入**当前发言者**——

- iOS：`AIChatViewModel.speakerRole: RoleFile`（普通会话恒等于 `sessionRole`，群会话逐成员变化）。
- Android：`ChatViewModel._speakerRole: StateFlow<RoleFile>`（同上）。

上述四处引用全部改读 `speakerRole`。这是对 `runAgentLoop` 的**唯一**侵入点：loop 本体、工具执行、流式、批量落库、错误持久化全部不动。落库时的模型归属快照列照旧按当次实际 provider 填写（群聊气泡可借此显示模型小标，§6）。

### 4.2 @解析：`parseMentions(text) -> MentionResult`

```
MentionResult = All | Some(ordered: [roleId]) | None
```

- 触发词：`@全体`、`@所有人`、`@all`（大小写不敏感）→ `All`。
- `@名字`：名字匹配到 `@` 后直到空白/标点；多个成员名前缀匹配时**长名优先**，存在精确匹配则必须精确。
- **无任何 @（None）= All**——群会话里不点名就是对全员说话（D2）。
- 解析发生在持久化**之前**，但用户消息原文（含 @）原样落库、原样进历史。
- 测试向量放 `src/shared/groupchat/mention_vectors.json`，双端共享（对齐 `[T-bash-on-demand]` 的共享向量模式，构建期拷贝）。

目标成员解析：

```
All  -> resolveMembers(group).filter { it.participatesInRound }（主持人最后）
Some -> 提到的成员（仍按 members_json 顺序执行；点名不受参与开关限制；点名主持人在最后）
空集（@了不存在的名字）-> 时间线 systemInfo 提示，不发起任何调用
```

### 4.3 群回合调度：`runGroupRound(targets)`

```
suspend fun runGroupRound(targets: List<ResolvedMember>) {
    isProcessing = true
    try {
        for (member in targets) {
            if (userCancelled) break                       // 取消 = 终止整轮
            speakerRole = member.role
            val provider = resolveSpeakerModel(group, member.role)   // §5：群覆盖 > 角色模型 > 默认，逐级降级 + banner
            val systemPrompt = identitySection(speakerRole) + groupSection(group, speakerRole, targets)
            val history = buildMemberHistory(sessionId, speakerRole.roleId)   // §4.4，从 DB 重建
            checkContext(member)                            // §4.6
            try {
                runAgentLoop(provider, systemPrompt, history)   // 现有 loop，含工具/流式/落库
            } catch (e) {
                persistErrorRow(member.roleId, e)           // 单成员失败：错误行 + 继续下一位
                continue
            }
        }
    } finally { isProcessing = false; drainQueuedPrompts() }
}
```

不变量与既有机制的复用：

- **每会话一个 loop** 的不变量保持：群回合内串行；用户在回合中发新消息 → 走现有 `promptQueue`/`enqueuePrompt`，回合结束后 drain（每个排队文本各触发一轮新的群回合）。
- **跨会话 5 并发上限**（双端 `SessionConcurrencyManager`）原样生效：整个群回合持有一个 slot（成员循环在 slot 内进行，不逐成员抢 slot）。
- **后面的成员看得到前面的回复**：每成员 loop 结束即落库（现有批量落库路径），下一位从 DB 重建历史时自然包含——「A 起草、B 审阅」的圆桌效果零成本获得。
- 取消语义：复用现有 cancel 路径；调度器在**成员之间**检查取消标志，当前成员的 loop 按现有取消逻辑中断。

### 4.4 历史署名改写：`buildMemberHistory(sessionId, selfRoleId)`

群会话**不维护**跨回合的内存 `agentHistory`（单一历史无法表达「同一行对不同成员是不同视角」），每成员每轮**从 DB 重建**（会话受 compaction 约束，规模可控）：

```
for row in loadMessages(sessionId):            // 含 compact marker / systemInfo，原样透传
  if row.role == user:        -> user 轮（原文，含 @）
  if row.role == assistant:
      if row.roleId == selfRoleId:
          -> assistant 轮，parts 原样（含自己的 toolUse/toolResult）
      else:
          -> 文本部分拼接（工具块跳过、媒体 → "[图片]" 占位，复用 forwardExtractText 语义），
             前缀 "[作者名]:"，作为 user 轮
连续的改写 user 轮合并为一条（多段 "[名]: …" 拼接），减少轮数
```

> 为什么必须改写：不改写则模型把其他成员的发言当成自己说的（人格串味），且部分 provider 对连续 assistant 轮行为异常。存储层永远存 `role=assistant + role_id`，改写只发生在发给 LLM 的请求组装处。iOS 落点在 `runAgentLoop` 组装 `AgentMessage` 处，Android 落点在 `agentHistory` 构建处。

### 4.5 系统提示词：`groupSection(group, self, targets)`

每成员 = 独立 LLM 调用，prompt = 现有 `identitySection(for: speakerRole)`（iOS `RoleSystemPromptBuilder` / Android `SystemPromptBuilder`，**不改**）+ 新增群上下文段（英文模板，与现有 prompt 风格一致）：

```
## Group chat context
You are in a group chat. Members (speaking order):
- 老王 — architect persona, speaks before you
- 小艾 — health assistant
The human user is the room owner; their messages are plain user turns.
Other members' messages appear as user turns prefixed "[name]:".
Messages with no prefix from your own assistant turns are your previous replies.
Group instruction from the user: {group.instruction}          // 非空才有此行
[若主持人] You are the moderator of this group: after others have spoken,
 summarize the discussion so far, surface disagreements, and propose next steps.
 Keep it concise.
```

成员一句话简介取自 role body 首行（或 `style` 截断）。成员各自的 per-role memory（`loadRecentDailyMemoryFragment(roleId)`）**不动**——群级共享上下文只走 `instruction`，避免记忆互相污染。

### 4.6 与 compaction / 上下文预算 / 队列的交互

- `checkContextBeforeSend` 的等价检查**逐成员执行**，但群会话中静默走 auto-compact，不弹确认（避免一轮弹 N 次对话框）；压缩落库后，后续成员重建历史自动使用压缩结果。
- 排队 prompt（回合中用户又发消息）：drain 时每个文本独立走 `parseMentions → runGroupRound`。
- 标题生成跳过（标题 = 群名）；`sessions.last_message` 预览沿用现有更新路径（显示最后一位发言者的摘要，天然合理）。

## 5. 模型配置：优先级链与 UI

**优先级链（群会话中每个成员发言前解析）**：

```
群级覆盖 group.modelOverride  >  角色模型 role.model  >  会话默认模型
```

- `resolveSpeakerModel(group, role)` 逐级取第一个**非空且可解析**的配置：按 `{modelId, providerInstanceId}` 查 `ProviderConfigStore`/provider 实例表；某级配置失效（实例被删/模型下线）→ 降级到下一级 + 顶部 banner 一次性提示；三级全空 = 现有默认行为。**绝不让一个成员的配置失效拖垮整轮**（与 §4.3 的单成员失败隔离双保险）。
- 群覆盖是**本群解析期的一层覆盖**：不改 `role.json`、不影响该角色在其他群或单聊使用的模型。
- 消息落库的模型归属快照（`model_id`/`model_display_name`/…）始终记录**实际使用**的模型——气泡小标自动反映覆盖结果，零额外处理。

**UI**：

- 角色编辑页（双端 RolePickerSheet → 编辑）加「默认模型」行：选择器列出全部 provider 实例 × 模型，含「跟随默认」项。
- 群管理页新增「统一模型」区（**默认关**）：开关 + 模型选择器（复用角色编辑页同款组件）；关闭即清空（`model_override_json = NULL`，重新开启需再选）。开启后成员列表每行的模型小标统一显示覆盖模型短名；未开启时显示 `role.model` 短名或「默认」。建群 sheet 同样显示各成员标签——建群时一眼看清配置。
- 群聊气泡作者头部（`AssistantHeader` / `AssistantMessageHeader`）追加小字模型名，取自该消息落库的 `model_display_name` 快照列（列已存在，零 schema 改动）。仅群会话显示。

**单聊同样受益**：`role.model` 非空时，单聊会话的 provider 解析同样优先角色模型（`speakerRole == sessionRole`，§4.1 的抽象自动覆盖；单聊没有群覆盖这一级，链只剩 角色 > 默认）。

## 6. UI 改动清单

| 界面 | iOS 落点 | Android 落点 | 内容 |
|---|---|---|---|
| 新建会话入口 | 新建 sheet / `ContentView` | 新建菜单 / `SessionListScreen` | 新增「发起群聊」：选已有群 或 建新群 |
| 建群 sheet | 新 `CreateGroupSheet.swift`（仿 `ForwardToRoleSheet` 两步结构） | 新 `CreateGroupSheet.kt` | 群名、成员多选（复用角色列表行 + 模型标签）、可选主持（默认无）、可选公告 |
| 群管理页 | 新 `GroupSettingsView.swift` | 新 `GroupSettingsScreen.kt` | 改名/公告/主持选择（默认无）/成员增删+拖动排序/单成员参与开关/「统一模型」开关+选择器（默认关，D4）/失效成员清理/删除群（二次确认，D6） |
| 会话列表行 | `ContentView` sidebar 行 | `SessionListScreen` 行 | 群会话：头像堆叠 + 群名；角色筛选列表排除群会话 |
| 输入栏 @ 补全 | `ChatInputBar.swift` | `ChatScreen` 输入区 | 监听 `@` 弹出成员浮层（头像+名+模型标签），选择插入 `@名字 ` |
| 聊天气泡 | `ChatMessageViews` / `AssistantBlockView` | `ChatAssistantMessageUI` / `ChatFlatItems` | 作者头部已支持（零改动）；群会话加模型小字；typing indicator 显示当前 `speakerRole` 名（现有 `sessionRoleName` 数据源换成 speakerRole） |
| 系统提示行 | — | — | `systemInfo` 行复用现有样式（成员跳过/回退默认模型/点名无效等提示） |

## 7. 同步与备份

- **iOS（CloudKit/LAN）**：`agent_groups` 表与 `sessions.group_id` 跟随 **`folders` 表在 ChatStore 同步管线中的既有注册方式**（同表级同步、同一套 dirty/tombstone 处理）；`role.json` 的 `model` 字段加进 `SyncedRole`（`F.optionalString("modelJson", …)`，LWW by `updatedAt` 照旧）。
- **Android**：`BackupCategory.GROUPS` 新增（`BackupExporter`/`BackupImporter` 加导出/导入分支，导出 `agent_groups` 行）；`sessions.group_id` 随现有 `CHATS` 类目走（导出会话行时带上新列，导入时容错缺列）；`roles/` 原样导出，`model` 字段自动包含；恢复后 `GroupStore.refreshAfterRestore()`（仿 `RoleStore.refreshAfterRestore`）。
- 旧备份 / 旧设备同步记录缺失新字段：全部按「空 = 默认行为」处理，与 §2.3 的兜底一致。

## 8. 阶段划分与验收标准

| 阶段 | 内容 | 验收标准 | 量级 |
|---|---|---|---|
| P0 | Android 迁移治理（§2.4）：exportSchema、schema 1.json 提交、version 2、MIGRATION_1_2、迁移测试 | 老版本数据安装升级后会话/消息无丢失（迁移测试 + 真机抽查） | 0.5–1 天 |
| P1 | 数据层 + GroupStore + 建群/群管理 UI（§2/§3/§6 前三行） | 建群、改群、删群（级联删会话）全流程可手测；「统一模型」开关可开/关且持久（生效于 P2）；重启持久 | 2–3 天 |
| P2 | 群会话发送流水线（§4 全部）+ 模型配置（§5）：**v1 功能完整** | 三成员群：无 @ 全员轮流必答、@ 单答、@全体必答、参与开关生效、主持最后发言、单成员失败不拖垮整轮、取消终止整轮、排队消息回合后补跑；历史改写 golden 测试过 | 4–6 天 |
| P3 | @ 补全、会话列表群头像、气泡模型小标（§6 后四行） | 视觉验收 + 补全插入格式正确 | 1–2 天 |
| P4 | iOS 同步 + Android 备份（§7） | 双设备建群同步；备份→恢复群与群会话完整 | 1–2 天 |

双端并行推进（代码是刻意复制的两份，每阶段都双端同时改）；P0–P4 即此前说的「v1 双端 1–2 周」。

### 实施进度（2026-11-07，P0–P4 全部代码完成）

| 阶段 | Android | iOS |
|---|---|---|
| P0 迁移+建表 | ✅ 完成，MigrationTestHelper 真机回放 1→2 通过 | ✅ 代码完成，**待 Mac 编译验证** |
| P1 GroupStore+UI | ✅ GroupStore/AgentGroupSheet（建群+选群+群管理+统一模型开关）/FAB 长按菜单入口，编译通过 | ✅ ChatStore 群 CRUD + GroupStore.swift 门面 + AgentGroupSheet.swift（列表/表单/统一模型/删除）+ 工具栏「发起群聊」入口（双列表挂载 sheet） |
| P2 发送流水线 | ✅ speakerRole（7 归因点切换）/selectGroupSpeakers/runGroupRound/rebuildAgentHistoryForSpeaker/模型链/群队列 drain，编译+单测通过 | ✅ 同构镜像（baseSystemPromptOverride + groupEntryOverride 注入机制，loop 本体不动）；**待 Mac 编译验证** |
| P3 补全/头像/模型小标/角色模型编辑行 | ✅ @补全（群聊 @ 弹成员，复用文件提及 popup）、列表群头像堆叠、气泡模型小标（占位符+重载双路）、角色编辑「默认模型」行 | ✅ @补全成员分支（mentionMenu/键盘/groupMentionCandidates）、列表群头像（双行变体）、角色编辑「默认模型」Picker；模型小标**未做**（iOS RawMessage 不携带模型快照，见下） |
| P4 同步/备份 | ✅ BackupCategory.GROUPS（data/agent_groups.json 导出/导入 + GroupStore.refreshAfterRestore + 备份屏分类项） | ✅ AgentGroupV2 全链路：SyncedAgentGroup 声明 + 注册表 + 水合器（build/merge LWW/deletion 只删群行不级联）+ GroupStore CRUD markDirty |

已知 v1 简化 / 待办：群会话逐成员上下文检查不弹 N 次对话框（沿用会话级单次检查）；群会话历史重建暂不注入 compact marker 摘要；**iOS 气泡模型小标未做**（iOS 模型归属快照在 RawMessage 持久化层之外，接线点与 Android 不同，留待 iOS 编译验证后补）；iOS AgentGroupAvatarStack 无响应式刷新（依赖列表行重渲染）。

> **移除记录（2026-11-07）**：原 P5（v2 路由选人：gateway 隐藏层 `im_group_router` 模型自动点名；v3 Agent 互 @：成员输出中的 @ 交给调度器续排）从计划中移除。理由：路由依赖 gateway 后端新增隐藏层模型（跨仓库，非本仓库可完成）；成本已被 D2（全员必答 + 参与开关 + @ 点名）覆盖；互 @ 的价值需 v1 真实使用反馈验证。`agent_groups.max_rounds` 列保留为零成本预留，将来重启此项无需迁移。

## 9. 测试要点

1. **迁移**（Android）：`MigrationTestHelper` 1→2、1→2→1（no-op 降级）；重点校验 `agent_groups` 建表与实体注解逐列一致。
2. **@解析**：共享向量 `src/shared/groupchat/mention_vectors.json`（双端同跑）：全形态——`@全体/@所有人/@all`、精确名、前缀歧义（长名优先）、无 @、@不存在、名字含空格、行首/行中、连续多个 @。
3. **历史改写**：golden 测试——同一会话对成员 A/B/C 各自重建，断言：自己的 assistant 轮保留 parts、他人轮变 `[名]:` user 轮、工具块跳过、媒体占位、连续改写轮合并、compact marker 透传。
4. **调度**：fake provider 驱动 `runGroupRound`——顺序（含主持最后）、参与开关过滤、单成员抛错继续、取消中断整轮、排队 drain 逐条成轮。
5. **模型解析**：`role.model` 空/有效/实例已删三态；删除 provider 实例后群回合回退默认模型。
6. **双端一致性**：`agent_groups` schema、`members_json` 格式、`role.json` model 字段、@触发词集合——四处双端共享同一份定义（§9.2 的向量文件 + schema 注释互指）。

## 10. 风险与边界

| 风险 | 处理 |
|---|---|
| 成本放大：一条消息 = N 次全量历史调用 | 群设置/建群页展示成员模型标签；`@` 点名是天然的省钱手段；参与开关剔除不相关成员。不做硬性人数上限 |
| 成员模型能力不齐（如不支持视觉的用户发图） | 群层不做特殊处理，交给现有 provider 管线降级；群层只保证单成员失败隔离（§4.3） |
| 回合中角色被删 / provider 实例被删 | `resolveMembers` 剔除 + systemInfo 提示（§3.3）；模型回退默认 + banner（§5） |
| 连续 user 轮的 provider 兼容性 | 改写时合并连续轮（§4.4）；现有管线本就存在连续 user 轮（tool result），风险低 |
| `runAgentLoop` 双端各 ~3k 行，侵入面失控 | 只改 `speakerRole` 一个抽象点（§4.1），loop 本体不动；code review 以此为线 |
| Android 迁移写错清库 | P0 单列阶段 + MigrationTestHelper 进 CI，先于一切功能合入 |
| 群回合持锁时间长（跨会话 slot 被占） | 与单会话长任务同权重，现有 T-STALL-DIAG 看护机制覆盖，无新增处理 |

## 附录 A：iOS 逐文件改动清单

| 文件 | 改动 |
|---|---|
| `src/ios/Agent/Chat/ChatStore.swift` | `agent_groups` 建表 + `sessions.group_id` 列（幂等迁移）；群 CRUD SQL（仿 folders）；`ChatSession` 加 `groupId`（含 equality/hash）；`createSession(…groupId:)` 变体 |
| `src/ios/Agent/Session/GroupStore.swift` **(新)** | 门面：snapshot 缓存、`createGroup/updateGroup/deleteGroup/resolveMembers`、`.groupChanged` 通知 |
| `src/ios/Agent/Session/RoleStore.swift` | `RoleMetadata`/`RoleFile` 加 `model`（`RoleModelRef?`）；`role.json` 读写兼容缺省；角色编辑相关 |
| `src/ios/Agent/Chat/AIChatViewModel.swift` | `speakerRole` 属性；`baseSystemPrompt` 改用 speakerRole；`send()` 群分支：`parseMentions` → `runGroupRound`；typing indicator 名字源 |
| `src/ios/Agent/Chat/AIChatViewModel+Persistence.swift` | 群会话创建路径（`ensureSessionReturningId` 分支）；`buildMemberHistory` 改写器（新函数）；assistant 落库盖 `speakerRole.roleId` |
| `src/ios/Agent/Chat/AIChatViewModel+ProviderFactory.swift` | `resolveSpeakerModel(group, role)`：§5 优先级链（群覆盖 > 角色 > 默认）逐级降级 + banner |
| `src/ios/Agent/Chat/AIChatViewModel+SSEStream.swift` | （若流式落库处引用 sessionRole）改 speakerRole |
| `src/ios/Agent/Chat/ChatModels.swift` | `MentionResult`；群回合状态（当前发言成员、进度）供 UI |
| `src/ios/Agent/Sync/V2/SyncedTypesExtensions.swift` + `RoleSyncExtensions.swift` | `SyncedRole.modelJson` 字段 + build/merge |
| `src/ios/Agent/Chat/ChatStore.swift`（同步部分） | `agent_groups`/`group_id` 按 folders 的注册方式进同步 |
| `src/ios/Views/Chat/CreateGroupSheet.swift` **(新)** / `GroupSettingsView.swift` **(新)** | §6 |
| `src/ios/Views/Chat/AIChatView.swift` / `ChatInputBar.swift` / `ChatMessageViews.swift` | 群分支 UI、@ 补全、模型小标 |
| `src/ios/Views/ContentView.swift` | 新建群聊入口；会话列表行（头像堆叠、角色筛选排除） |
| `src/ios/Agent/Session/RoleStore.swift`（`RoleSystemPromptBuilder` 处） | 不改 identitySection；新 `groupSection` 模板（放 GroupStore 或独立 `GroupPromptBuilder`） |

## 附录 B：Android 逐文件改动清单

| 文件 | 改动 |
|---|---|
| `…/data/db/AppDatabase.kt` | `exportSchema = true`、`version = 2`、`AgentGroupEntity` 入 entities、`GroupDao`、`addMigrations(MIGRATION_1_2)` |
| `…/data/db/AgentGroupEntity.kt` **(新)** / `GroupDao.kt` **(新)** | §2.1 |
| `…/data/db/ChatSessionEntity.kt` | `group_id` 列 + 索引；equality 约定注释同步 |
| `…/data/db/ChatDao.kt` | 群会话查询支持（按 group 过滤、删除群会话） |
| `…/data/repository/ChatRepository.kt` | `createSession(…groupId)` 变体 |
| `…/agent/GroupStore.kt` **(新)** | object + `prime()` + `StateFlow` snapshot + CRUD/resolveMembers（仿 RoleStore.kt） |
| `…/agent/RoleStore.kt` | `RoleMetadata`/`RoleFile` 加 `model`；`role.json` 读写兼容 |
| `…/ui/chat/ChatViewModel.kt` | `_speakerRole` StateFlow；`buildSystemPrompt()` 用 speakerRole；`sendMessage` 群分支（parseMentions→runGroupRound）；`buildMemberHistory`；assistant 落库盖 speakerRole.roleId；provider 解析按 §5 优先级链（群覆盖 > 角色 > 默认） |
| `…/ui/chat/ChatModels.kt` | `MentionResult`、群回合状态 |
| `…/ui/chat/RolePickerSheet.kt` | 角色编辑「默认模型」行；（建群复用其行渲染 + 多选） |
| `…/ui/chat/CreateGroupSheet.kt` **(新)** / `…/ui/*/GroupSettingsScreen.kt` **(新)** | §6 |
| `…/ui/chat/ChatScreen.kt` | 群分支 UI、@ 补全、输入栏 |
| `…/ui/chat/ChatAssistantMessageUI.kt` / `ChatFlatItems.kt` | 群会话作者头部模型小标（读 `model_display_name`） |
| `…/ui/sessions/SessionListScreen.kt` / `SessionListViewModel.kt` | 群会话行（头像堆叠）；`roleFilter` 排除群会话 |
| `…/agent/SoulStore.kt`（`SystemPromptBuilder`） | 不改 identitySection；新 `groupSection` 模板 |
| `…/backup/BackupExporter.kt` / `BackupImporter.kt` + `BackupCategory` | `GROUPS` 类目；`CHATS` 带上 group_id；`GroupStore.refreshAfterRestore()` |
| `app/build.gradle.kts` / `schemas/**` | P0：提交 schema 1.json/2.json |
| `src/shared/groupchat/mention_vectors.json` **(新)** + 拷贝任务 | §9.2，对齐 bashism 共享向量模式 |

---

**不做清单**（防 scope creep）：IM/Matrix 线（D1）；智能跳过（D2）；群自定义头像（§3.1）；群内子线程；跨群成员消息引用；群覆盖改写角色自身模型（覆盖仅解析期生效，§5）。
