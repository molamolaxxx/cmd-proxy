# Fast Team 技术实施方案：cmd-proxy 章节（协作稿）

> 文档角色：供 MolaChat 主文档合并的 cmd-proxy 侧章节  
> 维护边界：本文件由 Code Cmd Dev 维护；MolaChat 主文档由 Code Chat Dev 唯一合并  
> 日期：2026-07-30  
> 状态：设计稿，所有实施进度初始均为未完成

## 1. 本章范围与已对齐约束

对于现有纯本机 V1 Team，cmd-proxy 是 Team 定义、成员身份、状态机、持久化和 ACP
运行时的单一权威源。MolaChat 保存可重建投影、消息记录和 UI 状态，通过
`acpTeamList`、`acpTeamGet` 与 cmd-proxy 对账。对于本机与 remote 混选 Team，权威
边界改为“MolaChat 持有最小全局 placement，参与 cmd-proxy 各自权威管理本地
fragment”，详见第 1.1 节链接的新 MVP 基线。

V1 已对齐以下契约：

- `teamId`、`teamMemberId` 是不可变 UUID/ULID；同一来源 robot 加入不同 Team 时获得不同 teamMemberId。
- Team 成员逻辑 ID：
  - `robotId = team-acp-{teamMemberId}`
  - `acpClientId = team-acp-{teamMemberId}`（V1 与 robotId 相同）
  - `robotGroup = team-acp`（仅逻辑分类）
- RPC 使用实例级稳定 `transportGroup = team-acp-{cmdProxyInstanceId}`。
- `teamId + teamMemberId/acpClientId` 负责 transportGroup 内逻辑路由。
- `sourceGroupId` 只用于定位来源普通 robot、解析本地配置及归属校验，不作为 Team client 身份。
- Team 不进入普通 `acpSyncRobots`；普通 robot 的全量同步、覆盖和删除不得影响 Team。
- V1 不创建 Team/member 动态 RPC group，不依赖 `CmdReceiver.unregister`。
- 创建、删除以 `requestId` 幂等；创建采用全有或全无。
- Team talkTo 仅允许本队临时通讯录中的成员，按 teamMemberId 严格隔离。
- **除 Team talkTo 通讯录限制外，Team client 的 schedule、memory 读写、subAgent、MCP、模型、代理、权限、上下文压缩、能力配置等均与来源 robot 的正常模式一致。**

“配置一致”不等于“运行时身份共用”：Team client 必须拥有独立进程、ACP session、历史目录、schedule owner 和消息队列，不能与主 client 串状态。

### 1.1 已废弃的 Remote 整队 Placement 决策

2026-08-08 曾记录的“单 Team 单 placement、owner 单活 placement、cmd-proxy 0 改动”
方案已被用户明确作废。不得继续据此实现或验收；remote-only Team 仍明确禁止。

当前 MVP 必须允许一支 Team 同时包含本机 cmd-proxy 成员和一个或多个 remote
cmd-proxy 成员。最小权威模型、fragment 复用、补偿式 create/delete、成员级路由和
跨实例 talkTo 契约统一以
[Fast Team 本机与 Remote ACP 混选 MVP](fast-team-remote-mixed-mvp.md) 为准。旧的完整
跨 chatter V2 方案只保留为增强可靠性和来源授权的后续储备。

## 2. cmd-proxy 在端到端流程中的节点

### 2.1 cmd-proxy 启动

1. 取得 `CmdProxyHome.instanceId()`。
2. 注册一次实例级 Team transport provider/callback consumer：
   - transportGroup：`team-acp-{instanceId}`
   - commands：本章第 6 节定义的所有 `acpTeam*`
   - callback：统一 `acpTeamEvent`
3. 初始化 `TeamManager`、`TeamStore`、`TeamClientRegistry`、`TeamScheduleRegistry`。
4. 从 `$CMD_PROXY_HOME/teams/` 加载 Team 权威定义和 tombstone。
5. 对未删除 Team：
   - V1：标记为 `RECOVERING`，恢复定义和投影；按恢复策略重建新 ACP session。
   - 后续：若 journal 中 sessionId 可恢复，则使用 provider 支持的 `session/load`/`session/resume`。
6. 启动资源 reaper，清理超期 tombstone、归档和孤儿 runtime。
7. `acpTeamList/Get` 可用后发送 `TEAM_SNAPSHOT_READY` 事件，MolaChat 发起对账。

### 2.2 创建 Team

1. MolaChat 在任一已知 cmd-proxy 通道提交 `acpTeamCreate`。
2. `TeamCommandHandler` 校验：
   - requestId、teamId、teamMemberId 格式；
   - teamId/requestId 幂等记录；
   - ownerChatterId 和调用者权限；
   - 成员数、Team 数、实例 client 数配额；
   - sourceGroupId 对应普通 AcpClient 存在；
   - sourceRobotName 与本地 source client 匹配；
   - 同一 Team 不重复选择同一 source robot（V1）。
3. `TeamManager` 以 `putIfAbsent + operationLock` 创建 `CREATING` 定义并原子落盘。
4. 为每个 member 从来源 robot 建立配置快照：
   - 完整复制 `AcpRobotParam` 中正常模式字段；
   - 保持 memory 读写配置不变；
   - 保持 scheduleEnabled 不变；
   - 保持 subAgents、MCP、provider、model、apiKey 引用策略、codexHome、代理等不变；
   - 仅替换 talkTo contacts 为本 Team 临时通讯录。
5. 生成 Team 临时通讯录。每个 member 的 contacts 是“其他全部队员”，路由键为 teamMemberId/acpClientId。
6. 受全局 semaphore 控制并行创建 Team ACPClient：
   - 独立进程；
   - 强制 `session/new`；
   - history namespace：`team/{teamId}/{teamMemberId}`；
   - listener：`TeamAcpResponseListener`；
   - schedule owner：`team:{teamId}:{teamMemberId}`；
   - memory：按来源 robot 的正常配置初始化，读写开关不变；
   - talkTo：注入 Team 专属 dispatcher/context injector；
   - 其他能力：走与普通 client 相同的初始化器。
7. 全员 READY 后：
   - 原子切换 Team 为 `READY`；
   - 持久化成员 sessionId/runtime 摘要；
   - 发布 talkTo 路由；
   - 发送 `TEAM_READY`。
8. 任一成员失败：
   - Team 进入 `ROLLING_BACK`；
   - 关闭全部已创建 client；
   - 清 schedule runtime、talkTo inbox、listener；
   - Team 进入 `FAILED` 并落盘；
   - 发送包含逐成员错误的 `TEAM_CREATE_FAILED`。

### 2.3 向 Team member 发送消息

1. MolaChat 发送 `acpTeamSend(teamId, teamMemberId/acpClientId, message, files)`。
2. TeamManager 校验 Team 为 READY、member 存在、client 非 DELETING/CLOSED。
3. client 状态 READY 时接受；BUSY 时默认返回 `MEMBER_BUSY`，不在入口层无限排队。
4. `TeamAcpResponseListener` 将所有 chunk/tool/status/complete/error 转成统一 `acpTeamEvent`。
5. 事件显式携带 teamId、teamMemberId、acpClientId、transportGroup、eventId、eventSeq；不得只靠普通 groupId 或 robotName 路由。

### 2.4 Team 内 talkTo

1. 每个 Team client 首轮 harness 注入本队临时通讯录。
2. 通讯录条目包含：
   - targetTeamMemberId；
   - targetAcpClientId；
   - displayName；
   - remark（成员职责/能力摘要）。
3. remark 在创建 Team 时一次解析并冻结，优先级：
   - create 请求显式成员职责；
   - 来源 robot `ability.md` 摘要；
   - 来源 robot `signature`；
   - 默认成员说明。
4. Team prompt 必须明确“只能联系通讯录内成员”，不能沿用普通 TalkTo 的“也可向未列出的 Agent 发送”文案。
5. `TeamTalkToDispatcher` 只查询当前 TeamRuntime：
   - target 必须解析为本队 teamMemberId/acpClientId；
   - READY 直接投递；
   - BUSY 进入该 Team member 的 inbox；
   - inbox、去重 key、depth 全部包含 teamId 和 memberId；
   - 禁止退回普通 `robotRegistry`、全局 talkTo 或跨 chatter ad-hoc 路由。
6. TALK_TO_SEND/RECEIVE/QUEUED/REJECTED 均通过 `acpTeamEvent` 上报。

### 2.5 Team schedule

Team 模式保留来源 robot 的完整 schedule 能力和 `scheduleEnabled` 配置，但任务身份必须与主会话隔离。

1. Team client 使用 `ScheduleOwnerKey(scope=TEAM, teamId, teamMemberId)`，不能继续把 source robotName 作为唯一 key。
2. Team task 持久化到：
   - `$CMD_PROXY_HOME/schedules/team/{teamId}/{teamMemberId}/tasks.json`
3. 普通 task 继续保留现有目录，迁移时兼容读取：
   - `$CMD_PROXY_HOME/schedules/{robotName}/tasks.json`
4. Team task 到期后必须回调 TeamClientRegistry 中同一 member，不能通过普通 groupRobotMap 按 robotName 反查。
5. 执行语义与正常模式一致：
   - member READY 时新建/切换到新的 Team member ACP session 后执行；
   - 重新装配 memory、subAgent、schedule、Team talkTo 等全部能力；
   - 使用 `PromptOptions.forScheduleExecution()` 防止定时任务递归创建任务；
   - BUSY 时回 WAITING，下一轮重试；
   - cron/once/MISSED 行为保持正常模式。
6. 删除 Team 时取消并删除该 Team 的 schedule tasks；已经进入执行阶段的任务纳入 client cancel/end 屏障。

### 2.6 Team memory 与其他 ACP 能力

- `MemoryConfig.readEnabled/writeEnabled/scope/baseDir/...` 完整继承来源 robot，Team 不再默认关闭写入。
- Memory 的语义按正常模式：
  - workspace scope：写入同 workspace 的项目记忆；
  - robot scope：沿用来源 robot 的 robot memory namespace，使 Team 与来源 robot 保持知识连续性。
- 因普通主 client 与多个 Team client 可能同时写同一 memory store，建议引入 `MemoryManagerRegistry/MemoryScopeLock`，按实际 memory storage key 复用 manager 或串行化索引写入，避免 index/entry 丢更新。
- memory 提取间隔、full extract、dream、访问强化、手动管理行为与正常模式一致。
- subAgent 白名单与配置完整继承来源 robot，仍使用普通全局 robot registry 解析子 Agent；Team talkTo 隔离不限制 dispatch_subagent。
- MCP config 路径、模型、apiKey/codexHome 解析、HTTP 代理、permission 自动处理、compaction/harness 重注入全部复用正常实现。
- ability auto refresh 按来源 robot 能力域处理。推荐复用来源 robot 的 AbilityReflectionService/结果，避免每个 Team member 重复启动反思进程；这不改变 abilityAutoRefresh 的有效性。

### 2.7 切换 Team/主会话

- 切换纯属 MolaChat 视图操作，cmd-proxy 不关闭、不暂停、不迁移 client。
- Team client 后台 turn、talkTo、schedule 按正常状态继续执行。
- MolaChat 通过 eventSeq 补齐切走期间事件；cmd-proxy 的 listener 不依赖当前 UI 是否打开。
- `acpTeamGet` 返回当前 Team/member 状态和 latestEventSeq，供页面恢复。

### 2.8 删除 Team

1. `acpTeamDelete(requestId, teamId, expectedVersion?)` 幂等占位。
2. 状态原子切换到 `DELETING`，立即拒绝新 send、talkTo 和 schedule 创建。
3. 停止该 Team schedule owner，清 inbox/去重，阻止新后台任务。
4. 对全部 member：
   - BUSY 时先 `session/cancel`；
   - 发送 `session/end`；
   - 等待 2~3 秒；
   - 超时 `process.destroy()`；
   - 最终兜底 `destroyForcibly()`。
5. 等待/终止 client executor，注销 listener，关闭 subAgent dispatcher 和相关资源。
6. 从 TeamClientRegistry、TeamRuntime、schedule cache、talkTo route 移除。
7. 会话历史移动至 `$CMD_PROXY_HOME/team-archive/{teamId}`，默认 7 天 TTL。
8. 写入 DELETED tombstone，再发 `TEAM_DELETED`；MolaChat 据此删除投影。
9. 关闭失败进入 `DELETED_WITH_WARNINGS`，reaper 持续尝试回收，但不允许 Team 重新接收业务请求。

## 3. 新增模型

### 3.1 持久化领域模型

#### TeamDefinition

```text
teamId: String
ownerChatterId: String
name: String
state: TeamState
version: long
transportGroup: String
createRequestId: String
members: List<TeamMemberDefinition>
createdAt: long
updatedAt: long
deletedAt: Long?
lastError: TeamError?
```

#### TeamMemberDefinition

```text
teamMemberId: String
robotId: String                  // team-acp-{teamMemberId}
acpClientId: String              // V1 与 robotId 相同
robotGroup: String               // team-acp
sourceGroupId: String
sourceRobotName: String
displayName: String
remark: String
configFingerprint: String
state: TeamMemberState
sessionId: String?
lastError: TeamError?
```

#### TeamState

```text
CREATING
READY
RECOVERING
ROLLING_BACK
FAILED
DELETING
DELETED
DELETED_WITH_WARNINGS
```

#### TeamMemberState

```text
STARTING
READY
BUSY
ERROR
CLOSING
CLOSED
```

#### TeamOperationRecord

```text
requestId: String
operation: CREATE | DELETE
payloadHash: String
teamId: String
status: ACCEPTED | SUCCEEDED | FAILED
resultSnapshot: Object?
createdAt: long
expiresAt: long
```

同一 requestId + 同 payload 返回原结果；同一 requestId + 不同 payload 返回 `IDEMPOTENCY_CONFLICT`。

#### TeamTombstone

```text
teamId: String
deleteRequestId: String
finalState: DELETED | DELETED_WITH_WARNINGS
deletedAt: long
expireAt: long
warnings: List<TeamError>
```

### 3.2 运行时模型

#### TeamRuntime

```text
definition: AtomicReference<TeamDefinition>
operationLock: ReentrantLock
generation: long
members: ConcurrentMap<teamMemberId, TeamMemberRuntime>
dispatcher: TeamTalkToDispatcher
eventSequencer: AtomicLong
acceptingRequests: AtomicBoolean
createdAt / lastActiveAt
```

#### TeamMemberRuntime

```text
teamMemberId: String
acpClientId: String
sourceRobotSnapshot: AcpRobotParam
clientIdentity: AcpClientIdentity
client: AcpClient
listener: TeamAcpResponseListener
scheduleOwnerKey: ScheduleOwnerKey
state: AtomicReference<TeamMemberState>
generation: long
```

#### AcpClientIdentity

建议把普通与 Team client 共有身份抽成显式对象：

```text
scope: MAIN | TEAM | SUB_AGENT | MEMORY | ABILITY
logicalId: String
transportGroup: String
historyNamespace: String
ownerChatterId: String?
teamId: String?
teamMemberId: String?
sourceRobotName: String?
```

不能再让一个 `groupId` 同时承担 RPC 地址、历史目录、chatterId 推导和业务身份。

#### TeamContactRef

```text
targetTeamMemberId: String
targetAcpClientId: String
displayName: String
remark: String
```

Team 不直接使用缺少 memberId 的普通 `ContactRef`。

#### ScheduleOwnerKey

```text
scope: MAIN | TEAM
ownerId: String
robotName: String?
teamId: String?
teamMemberId: String?
persistencePath: String
```

### 3.3 命令与事件 DTO

- `TeamCreateCommand`
- `TeamMemberCreateSpec`
- `TeamSendCommand`
- `TeamCancelCommand`
- `TeamDeleteCommand`
- `TeamQuery`
- `TeamCommandResult`
- `TeamEventEnvelope`
- `TeamError`

DTO 必须有协议版本字段 `schemaVersion`，未知新增字段应被旧端忽略。

## 4. 内存结构与持久化

### 4.1 内存结构

```text
TeamManager
  teams: ConcurrentMap<teamId, TeamRuntime>
  operations: ConcurrentMap<requestId, TeamOperationRecord>
  createExecutor: bounded ExecutorService
  cleanupExecutor: bounded ExecutorService
  startupSemaphore: Semaphore

TeamClientRegistry
  clients: ConcurrentMap<TeamClientKey(teamId, teamMemberId), AcpClient>

TeamScheduleRegistry
  owner -> task list / running state / execution route
```

默认配额建议：

- 每 chatter 最多 5 个活跃 Team；
- 每 Team 1～6 个 member；
- 每 cmd-proxy 实例最多 20 个 Team client；
- 同时启动 ACP 进程最多 4 个；
- 每 member talkTo inbox 10 条，TTL 30 分钟。

### 4.2 磁盘布局

```text
$CMD_PROXY_HOME/
  teams/
    {teamId}/
      team.json                 # TeamDefinition，权威元数据
      runtime.json              # sessionId、eventSeq、恢复摘要
    operations/
      {requestId}.json          # 有 TTL 的幂等记录
    tombstones/
      {teamId}.json
  session/
    team/
      {teamId}/
        {teamMemberId}/
          {sessionId}/...
  schedules/
    team/
      {teamId}/
        {teamMemberId}/
          tasks.json
  team-archive/
    {teamId}/...
```

写入要求：

- 先写同目录临时文件、flush，再原子 move 覆盖；
- TeamDefinition 每次状态迁移 version +1；
- 内存状态只有在权威元数据写入成功后才对外发布；
- 启动时发现临时文件或 CREATING/DELETING 中间态，按 operation journal 恢复或回滚；
- apiKey、token、完整代理凭据不重复写 TeamDefinition，只保存 source 配置引用和 fingerprint。

### 4.3 Memory 持久化

Memory 不放入 `teams/`：

- 继续使用现有 MemoryConfig 决定 baseDir 和 scope；
- Team 的 memory 读写与来源 robot 正常模式共享语义；
- Team 删除不删除已经提取的长期 memory；
- Team 会话历史归档不等于删除 memory；
- 同一实际 memory scope 的写操作需要共享锁或单例 manager。

## 5. 并发与生命周期保障

### 5.1 创建/删除竞态

- TeamManager 先用 `putIfAbsent` 占据 teamId。
- 每 Team 使用 operationLock 串行 create/delete/recover。
- 每个异步任务捕获 generation，提交前、启动后、发布事件前都校验 generation。
- delete 增加 generation 并关闭 acceptingRequests，旧 create future 即使完成也只能自清理，不能把 Team 改回 READY。

### 5.2 Send/close 竞态

- AcpClient 增加 lifecycle lock 或 compare-and-set 状态屏障。
- CLOSED/DELETING 后后台 send task 不得再把状态写回 READY/ERROR。
- listener 每次发送前校验 Team/member generation；删除后的迟到 chunk 丢弃并记录 debug 日志。

### 5.3 资源回收

- `AcpProxy.stop()` 调用 `TeamManager.closeAll()`，并等待有界时间。
- JVM shutdown hook 做协议关闭和进程兜底。
- reaper 周期检查：
  - registry 无定义的孤儿 client；
  - DELETED_WITH_WARNINGS 的进程；
  - 超期 operation/tombstone/archive；
  - 无对应 Team 的 schedule owner；
  - 长期 CREATING/DELETING 中间态。

## 6. 命令与事件契约

### 6.1 通用信封

所有请求：

```text
schemaVersion
requestId
transportGroup
teamId?
expectedVersion?
payload
```

所有同步响应：

```text
requestId
accepted: boolean
code
message
teamVersion?
data?
```

所有事件：

```text
schemaVersion
eventId
eventSeq
transportGroup
teamId
teamMemberId?
acpClientId?
type
teamVersion
timestamp
data
```

### 6.2 Commands

| 命令 | 关键请求字段 | 同步结果 |
|---|---|---|
| `acpTeamCreate` | requestId, teamId, ownerChatterId, name, members[{teamMemberId, sourceGroupId, sourceRobotName, displayName?, remark?}] | ACCEPTED / ALREADY_EXISTS / VALIDATION_ERROR / IDEMPOTENCY_CONFLICT |
| `acpTeamList` | ownerChatterId?, sinceVersion? | 权威 Team 摘要列表 |
| `acpTeamGet` | teamId | TeamDefinition 投影、member 状态、latestEventSeq |
| `acpTeamSend` | requestId, teamId, teamMemberId/acpClientId, message, files? | ACCEPTED / MEMBER_BUSY / TEAM_NOT_READY / NOT_FOUND |
| `acpTeamCancel` | requestId, teamId, teamMemberId/acpClientId | ACCEPTED / NOT_RUNNING / NOT_FOUND |
| `acpTeamNewSession` | requestId, teamId, teamMemberId | 新建独立 Team member session |
| `acpTeamListSessions` | teamId, teamMemberId, limit? | Team history namespace 内会话列表 |
| `acpTeamRestoreSession` | requestId, teamId, teamMemberId, sessionId | 仅允许恢复该 member namespace 内 session |
| `acpTeamGetStatus` | teamId, teamMemberId? | Team 或 member 状态 |
| `acpTeamGetContextUsage` | teamId, teamMemberId | context usage |
| `acpTeamDelete` | requestId, teamId, expectedVersion? | ACCEPTED / ALREADY_DELETED / VERSION_CONFLICT |

Team client 内部的 `schedule_task/manage_schedule/dispatch_subagent/talk_to` 仍由 harness 拦截，不需要 MolaChat 单独发命令。

### 6.3 Events

统一 callback：`acpTeamEvent`

| type | member 可空 | data |
|---|---:|---|
| `TEAM_CREATE_ACCEPTED` | 是 | Team 摘要 |
| `TEAM_READY` | 是 | 全成员状态 |
| `TEAM_CREATE_FAILED` | 是 | 逐成员错误 |
| `TEAM_STATE_CHANGED` | 是 | from/to/reason |
| `MEMBER_STATE_CHANGED` | 否 | from/to/sessionId? |
| `MESSAGE_CHUNK` | 否 | content |
| `MESSAGE_COMPLETE` | 否 | stopReason? |
| `MESSAGE_ERROR` | 否 | code/message/retryable/content（content 与普通 ACP 错误帧一致） |
| `TOOL_CALL` | 否 | toolCallId/title/status/sanitized detail |
| `TALK_TO_SEND` | 否 | targetTeamMemberId/content summary |
| `TALK_TO_RECEIVE` | 否 | senderTeamMemberId/content |
| `TALK_TO_QUEUED` | 否 | queuePosition |
| `TALK_TO_REJECTED` | 否 | reason/targetTeamMemberId/content |
| `SCHEDULE_EVENT` | 否 | create/manage/trigger/result |
| `COMPACTION_EVENT` | 否 | provider/status |
| `TEAM_DELETED` | 是 | warnings/archiveExpireAt |
| `TEAM_SNAPSHOT_READY` | 是 | snapshotVersion |

事件要求：

- eventSeq 至少在 Team 内单调递增；
- eventId 全局唯一，MolaChat 按 eventId 去重；
- 状态事件必须带 teamVersion，旧版本不能覆盖新投影；
- 大文件内容沿用现有 files 机制，不直接塞入事件；
- callback 失败要记录，V2 增加事件短期落盘/补拉机制。

### 6.4 错误码

```text
VALIDATION_ERROR
UNAUTHORIZED
NOT_FOUND
TEAM_NOT_READY
MEMBER_BUSY
QUOTA_EXCEEDED
IDEMPOTENCY_CONFLICT
VERSION_CONFLICT
SOURCE_ROBOT_NOT_FOUND
SOURCE_ROBOT_MISMATCH
CLIENT_START_FAILED
CLIENT_CLOSED
TEAM_DELETING
INBOX_FULL
INTERNAL_ERROR
```

## 7. 功能拆分

### F0：通用 client identity 与优雅关闭

- 引入 AcpClientIdentity/historyNamespace。
- 把 transport route 与逻辑 client identity 分离。
- close 改为 cancel/end/wait/destroy/forcibly。
- 解决 close 后异步状态回跳和迟到 callback。

### F1：Team 权威模型与持久化

- TeamDefinition/member/state/operation/tombstone。
- TeamStore 原子写、加载、恢复、版本控制。
- cmd-proxy 权威 list/get。

### F2：Team RPC 与事件

- 实例级 transportGroup 注册。
- Team commands、统一 TeamEvent。
- Team listener 和 eventSeq。

### F3：Team client 创建与能力装配

- TeamClientRegistry。
- 配置快照与 fingerprint。
- 全有或全无并行创建/回滚。
- 抽取普通/Team 共用 AcpClientFeatureInitializer，确保除 talkTo 外配置行为一致。

### F4：Team talkTo

- TeamContactRef、remark 解析。
- 严格队内 context injector。
- TeamTalkToDispatcher/inbox/去重/清理。

### F5：Schedule 与 Memory 一致性

- ScheduleOwnerKey 与 Team schedule 路由/持久化。
- Team schedule 执行时重建同作用域 client。
- Memory 正常读写启用。
- Memory scope 共享锁/manager registry。

### F6：删除、恢复与 reaper

- 幂等删除状态机。
- Team 全资源关闭和历史归档。
- 启动恢复、中间态恢复、孤儿回收。

### F7：测试、观测与限额

- 单元、集成、竞态、压力、进程泄漏测试。
- Team/client 数、创建耗时、清理耗时、失败原因、reaper 指标。
- 配额与可配置项。

## 8. 文件级预计改动

路径以 `cmd-proxy-app/src/main` 为基准。

### 8.1 新增文件

| 文件 | 作用 |
|---|---|
| `java/.../acp/team/TeamManager.java` | Team 权威状态机与编排 |
| `java/.../acp/team/TeamStore.java` | team/operation/tombstone 原子持久化 |
| `java/.../acp/team/TeamClientRegistry.java` | `(teamId, teamMemberId) -> AcpClient` |
| `java/.../acp/team/TeamCommandHandler.java` | acpTeam commands 参数校验和调用 |
| `java/.../acp/team/TeamResourceReaper.java` | 孤儿进程、任务、归档和中间态回收 |
| `java/.../acp/team/model/TeamDefinition.java` | Team 持久模型 |
| `java/.../acp/team/model/TeamMemberDefinition.java` | member 持久模型 |
| `java/.../acp/team/model/TeamState.java` | Team 状态 |
| `java/.../acp/team/model/TeamMemberState.java` | member 状态 |
| `java/.../acp/team/model/TeamOperationRecord.java` | 幂等操作记录 |
| `java/.../acp/team/model/TeamTombstone.java` | 删除墓碑 |
| `java/.../acp/team/model/TeamError.java` | 结构化错误 |
| `java/.../acp/team/model/TeamContactRef.java` | 带 memberId 的临时通讯录 |
| `java/.../acp/team/runtime/TeamRuntime.java` | Team 内存态 |
| `java/.../acp/team/runtime/TeamMemberRuntime.java` | member 内存态 |
| `java/.../acp/team/talkto/TeamTalkToDispatcher.java` | 队内严格隔离投递 |
| `java/.../acp/team/talkto/TeamTalkToContextInjector.java` | 队内白名单 harness |
| `java/.../acp/team/model/TeamContactRef.java#from` | 从 member 固化 remark 生成临时联系人 |
| `java/.../acp/team/listener/TeamAcpResponseListener.java` | 统一 TeamEvent callback |
| `java/.../acp/team/event/TeamEventEnvelope.java` | 事件信封 |
| `java/.../acp/team/event/TeamEventSequencer.java` | Team eventSeq |
| `java/.../acp/acpclient/AcpClientIdentity.java` | 显式 client 身份 |
| `java/.../acp/acpclient/AcpClientFeatureInitializer.java` | 普通/Team 公共能力装配 |
| `java/.../acp/schedule/ScheduleOwnerKey.java` | MAIN/TEAM schedule owner |
| `java/.../acp/memory/MemoryManagerRegistry.java` | 同 memory scope manager/锁复用 |

DTO 可放在 `acp/team/protocol/` 下，按 command/result/event 分文件。

### 8.2 修改文件

| 文件 | 预计改动 |
|---|---|
| `java/.../acp/acpclient/AbstractAcpClient.java` | identity、协议优先 close、等待/强杀兜底、lifecycle guard |
| `java/.../acp/acpclient/AcpClient.java` | historyNamespace、schedule owner、可插拔 talkTo、关闭后状态保护 |
| `java/.../acp/acpclient/context/ConversationHistoryManager.java` | 支持显式相对 namespace，并校验不能逃逸 session root |
| `java/.../acp/acpclient/AcpClientRegistry.java` | 保持只管 MAIN，或增加 scope 防误收 Team |
| `java/.../acp/schedule/ScheduleTaskManager.java` | robotName key 泛化为 ScheduleOwnerKey；Team 执行路由与清理 |
| `java/.../acp/schedule/model/ScheduledTask.java` | owner/schemaVersion（如采用单文件兼容模型） |
| `java/.../acp/memory/MemoryManager.java` | 配合 registry/共享锁，保持读写配置不变 |
| `java/.../acp/talkto/TalkToContextInjector.java` | 普通行为保持不变；公共 remark resolver 可抽取 |
| `kotlin/.../acp/AcpProxy.kt` | 初始化 TeamManager、注册 transport、抽取公共能力装配、stop 时 closeAll |
| `kotlin/.../Main.kt` | 启动 Team 模块、传入 instanceId/transportGroup |
| `java/.../utils/CmdProxyHome.java` | teams/team-archive 路径 helper（可选） |
| `kotlin/.../client/provider/CmdReceiver.kt` | 支持实例级 Team group 注册；V1 不要求 unregister |
| `java/.../acp/common/InstanceRegistry.java` | 暴露/同步 instance transport 信息（如 MolaChat discovery 需要） |

### 8.3 测试文件

建议新增：

```text
src/test/java/.../acp/team/TeamManagerTest.java
src/test/java/.../acp/team/TeamStoreTest.java
src/test/java/.../acp/team/TeamIdempotencyTest.java
src/test/java/.../acp/team/TeamLifecycleRaceTest.java
src/test/java/.../acp/team/TeamTalkToDispatcherTest.java
src/test/java/.../acp/team/TeamScheduleIntegrationTest.java
src/test/java/.../acp/team/TeamMemoryIntegrationTest.java
src/test/java/.../acp/team/TeamResourceReaperTest.java
src/test/java/.../acp/team/TeamProtocolContractTest.java
```

## 9. 测试与验收

### 9.1 创建与权威状态

- 2 人、6 人创建成功。
- 任一 member 启动失败时全员回滚，无遗留 client/process/session route。
- 相同 requestId + 相同 payload 返回相同结果。
- 相同 requestId + 不同 payload 返回 IDEMPOTENCY_CONFLICT。
- 相同 source robot 可加入不同 Team，robotId 不冲突。
- Team 从不出现在 acpSyncRobots payload，不被普通 sync/delete 影响。
- cmd-proxy 重启后 list/get 权威状态可恢复，MolaChat 投影可重建。

### 9.2 client 与历史隔离

- Team member 与来源主 robot 的 PID、sessionId、history namespace 均不同。
- 两个 Team 复用同一来源 robot 时历史不串。
- Team 绝不读取来源 robot 的 lastSessionId。
- Team session restore 只能恢复自身 namespace。
- Team client 创建失败不得触发对主进程的 `kill -9`。

### 9.3 talkTo

- 只能投递给同 Team 临时通讯录中的 member。
- 使用 teamMemberId/acpClientId 路由，不查询普通 robotRegistry。
- READY 直投、BUSY 入队、inbox 满拒绝、TTL 超期清理。
- 两个 Team 中相同 displayName 不串消息。
- depth 和重复消息限制只在对应 Team 内生效。
- Team 删除后 send/receive/queued 全部拒绝且队列清空。
- remark 优先级和快照行为正确。

### 9.4 Schedule

- scheduleEnabled 与来源 robot 配置一致。
- Team 可创建/list/update/cancel cron 和 once 任务。
- Team task 只触发对应 Team member，不触发来源主 robot或其他 Team。
- Team task 持久目录与普通 task 隔离。
- 任务触发后的新 session 仍装配 memory/subAgent/Team talkTo/MCP 等能力。
- Team 删除清理所有 WAITING/RUNNING task。
- cmd-proxy 重启后的 MISSED 行为与正常模式一致。

### 9.5 Memory 与其他配置

- memory readEnabled/writeEnabled 完整继承，不再强制关闭写入。
- workspace scope 和 robot scope 均按来源正常语义读写。
- 主 client 与多个 Team client 并发提取不会损坏 index 或丢 entry。
- subAgent、MCP、model、proxy、permission、compaction 行为与来源正常 client 一致。
- Team talkTo 的严格白名单不会误限制 dispatch_subagent。

### 9.6 删除与资源回收

- READY/BUSY/ERROR/CREATING/RECOVERING 状态均可幂等删除。
- delete 与 create/send/talkTo/schedule 并发时 Team 不复活。
- close 顺序为 cancel/end/wait/destroy/forcibly。
- 删除 100 次后进程、线程、文件句柄、registry、inbox、schedule cache 回到基线。
- provider 不响应时进入 DELETED_WITH_WARNINGS，reaper 最终清理。
- history 归档及 TTL 清理正确；memory 不被 Team 删除误删。

### 9.7 RPC 与事件

- 多 cmd-proxy 实例 transportGroup 不冲突。
- eventId 去重、eventSeq 单调、teamVersion 防状态倒退。
- 迟到 chunk 不发送到已删除 Team。
- callback 暂时失败不破坏 Team 权威状态。
- sourceGroupId 只用于来源解析，不作为 Team 消息主键。

### 9.8 回归

- 普通 ACP send/new/restore/cancel/status/context usage 不变。
- 普通 talkTo/crossTalkTo 不变。
- 普通 schedule、memory、subAgent、ability reflection 不变。
- robot reload 和 AcpProxy.stop 正确处理 MAIN 与 TEAM 各自范围。
- 多环境 InstanceRegistry 和 acpSyncRobots 行为不回退。

验收硬门槛：

1. Team client 不得加载或关闭来源主会话。
2. Team talkTo 不得逃逸当前 Team。
3. schedule/memory 写入及其他 ACP 配置必须与正常模式一致。
4. create/delete 必须幂等且无僵尸 ACP 进程。
5. 普通 acpSyncRobots 不得创建、覆盖或删除 Team。

## 10. Code Cmd Dev 实施进度来源

> 本节是 cmd-proxy 侧唯一进度来源，初始全部未完成。Code Chat Dev 合并到主文档后，只从本节同步 cmd-proxy 进度，不在两处分别维护状态。

### Phase 0：基础身份与生命周期

- [x] FT-CMD-001：引入 `AcpClientIdentity`，拆分逻辑身份、transportGroup、historyNamespace。
  - 完成证据：`AcpClientIdentityTest` 4/4 通过；MAIN 兼容身份与 TEAM 分离身份均已覆盖。
  - 验证命令：`mvn -pl cmd-proxy-app -am -Dtest=AcpClientIdentityTest -Dsurefire.failIfNoSpecifiedTests=false test`
  - 修改文件：`AcpClientIdentity.java`、`AbstractAcpClient.java`、`AcpClient.java`、`AcpClientIdentityTest.java`、`cmd-proxy-app/pom.xml`。
- [x] FT-CMD-002：`ConversationHistoryManager` 支持安全的 Team history namespace。
  - 完成证据：`ConversationHistoryManagerTest` 6/6 通过；覆盖 Team 分层路径、MAIN 兼容清洗、父目录穿越、Linux/Windows 绝对路径和空路径段。
  - 验证命令：`mvn -pl cmd-proxy-app -am -Dtest=ConversationHistoryManagerTest -Dsurefire.failIfNoSpecifiedTests=false test`
  - 修改文件：`ConversationHistoryManager.java`、`AcpClient.java`、`ConversationHistoryManagerTest.java`。
- [x] FT-CMD-003：实现 `session/cancel → session/end → wait → destroy → destroyForcibly` 关闭链路。
  - 完成证据：`AbstractAcpClientShutdownTest` 5/5 通过；覆盖 BUSY 时 session/cancel→session/end 顺序、session/end 优雅退出、destroy 兜底、destroyForcibly 最终兜底及无 session 直接清理。
  - 验证命令：`mvn -pl cmd-proxy-app -am -Dtest=AbstractAcpClientShutdownTest -Dsurefire.failIfNoSpecifiedTests=false test`
  - 修改文件：`AbstractAcpClient.java`、`AcpClient.java`、`AbstractAcpClientShutdownTest.java`。
- [x] FT-CMD-004：增加 lifecycle/generation guard，阻止关闭后状态回跳和迟到 callback。
  - 完成证据：`AcpClientLifecycleGuardTest` 3/3 通过；覆盖 close 失效旧 generation、重复 close 幂等及全部 listener 迟到事件拦截。
  - 验证命令：`mvn -pl cmd-proxy-app -am -Dtest=AcpClientLifecycleGuardTest -Dsurefire.failIfNoSpecifiedTests=false test`
  - 修改文件：`AbstractAcpClient.java`、`AcpClient.java`、`LifecycleGuardedAcpResponseListener.java`、`AcpClientLifecycleGuardTest.java`。
- [x] FT-CMD-005：限制或移除 session 冲突时直接 `kill -9` 主进程的路径。
  - 完成证据：`AcpClientSessionConflictPolicyTest` 2/2 通过；带/不带 PID 的占用错误只做识别。源码扫描确认 `AcpClient` 已无 `kill -9`、PID 解析和 `ProcessBuilder("kill")` 路径。
  - 验证命令：`mvn -pl cmd-proxy-app -am -Dtest=AcpClientSessionConflictPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test`；`rg 'kill\\s*-9|new ProcessBuilder\\(\"kill\"|tryKillConflictingProcess|PID_PATTERN' AcpClient.java` 无匹配。
  - 修改文件：`AcpClient.java`、`AcpClientSessionConflictPolicyTest.java`。

Phase 0 统一回归证据（2026-07-30）：执行
`mvn -pl cmd-proxy-app -am -Dtest=AcpClientIdentityTest,ConversationHistoryManagerTest,AbstractAcpClientShutdownTest,AcpClientLifecycleGuardTest,AcpClientSessionConflictPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test`，
共运行 20 个测试，Failures 0、Errors 0、Skipped 0，`BUILD SUCCESS`。本阶段未创建或修改 `TeamManager`、`TeamClientRegistry`。

### Phase 1：Team 权威模型与 transport

- [x] FT-CMD-101：实现 TeamDefinition、TeamMemberDefinition、状态与错误模型。
  - 完成证据：`TeamModelTest` 5/5 通过；覆盖稳定 member identity、1～6 人及 memberId 唯一约束、Team version 递增、终态保护、结构化错误和 tombstone。
  - 实际模型：`schemaVersion=1`；`robotId=acpClientId=team-acp-{teamMemberId}`；`robotGroup=team-acp`；成员同时固化 `sourceRobotId`、`sourceGroupId`、`avatar`、`order`、来源配置快照 fingerprint；Team/Member 状态及 15 个标准错误码已固化。
  - 验证命令：`mvn -pl cmd-proxy-app -am -Dtest=TeamModelTest -Dsurefire.failIfNoSpecifiedTests=false test`
  - 修改文件：`team/model/TeamDefinition.java`、`TeamMemberDefinition.java`、`TeamState.java`、`TeamMemberState.java`、`TeamError.java`、`TeamErrorCode.java`、`TeamOperationRecord.java`、`TeamTombstone.java`、`TeamModelTest.java`。
- [x] FT-CMD-102：实现 TeamStore 原子持久化、version、operation、tombstone。
  - 完成证据：`TeamStoreTest` 5/5 通过；覆盖临时文件写入+FileChannel.force+原子 move、严格 next-version CAS、安全路径、operation/tombstone 独立 namespace 及稳定加载顺序。
  - 实际布局：`teams/{teamId}/team.json`、`teams/operations/{requestId}.json`、`teams/tombstones/{teamId}.json`；根目录默认来自 `CmdProxyHome.resolve("teams")`。
  - 验证命令：`mvn -pl cmd-proxy-app -am -Dtest=TeamStoreTest -Dsurefire.failIfNoSpecifiedTests=false test`
  - 修改文件：`team/TeamStore.java`、`TeamStoreTest.java`。
- [x] FT-CMD-103：实现 TeamManager 与 TeamClientRegistry。
  - 完成证据：`TeamManagerTest` 4/4 通过；覆盖持久定义幂等恢复且不启动 client、TEAM identity 强校验、与普通 registry 隔离、统一 close 和重复 close 幂等。
  - 本批边界：`TeamManager` 仅提供恢复、内存占位、快照和关闭；未实现 create/list/get command、ACP 启动或能力装配。
  - 验证命令：`mvn -pl cmd-proxy-app -am -Dtest=TeamManagerTest -Dsurefire.failIfNoSpecifiedTests=false test`
  - 修改文件：`team/TeamManager.java`、`TeamClientRegistry.java`、`team/runtime/TeamRuntime.java`、`TeamClientKey.java`、`TeamManagerTest.java`。
- [x] FT-CMD-104：注册实例级 `team-acp-{instanceId}` transportGroup。
  - 完成证据：`TeamTransportProtocolTest` 6/6 通过，且 Maven Kotlin compile 成功；覆盖多实例 group 隔离、安全 instanceId、describe 响应、未就绪/业务就绪 command 集合和 sync discovery 编码。
  - 实际注册：`AcpProxy.start()` 使用 `CmdProxyHome.instanceId()` 注册 `acpTeamDescribe` 到 `team-acp-{instanceId}`；该注册同时建立实例 group 的 callback consumer，统一 callback 名为 `acpTeamEvent`。
  - discovery：现有 `acpSyncRobots` resultMap 新增 `teamSchemaVersion`、`teamCmdProxyInstanceId`、`teamTransportGroup`、`teamDiscovery`；其中 `teamDiscovery` 是 `TeamTransportDescriptor` JSON。
  - describe resultMap：字符串字段 `schemaVersion`、`requestId`、`accepted`、`code`、`message`、`data`；`data` 为 descriptor JSON。权威状态恢复成功后 `businessCommandsReady=true`；FT-CMD-104 首次落地时 commands 为 describe/create/list/get，FT-CMD-204 完成后扩为 11 个命令，后续能力一致性修复加入 `acpTeamMemoryDream`，当前共 13 个命令（describe + 12 个业务命令）；若恢复失败则保持 `false` 且只暴露 describe。
  - 验证命令：`mvn -pl cmd-proxy-app -am -Dtest=TeamTransportProtocolTest -Dsurefire.failIfNoSpecifiedTests=false test`
  - 修改文件：`team/protocol/TeamTransportDescriptor.java`、`TeamTransportProtocol.java`、`AcpProxy.kt`、`TeamTransportProtocolTest.java`。
- [x] FT-CMD-105：实现 `acpTeamCreate/List/Get` 与 requestId 幂等。
  - 完成证据：`TeamCommandHandlerTest` 6/6 通过；覆盖首次 create 与同载荷精确重放、同 requestId 异载荷冲突、owner 隔离的 list/get、进程重启后 operation 恢复、来源 robot 不匹配和单参数契约。
  - create 语义：校验并固化 MolaChat 提供的 `teamId/teamMemberId`，验证 `sourceGroupId` 对应 `enabled=true && onlySubAgent=false` 的 Team 可用来源；`onlyTeamMember` 不影响 Team 准入，只决定是否创建 MAIN ACP。持久化来源身份/展示元数据和完整来源配置的 SHA-256 fingerprint，不持久化凭据或完整配置；按 `ACCEPTED operation → CREATING TeamDefinition → SUCCEEDED operation` 落盘。当前只返回“定义已持久化、成员启动待后续阶段”，不启动 Team ACPClient。
  - robot 角色语义：`enabled=false` 不启动 MAIN 且不可被 Team 使用；`enabled=true && onlyTeamMember=true` 只作为 Team Member 来源，不启动 MAIN；`enabled=true && onlyTeamMember=false && onlySubAgent=false` 同时启动 MAIN 并可被 Team 使用。`onlyTeamMember` 默认 false，且与 `onlySubAgent` 互斥。
  - discovery：普通 `robots` 只包含实际启动 MAIN 的 robot；Fast Team 候选通过 `teamDiscovery.teamMemberSources` 独立发布，字段仅包含 owner/source identity、展示信息和 `onlyTeamMember`，不得包含 apiKey 或代理凭据。
  - `remark` 实际赋值：优先使用来源 `AcpRobotParam.signature.trim()`；signature 为空时使用 `Team member based on {robotName}`。V1 create DTO 不接收用户自定义 remark，后续 `TeamContactRef.remark` 直接读取这份固化值，避免把 robotId 当作展示说明。
  - 幂等语义：相同 `requestId` 与相同 canonical payload 返回原结果；相同 `requestId` 与不同 payload 返回 `IDEMPOTENCY_CONFLICT`；成功 operation 可在 cmdproxy 重启后从持久定义精确重放。
  - 验证命令：`mvn -pl cmd-proxy-app -am -Dtest=TeamCommandHandlerTest -Dsurefire.failIfNoSpecifiedTests=false test`
  - 修改文件：`team/TeamCommandHandler.java`、`TeamManager.java`、`MapTeamSourceRobotResolver.java`、`TeamSourceRobotResolver.java`、`TeamSourceResolutionException.java`、`team/protocol/TeamCreateCommand.java`、`TeamMemberCreateSpec.java`、`TeamQuery.java`、`TeamCommandResult.java`、`team/model/TeamMemberDefinition.java`、`TeamCommandHandlerTest.java`、`AcpProxy.kt`。
- [x] FT-CMD-106：实现 TeamEventEnvelope、eventId/eventSeq/teamVersion。
  - 完成证据：`TeamEventContractTest` 3/3 通过；覆盖同 Team runtime 内 eventSeq 单调递增、teamVersion 透传、纯字符串 resultMap/data JSON 编码，以及 `acpTeamEvent` callback 的 command/group/cmdId。
  - 事件字段：`schemaVersion`、`eventId`、`eventSeq`、`transportGroup`、`teamId`、可选 `teamMemberId/acpClientId`、`type`、`teamVersion`、`timestamp`、`data`。`CmdResponseContent.cmdId=eventId`。
  - 当前边界：eventSeq 在单个 `TeamRuntime` 生命周期内单调递增，cmdproxy 重启后从 0 重新开始；事件持久化与补拉仍归 FT-CMD-503，不在本批伪装成已具备。
  - 验证命令：`mvn -pl cmd-proxy-app -am -Dtest=TeamEventContractTest -Dsurefire.failIfNoSpecifiedTests=false test`
  - 修改文件：`team/event/TeamEventEnvelope.java`、`TeamEventType.java`、`TeamEventCodec.java`、`TeamEventSink.java`、`TeamCallbackSender.java`、`RpcTeamEventSink.java`、`team/runtime/TeamRuntime.java`、`TeamManager.java`、`TeamEventContractTest.java`。
- [x] FT-CMD-107：实现 TeamAcpResponseListener。
  - 完成证据：`TeamAcpResponseListenerTest` 5/5 通过；覆盖 message/tool/subAgent/schedule/talkTo/compaction/complete/error 映射、成员身份归属校验、runtime 关闭后的迟到 callback 丢弃。
  - 当前边界：本类只是后续 Team member runtime 可注入的 listener 骨架；本批没有创建 ACPClient，也没有改变普通 ACP 的 feature initializer、talkTo、schedule 或 memory。
  - 验证命令：`mvn -pl cmd-proxy-app -am -Dtest=TeamAcpResponseListenerTest -Dsurefire.failIfNoSpecifiedTests=false test`
  - 修改文件：`team/listener/TeamAcpResponseListener.java`、`TeamAcpResponseListenerTest.java`。

FT-CMD-101～104 统一回归证据（2026-07-30）：执行
`mvn -pl cmd-proxy-app -am test`，共运行 9 个测试类、39 个测试，
Failures 0、Errors 0、Skipped 0，`BUILD SUCCESS`；其中本批新增 19 个测试，
Phase 0 原有 20 个测试全部保持通过。普通 ACP 的 schedule、memory、subAgent、
talkTo 和其他能力装配均未在本批修改。

FT-CMD-105～107 统一回归证据（2026-07-30）：执行
`mvn -pl cmd-proxy-app -am test`，共运行 12 个测试类、54 个测试，
Failures 0、Errors 0、Skipped 0，`BUILD SUCCESS`。`businessCommandsReady`
仅在 `TeamManager.recoverPersistedDefinitions()` 成功且 create/list/get 契约测试与恢复测试
通过后置为 `true`；恢复异常时保持只读 discovery。普通 ACP 的 schedule、memory、
subAgent、talkTo 和其他能力装配仍未修改。

#### FT-CMD-105～107 实际 RPC 契约

所有业务 command 的 `cmdArgs` 都必须严格包含一个非空 JSON 字符串：

- `acpTeamCreate`：`cmdArgs[0]={"schemaVersion":"1","requestId":"req-01","teamId":"team-01","ownerChatterId":"owner-01","name":"Fast Team","members":[{"teamMemberId":"member-01","sourceRobotId":"acp-codex","sourceGroupId":"source-group","order":0},{"teamMemberId":"member-02","sourceRobotId":"acp-kiro","sourceGroupId":"source-group","order":1}]}`。
- `acpTeamList`：`cmdArgs[0]={"schemaVersion":"1","ownerChatterId":"owner-01"}`。
- `acpTeamGet`：`cmdArgs[0]={"schemaVersion":"1","ownerChatterId":"owner-01","teamId":"team-01"}`。`sinceVersion` 字段当前仅预留，调用方 V1 应省略。

三个 command 的返回值均为 `Map<String,String>`，公共字段为
`schemaVersion/requestId/accepted/code/message`，成功时可附带 `teamVersion/data`。
`data` 本身是 JSON 字符串，不是嵌套 map：

- create：`{"schemaVersion":"1","requestId":"req-01","accepted":"true","code":"ACCEPTED","message":"Team definition persisted; member startup pending","teamVersion":"1","data":"<TeamDefinition JSON>"}`。这里 requestId 使用 payload 的幂等键。
- list：`{"schemaVersion":"1","requestId":"<RPC cmdId>","accepted":"true","code":"OK","message":"Team list loaded","teamVersion":"<snapshotVersion>","data":"{\"teams\":[...],\"snapshotVersion\":1}"}`。
- get：`{"schemaVersion":"1","requestId":"<RPC cmdId>","accepted":"true","code":"OK","message":"Team loaded","teamVersion":"1","data":"{\"team\":{...},\"latestEventSeq\":1}"}`。

本批实际成功码为 `ACCEPTED`、幂等重放时的 `ALREADY_EXISTS` 和 `OK`。错误均返回
`accepted=false`；当前路径可返回 `VALIDATION_ERROR`、`UNAUTHORIZED`、`NOT_FOUND`、
`IDEMPOTENCY_CONFLICT`、`VERSION_CONFLICT`、`SOURCE_ROBOT_NOT_FOUND`、
`SOURCE_ROBOT_MISMATCH`、`INTERNAL_ERROR`，runtime 热重载/未就绪时返回
`TEAM_NOT_READY`。

callback 注册固定为
`CmdReceiver.INSTANCE.callback("acpTeamEvent", transportGroup, CmdResponseContent(eventId, resultMap))`。
callback 的 resultMap 同样全为字符串，`data` 为 JSON 字符串；create 成功落盘后实际发送
`TEAM_CREATE_ACCEPTED`。`TeamAcpResponseListener` 已定义 `MESSAGE_CHUNK`、
`MESSAGE_COMPLETE`、`MESSAGE_ERROR`、`TOOL_CALL`、`SUB_AGENT_EVENT`、
`SCHEDULE_EVENT`、`TALK_TO_SEND/RECEIVE/QUEUED`、`COMPACTION_EVENT` 映射，
但只有后续 Team ACPClient 注入 listener 后才会产生这些成员事件。callback 发送失败
不会回滚 cmdproxy 的权威 Team 状态。

### Phase 2：Team client 与正常能力装配

- [x] FT-CMD-201：在 FT-CMD-105 已固化来源身份/展示元数据与 config fingerprint 的基础上，实现启动 Team ACPClient 所需的配置快照读取和漂移检测。
  - 完成证据：`TeamSourceRobotSnapshotTest` 2/2 通过；覆盖包含 provider/model/proxy/MCP、memory、schedule、subAgent 等字段及运行时凭据的内存深拷贝、创建后来源对象变更不污染快照、持久定义不出现 apiKey/完整配置，以及 fingerprint 漂移拒绝恢复。
  - 安全边界：完整 `AcpRobotParam` 只保存在启动过程的内存快照；`team.json` 仅保存来源身份/展示字段和 64 位 SHA-256 fingerprint，不落盘 apiKey、proxy credential 或完整 robot 配置。
  - 漂移语义：启动前按 `sourceGroupId + sourceRobotId` 重新解析当前启用且非 onlySubAgent 的 Team 来源；`onlyTeamMember` 不计入运行配置 fingerprint，其他运行配置 fingerprint 不一致返回 `VERSION_CONFLICT` 并进入整队失败回滚。自动接受配置漂移的兼容策略仍需双方后续确认。
  - 验证命令：`mvn -pl cmd-proxy-app -am -Dtest=TeamSourceRobotSnapshotTest -Dsurefire.failIfNoSpecifiedTests=false test`
  - 修改文件：`team/TeamSourceRobotSnapshot.java`、`TeamSourceRobotSnapshots.java`、`TeamSourceRobotResolver.java`、`TeamManager.java`、`TeamSourceRobotSnapshotTest.java`。
- [x] FT-CMD-202：抽取 `AcpClientFeatureInitializer`，普通与 Team 共用。
  - 完成证据：`AcpClientFeatureInitializerTest` 2/2 通过；验证 MAIN/TEAM 均按固定顺序经过 memory、ability reflection、subAgent、schedule、talkTo 五个能力挂点，并暴露 Team 独立 featureOwnerKey 与 sourceGroupId。
  - 普通模式：冷启动、new session、restore session、schedule 触发新 session 和 robot 热重载均已改为调用同一个 initializer，避免能力漏装。
  - Team 模式：`AcpClient` 使用完整来源配置启动，因此 provider/model/apiKey/proxy/MCP/permission/compaction 等构造期行为与普通模式一致；memory 按 sourceGroupId 复用来源 `MemoryManager`，没有禁用读取或写入；schedule 继续注入现有 `ScheduleTaskManager/ContextInjector`，没有临时关闭。`Context.team(...)` 已为后续 `ScheduleOwnerKey`、memory scope lock 提供独立 owner 扩展点。
  - talkTo 边界：Team 初始化会调用显式 `initTeamTalkToExtension`，但绝不把 Team member 注入普通 robotRegistry；严格队内通讯录由 FT-CMD-301 在该扩展点接入。
  - 验证命令：`mvn -pl cmd-proxy-app -am -Dtest=AcpClientFeatureInitializerTest -Dsurefire.failIfNoSpecifiedTests=false test`
  - 修改文件：`acpclient/AcpClientFeatureInitializer.java`、`AcpProxy.kt`、`AcpClientFeatureInitializerTest.java`。
- [x] FT-CMD-203：实现全有或全无并行创建、限流、超时和回滚。
  - 完成证据：`TeamStartupCoordinatorTest` 3/3、`TeamLifecycleStartupTest` 2/2 通过；覆盖全部成员成功后才注册、单成员失败关闭全部已启动 client、360 秒超时语义、无部分 registry 残留、CREATING→READY/FAILED 原子持久化、事件及 create 原始幂等快照保持。
  - 运行策略：实例级固定线程池，全局最大并行数 `min(4, availableProcessors)`，单 Team 整体启动超时 360 秒；每个 member 使用 `AcpClientIdentity.team`、独立 history namespace 和 `TeamAcpResponseListener`。只有全部 client `start + feature initialize` 成功后才批量进入 TeamClientRegistry。
  - 成功状态：Team `CREATING(v1) → READY(v2)`；全部 member `STARTING → READY`，保存各自 sessionId，发送 `TEAM_READY`。
  - 失败状态：Team `CREATING(v1) → FAILED(v2)`；直接失败/超时成员为 `ERROR`，已启动后被回滚的成员为 `CLOSED`，Team/Member `lastError` 使用结构化错误；关闭并清空该 Team 的所有 client，发送 `TEAM_CREATE_FAILED`。
  - 幂等边界：首次 `acpTeamCreate` 仍立即返回 v1 `ACCEPTED`；无论后台随后 READY 或 FAILED，同 requestId 重放都精确返回最初的 ACCEPTED resultMap，不把状态变化混入幂等响应。最新状态通过 list/get/event 获取。
  - 恢复边界：启动恢复会继续启动持久化的 CREATING Team；READY/FAILED 的完整进程恢复策略仍属于 FT-CMD-503。
  - 验证命令：`mvn -pl cmd-proxy-app -am -Dtest=TeamStartupCoordinatorTest,TeamLifecycleStartupTest -Dsurefire.failIfNoSpecifiedTests=false test`
  - 修改文件：`team/TeamMemberClientStarter.java`、`TeamStartupCoordinator.java`、`TeamManager.java`、`TeamClientRegistry.java`、`team/model/TeamDefinition.java`、`team/protocol/TeamCommandResult.java`、`AcpProxy.kt`、`TeamStartupCoordinatorTest.java`、`TeamLifecycleStartupTest.java`。

FT-CMD-201～203 统一回归证据（2026-07-30）：执行
`mvn -pl cmd-proxy-app -am test`，共运行 16 个测试类、63 个测试，
Failures 0、Errors 0、Skipped 0，`BUILD SUCCESS`。本批新增 9 个测试，
此前 54 个测试全部保持通过。

#### TEAM_READY / TEAM_CREATE_FAILED 实际事件 data

事件 envelope/resultMap 编码沿用 FT-CMD-106；以下是 `data` JSON 的实际结构。成功时：

`{"team":{"schemaVersion":"1","teamId":"team-01","state":"READY","version":2,"members":[...]},"members":[{"teamMemberId":"member-01","acpClientId":"team-acp-member-01","state":"READY","sessionId":"<ACP sessionId>","lastError":null},{"teamMemberId":"member-02","acpClientId":"team-acp-member-02","state":"READY","sessionId":"<ACP sessionId>","lastError":null}]}`

失败时：

`{"team":{"schemaVersion":"1","teamId":"team-01","state":"FAILED","version":2,"lastError":{"code":"CLIENT_START_FAILED","message":"member startup failed","retryable":true,"details":{},"timestamp":...},"members":[...]},"members":[{"teamMemberId":"member-01","state":"CLOSED","sessionId":null,"lastError":{"code":"CLIENT_START_FAILED",...}},{"teamMemberId":"member-02","state":"ERROR","sessionId":null,"lastError":{"code":"CLIENT_START_FAILED",...}}],"error":{"code":"CLIENT_START_FAILED","message":"member startup failed","retryable":true,"details":{},"timestamp":...}}`

来源配置缺失/不匹配/fingerprint 漂移时，`error.code` 保留
`SOURCE_ROBOT_NOT_FOUND`、`SOURCE_ROBOT_MISMATCH` 或 `VERSION_CONFLICT`；
ACP 进程、initializer 或超时失败统一为 `CLIENT_START_FAILED`。外层事件的
`type` 分别为 `TEAM_READY` / `TEAM_CREATE_FAILED`，`teamVersion=2`。
- [x] FT-CMD-204：实现 `acpTeamSend/Cancel/NewSession/ListSessions/RestoreSession/GetStatus/GetContextUsage`。
  - 实际命令：`acpTeamSend`、`acpTeamCancel`、`acpTeamNewSession`、`acpTeamListSessions`、`acpTeamRestoreSession`、`acpTeamGetStatus`、`acpTeamGetContextUsage`；全部注册在实例级 `team-acp-{instanceId}`，并加入 ready discovery command 列表。
  - 路由门禁：每次调用都验证 `schemaVersion/ownerChatterId/teamId/teamMemberId`；可选 `acpClientId` 一旦携带必须与 member 定义一致。send/new/restore 仅允许 Team 和 member/client 均为 READY，不回退普通 registry。
  - send 状态：原子持久化 member `READY→BUSY` 并发送 `MEMBER_STATE_CHANGED` 后调用 AcpClient 异步 send；Team listener 收到 complete/error 后再持久化 `BUSY→READY/ERROR`。重复发送返回 `MEMBER_BUSY`。
  - session：new/restore 只关闭并替换目标 member client，新 client 继续使用相同 Team identity/history namespace，且完整重跑 `AcpClientFeatureInitializer`；替换期间 `READY→STARTING→READY/ERROR`，不影响同队其他成员。
  - 完成证据：`TeamMemberCommandTest` 4/4、`TeamAcpResponseListenerTest` 6/6、`TeamTransportProtocolTest` 6/6 通过；覆盖 send/files、owner/member/client 隔离、cancel/status/context/session list、new/restore 和 listener 状态回写。
  - 修改文件：`team/protocol/TeamMemberCommand.java`、`TeamTransportProtocol.java`、`TeamTransportDescriptor.java`、`TeamCommandHandler.java`、`TeamManager.java`、`TeamMemberStartOptions.java`、`TeamMemberClientStarter.java`、`TeamStartupCoordinator.java`、`team/listener/TeamMemberStateObserver.java`、`TeamAcpResponseListener.java`、`AcpProxy.kt` 及对应测试。
- [x] FT-CMD-205：确保 MCP、model、proxy、subAgent、permission、compaction 等与正常模式一致。
  - Team 与普通模式使用同一具体 `AcpClient`、同一 `AgentProviderRouter` 和同一 `AcpClientFeatureInitializer`；Team 仅替换 `AcpClientIdentity` 与 response listener，因此 provider/model/apiKey/proxy/MCP 解析、permission 自动响应、provider compaction 识别与 harness 重注入走完全相同代码。
  - subAgent 仍使用来源 robot 的完整配置与普通 `SubAgentDispatcher/ContextInjector`；listener 已将 subAgent、tool、compaction 事件映射到 TeamEvent。Team talkTo 仍停留在严格隔离扩展点，没有提前接普通 dispatcher。
  - 完成证据：`TeamCapabilityParityTest` 验证 MAIN/TEAM 使用同一 client class、同一来源配置与 MCP 路径解析；`AcpClientFeatureInitializerTest` 验证五能力挂点无漏装；`TeamAcpResponseListenerTest` 验证 subAgent/tool/compaction 映射。
- [x] FT-CMD-206：复用/协调来源 robot AbilityReflectionService，避免重复反思进程。
  - `AcpClientFeatureInitializer.Context.team` 同时携带 Team 独立 `featureOwnerKey` 和 `sourceGroupId`；ability hook 始终用 `sourceGroupId` 访问 `abilityServices.getOrPut`，因此主 client 与任意 Team member 复用同一来源 robot 的 AbilityReflectionService，不按 `teamMemberId` 重复创建反思服务。
  - memory 同样按 sourceGroupId 复用 manager，但并发写锁仍归 FT-CMD-405；schedule owner 隔离仍归 FT-CMD-401，本批没有关闭两项正常能力。

#### FT-CMD-204 实际 command payload/result

每个 command 仍严格使用单一 JSON `cmdArgs[0]`。公共路由字段：
`{"schemaVersion":"1","ownerChatterId":"owner-01","teamId":"team-01","teamMemberId":"member-01","acpClientId":"team-acp-member-01"}`；
`acpClientId` 可省略，其余字段必填。

- send：公共字段加 `"message":"请分析","files":[{"design.png":"<Base64>"},{"spec.pdf":"https://host/spec.pdf"}]`。成功 code=`QUEUED`。
- cancel：仅公共字段。成功 code=`OK`。
- new session：仅公共字段。成功 code=`OK`。
- list sessions：公共字段可加 `"limit":7`，范围 1～50。成功 code=`OK`。
- restore session：公共字段加 `"sessionId":"..."`。成功 code=`OK`。
- get status / get context usage：仅公共字段。成功 code=`OK`。

所有 resultMap 继续使用 FT-CMD-105 的字符串 envelope；`data` 是 JSON 字符串。成员公共 data：
`{"teamId":"team-01","teamState":"READY","teamMemberId":"member-01","acpClientId":"team-acp-member-01","memberState":"BUSY","clientState":"BUSY","sessionId":"..."}`。
send 另加 `queued:true`；new/restore 另加 `restored:false/true`；list 加
`sessions:[{"sessionId":"...","preview":"...","lastModified":"yyyy-MM-dd HH:mm","current":true}]`；
context 加 `contextUsagePercentage:42.5`。错误使用
`VALIDATION_ERROR/UNAUTHORIZED/NOT_FOUND/TEAM_NOT_READY/MEMBER_BUSY/CLIENT_CLOSED/CLIENT_START_FAILED/INTERNAL_ERROR`。

Team robot 文件参数与普通 ACP 保持完全相同：`files` 是数组，每项必须是恰好一个
`filename → content` 的 JSON 对象；filename 必须是 basename，禁止 `/`、`\`、`.`、`..`；
content 必须是合法 Base64 或 `http://`/`https://` URL；单次最多 10 个文件。

#### MESSAGE / MEMBER_STATE_CHANGED 事件 data

- `MESSAGE_CHUNK`：`{"content":"流式文本片段"}`。
- `MESSAGE_COMPLETE`：`{}`。随后一定有 member `READY` 的 `MEMBER_STATE_CHANGED`。
- `MESSAGE_ERROR`：使用 `{"code":"INTERNAL_ERROR","message":"provider failed","retryable":true,"content":"====== 发生错误 ======\\nprovider failed"}`；`content` 是与普通 ACP `end=Y` 帧完全相同的用户可见内容，随后有 member `ERROR` 状态事件。
- `MEMBER_STATE_CHANGED`：`{"team":<完整最新 TeamDefinition>,"member":<完整最新 TeamMemberDefinition>,"state":"BUSY|READY|ERROR|STARTING","error":<失败时 TeamError>}`。外层 envelope 同时携带 `teamMemberId/acpClientId/teamVersion`。

FT-CMD-204～206 统一回归证据：`mvn -pl cmd-proxy-app -am test` 通过，共
18 个测试类、69 个测试，0 failure、0 error、0 skipped；`git diff --check` 通过。

### Phase 3：Team talkTo

- [x] FT-CMD-301：实现 TeamContactRef 和 remark resolver。
  - `TeamContactRef` 固化 `targetTeamMemberId/targetAcpClientId/displayName/remark`，路由字段与展示字段分离。
  - `remark` 直接读取 `TeamMemberDefinition.remark`：该值在 create 时已按“来源 robot `signature.trim()` 优先；为空时 `Team member based on {robotName}`”固化。Team talkTo 不再读取动态 ability 文件，也不把 robotId 当备注。
  - 修改文件：`team/model/TeamContactRef.java`、`TeamTalkToContextInjectorTest.java`。
- [x] FT-CMD-302：实现 TeamTalkToContextInjector 严格队内通讯录。
  - 动态读取当前 TeamDefinition，仅列出除自身外的同队 member；发送格式强制 `target=teamMemberId`。
  - 明确拒绝来源 robotName、displayName、acpClientId、`chatterId:robotName` 和未列出名称作为路由目标；传入来源 robot 的普通 contacts/global registry 会被忽略。
  - harness 明确说明 Team talkTo 白名单不限制 `dispatch_subagent/schedule/memory` 等其他 ACP 能力。
  - 修改文件：`team/talkto/TeamTalkToContextInjector.java`、`AcpProxy.kt`。
- [x] FT-CMD-303：实现 TeamTalkToDispatcher、memberId 路由、inbox、TTL、去重和 depth。
  - 每 Team 唯一 dispatcher、每目标 member 独立 FIFO inbox；路由 key 为 `teamId + teamMemberId`，只访问 `TeamClientRegistry`。
  - 仅目标 member/client 同为 READY 时直投；目标存在且 member/client 为 BUSY 时排队。STARTING/ERROR/CLOSED、client 缺失、Team 非 READY 一律拒绝，不进入 inbox。
  - inbox 容量 10、TTL 30 分钟；每个 target turn 完成回到 READY 后取一条，后续 turn 完成继续串行取下一条。TTL 在 offer/poll 时清理，过期消息不会投递。
  - 去重 key 包含 teamId、senderMemberId、targetMemberId、content SHA-256，窗口 60 秒；只保留已直投/已排队记录。最大 depth=5，队内来信回复模板显式传递 `_depth`。
  - 20 路并发入队验证容量始终不超过 10。
  - 修改文件：`team/talkto/TeamTalkToDispatcher.java`、`AcpClient.java`、`TalkToDispatcher.java`、`TeamManager.java`、`TeamTalkToDispatcherTest.java`。
- [x] FT-CMD-304：实现 talkTo TeamEvent 和删除清理。
  - dispatcher 直接发布结构化 `TALK_TO_SEND/RECEIVE/QUEUED/REJECTED`，并声明 `managesTalkToEvents=true`，避免 AcpClient 把 queued/rejected 重复误报为 SEND。
  - 直投或 inbox 取出前通过权威 state observer 持久化目标 member BUSY；正常 listener completion/error 继续回写 READY/ERROR。
  - `TeamManager.cleanupTalkTo(teamId)` 是 delete 状态机的统一清理入口；创建失败、manager close 已接入，清空 inbox 与去重缓存。FT-CMD-501 实现 delete 时复用此入口。
  - 修改文件：`TeamEventType.java`、`TeamAcpResponseListener.java`、`TeamManager.java`、`TeamManagerTest.java`。
- [x] FT-CMD-305：验证 Team talkTo 不回退普通/跨 chatter dispatcher。
  - Team dispatcher 覆盖 deliver/poll/receive card 全链路，不访问普通 robotRegistry、robotToGroupId、`crossTalkTo` callback，也不解析冒号远程路由。
  - AcpClient 的 talkTo routing name 在 TEAM scope 固定取 identity.teamMemberId；MAIN scope 仍用 robotName。
  - subAgent 与 talkTo registry 已拆成独立字段，Team 的空 talkTo registry 不会覆盖 subAgent registry；`dispatch_subagent` 检测、派发与上下文保持正常模式。
  - 完成证据：`TeamTalkToContextInjectorTest` 2/2、`TeamTalkToDispatcherTest` 6/6、`AcpClientTalkToIsolationTest` 1/1、相关 listener/manager 测试通过。

#### FT-CMD-301～305 实际 talkTo event data

四种事件共用字段：

`{"messageId":"<uuid>","teamId":"team-1","senderTeamMemberId":"member-1","senderAcpClientId":"team-acp-member-1","targetTeamMemberId":"member-2","targetAcpClientId":"team-acp-member-2","content":"消息正文","depth":1,"delivery":"..."}`。

- `TALK_TO_SEND`：直投接受时发布，`delivery="DELIVERED"`；外层 envelope 的 member 是 sender。
- `TALK_TO_RECEIVE`：直投时为 `delivery="DELIVERED"`；从 inbox 取出时为 `delivery="DELIVERED_FROM_INBOX"` 并带 `expiresAt`；外层 member 是 target。
- `TALK_TO_QUEUED`：`delivery="QUEUED"`，另带 `queuePosition/queueCapacity=10/expiresAt`；外层 member 是 sender。
- `TALK_TO_REJECTED`：即时拒绝为 `delivery="REJECTED"`，TTL 过期为 `delivery="EXPIRED"`；另带 `reason`。实际 reason 包含 `TEAM_CLOSED/TEAM_NOT_READY/SENDER_NOT_IN_TEAM/INVALID_DEPTH/DEPTH_EXCEEDED/TARGET_NOT_IN_TEAM/SELF_TARGET/EMPTY_CONTENT/DUPLICATE/INBOX_FULL/TARGET_NOT_READY/TTL_EXPIRED`；已识别 sender 时外层 member 是 sender。

#### Team talkTo 联系人与对话卡片可见性修复

排查结论：缺失不在联系人白名单构建。`TeamTalkToContextInjector` 原本已经动态列出
同队其他 member，且 dispatcher 始终只按 memberId 查找。真正缺口在卡片输出层：Team
dispatcher 声明 `managesTalkToEvents=true` 后，AcpClient 会跳过普通 talkTo 卡片；同时
Team 的 `pushIncomingMessageCard` 为避免重复曾是空实现，而 MolaChat 当前
`TeamTalkToEventSolution` 只记录 TALK_TO_* 日志，因此对话中看不到发送/接收过程。

修复后，prompt 中的联系人使用明确 JSON 卡片，每张只包含展示所需字段：

```json
{"target":"member-2","displayName":"Researcher","remark":"负责检索与事实核验"}
```

`target` 是卡片中唯一可用于路由的值，必须是不可变 `teamMemberId`。prompt 和卡片
均不暴露 `targetAcpClientId`，来源 `robotName` 也不进入候选。displayName/remark
只用于帮助模型选择联系人；dispatcher 仍会拒绝 displayName、robotName、
`team-acp-{memberId}`、acpClientId、跨 chatter 名称和 self target。

每个结构化 TALK_TO_* 事件之后，dispatcher 会向对应 member envelope 追加一个
`MESSAGE_CHUNK` 对话卡片；MolaChat 已有 Team message 流可直接渲染，无需放宽路由。
卡片 data：

```json
{
  "content": "<details class=\"tool-call team-talk-to-card\" open data-team-member-id=\"member-2\">...</details>",
  "cardType": "TEAM_TALK_TO",
  "messageId": "<与 TALK_TO_* 相同的 uuid>",
  "direction": "SEND|RECEIVE",
  "cardTargetTeamMemberId": "member-2",
  "delivery": "DELIVERED|QUEUED|DELIVERED_FROM_INBOX|REJECTED|EXPIRED",
  "reason": "<拒绝/过期时可选>"
}
```

发送卡片正文示例：

````html
<details class="tool-call team-talk-to-card" open data-team-member-id="member-2">
<summary>📤 发送 Team 消息给 member-2（Researcher）</summary>
<div class="tool-call-body">

路由 target：`member-2`

投递状态：`DELIVERED`

```
请核验这个结论
```

</div></details>
````

工具 follow-up 输出仍为：
`[talkTo 结果]\n已成功将消息发送给同队成员 Researcher（member-2）。对方会处理你的请求，你可以继续当前工作。`
忙碌时改为 Team inbox 位置提示，拒绝时给出严格路由原因。

直投会为 sender 发布 SEND 卡、为 target 发布 RECEIVE 卡；排队为 sender 发布 QUEUED
卡；inbox 取出时为 target 发布 RECEIVE 卡；已解析到合法同队 peer 的拒绝/过期会给
sender 发布 REJECTED 卡。无法解析为同队其他 member 的 hostile/self target 只保留
结构化拒绝，不生成伪联系人卡片。

修改文件：`TeamTalkToContextInjector.java`、`TeamTalkToDispatcher.java`、
`TeamTalkToCardRenderer.java` 及两项 talkTo 测试。

验证证据（2026-07-30）：联系人 prompt 与 dispatcher 卡片定向测试 8/8 通过；
完整 `mvn -pl cmd-proxy-app -am test` 仍为 28 个测试类、96/96，Failures 0、
Errors 0、Skipped 0，`BUILD SUCCESS`；独立 compile 与 `git diff --check` 通过。

FT-CMD-301～305 统一回归证据：`mvn -pl cmd-proxy-app -am test` 通过，共
21 个测试类、79 个测试，0 failure、0 error、0 skipped；`git diff --check` 通过。

### Phase 4：Schedule、Memory 与完整配置一致性

- [x] FT-CMD-401：引入 ScheduleOwnerKey，普通/Team task 身份隔离。
  - 实际字段：`scope=MAIN|TEAM`、`ownerId`、可选 `robotName`、Team 专用 `teamId/teamMemberId`、派生且经过安全校验的 `persistencePath`。
  - MAIN 的 `ownerId=robotName`，继续使用历史 storage key/目录，不迁移普通任务；TEAM 的 ownerId 是 Team `ownerChatterId`，storage key 固定为 `team/{teamId}/{teamMemberId}`，即使多个 Team 复用同一来源 robot 也不会碰撞。
  - `ScheduledTask.owner` 随任务持久化；旧普通任务缺少 owner 时从历史目录恢复，保持向后兼容。
  - 修改文件：`schedule/model/ScheduleOwnerKey.java`、`schedule/model/ScheduledTask.java`、`AcpClient.java`、`ScheduleOwnerIsolationTest.java`。
- [x] FT-CMD-402：实现 Team schedule 持久化、扫描、执行回调和删除清理。
  - 实际目录：普通任务仍为 `$CMD_PROXY_HOME/schedules/{robotName}/tasks.json`；Team 任务为 `$CMD_PROXY_HOME/schedules/team/{teamId}/{teamMemberId}/tasks.json`。
  - `ScheduleTaskManager` 按 owner storage key 隔离 create/list/update/cancel、running 标记和持久化；启动时递归扫描两种目录，旧有 MISSED/cron/once 语义保持不变。
  - callback 改为显式携带 `ScheduleOwnerKey`：MAIN 仍路由普通 registry；TEAM 经 `teamId+teamMemberId` 只路由 `TeamClientRegistry`。
  - 删除清理入口为 `TeamManager.cleanupEphemeralResourcesForDelete(teamId)` → `ScheduleTaskManager.cleanupTeam(teamId)`；清除对应任务缓存、running 标记和 Team schedule 文件，普通任务及其他 Team 不受影响。普通 stop 不删除持久任务。FT-CMD-501 的 delete 状态机直接复用该入口。
  - 修改文件：`ScheduleTaskManager.java`、`TeamManager.java`、`AcpProxy.kt`、`ScheduleOwnerIsolationTest.java`。
- [x] FT-CMD-403：确保 schedule 触发新 session 后完整重装 Team 能力。
  - Team task 仅在 Team/member/client 均 READY 且 owner 匹配时接受；目标 member 进入 STARTING，优雅关闭旧 client，使用 `TeamStartupCoordinator.replaceMember(..., newSession())` 创建新 session。
  - replacement 走与正常 Team 启动相同的 `AcpClientFeatureInitializer`，重新装配 memory、ability、subAgent、schedule、Team talkTo，并保留来源 provider/model/apiKey/proxy/MCP/permission/compaction 构造行为。
  - 发送使用 `PromptOptions.forScheduleExecution()`，保留普通防递归语义；目标 member 随后进入 BUSY，其他 member/client 不替换。
  - 完成证据：`TeamMemberCommandTest` 新增 schedule replacement 场景，验证 force-new-session、非递归 prompt option、成员 BUSY 和结构化事件。
- [x] FT-CMD-404：Team 完整继承 MemoryConfig 读写行为。
  - 来源配置快照继续深拷贝完整 `MemoryConfig`；Team initializer 不修改 `readEnabled/writeEnabled/scope/model/dream` 等字段。
  - Team memory hook 使用 `sourceGroupId` 获取来源 `MemoryManager`，因此 workspace/robot scope、读取注入、按轮提取、session close 全量提取、session 计数、dream 与普通来源语义一致；Team delete 不删除共享 memory。
  - 修改文件：`AcpProxy.kt`；配置深拷贝和完整 initializer 顺序由既有 `TeamSourceRobotSnapshotTest/AcpClientFeatureInitializerTest` 回归覆盖。
- [x] FT-CMD-405：实现 MemoryManagerRegistry/MemoryScopeLock，处理并发写。
  - `MemoryManagerRegistry` 按 owner key 复用 manager/异步队列，并让普通、Team、subAgent manager 共享一个 `MemoryScopeLockRegistry`。
  - 锁粒度不是 managerId，而是 `MemoryFileStore.getStorageKey(workspacePath)` 返回的规范化最终目录：`{baseDir}/{sanitizedWorkspace}`，robot scope 再追加 `{sanitizedRobotName}`。同一真实 store 只有一把公平 `ReentrantLock`，不同 workspace/robot scope 可并行。
  - 所有 index/dream-state 读改写、memory list/delete/touch/clean、prompt snapshot 均在该锁下执行；extractor/dreamer 的 LLM 调用不持锁，返回后重新加载最新 index，再在单锁内应用、归档和保存，消除 stale-index 覆盖。
  - robot reload 移除的 manager 延迟到 `AcpProxy.stop()` 统一 shutdown，避免 Team 仍引用来源 manager 时被提前关闭，同时不泄漏异步队列。
  - 完成证据：`MemoryManagerRegistryTest` 使用两个不同 owner manager 对同一 workspace 并发执行 200 次读改写，最终 session count 精确为 200，且确认共享一个 storage lock；robot scope 的两个目录使用两把锁。
  - 修改文件：`MemoryManagerRegistry.java`、`MemoryScopeLockRegistry.java`、`MemoryManager.java`、`MemoryExtractor.java`、`MemoryDreamer.java`、`MemoryFileStore.java`、`AcpProxy.kt`、`MemoryManagerRegistryTest.java`。
- [x] FT-CMD-406：验证 subAgent、memory dream/ability、schedule 等不会被 talkTo 白名单误限制。
  - `AcpClientFeatureInitializerTest` 验证 MAIN/TEAM 都按 memory→ability→subAgent→schedule→talkTo 的稳定顺序执行全部 hook。
  - `AcpClientTalkToIsolationTest` 验证 Team 空 talkTo registry 不覆盖 subAgent registry；`TeamCapabilityParityTest/TeamSourceRobotSnapshotTest` 验证 Team 复用相同 concrete client、MCP/provider/model/proxy 和完整来源配置快照。
  - schedule replacement 测试验证新 session 仍走完整 initializer；memory 并发测试验证正常读写未因 Team 隔离而关闭。

#### FT-CMD-401～406 实际事件与错误变化

本批不新增 RPC command，不改变现有 command resultMap，也不新增 `TeamErrorCode`。

Team schedule 被接受执行时发布现有 `SCHEDULE_EVENT`，data 实际为：
`{"eventType":"SCHEDULE_TRIGGER","taskId":"t...","owner":{"scope":"TEAM","ownerId":"owner-1","robotName":"Robot One","teamId":"team-1","teamMemberId":"member-1","persistencePath":"team/team-1/member-1"},"sessionId":"<new-session-id>"}`。
外层 envelope 继续携带 `teamId/teamMemberId/acpClientId/teamVersion/eventSeq`。schedule
创建与管理仍由 `TeamAcpResponseListener` 使用既有 `SCHEDULE_EVENT` 映射；启动或发送失败继续使用
既有 `CLIENT_START_FAILED/INTERNAL_ERROR` 和 `MEMBER_STATE_CHANGED/MESSAGE_ERROR`，无新错误映射。

FT-CMD-401～406 针对性证据：`ScheduleOwnerIsolationTest` 3/3、
`MemoryManagerRegistryTest` 2/2、`TeamMemberCommandTest` 5/5，全部通过。

统一回归证据（2026-07-30）：`mvn -pl cmd-proxy-app -am test` 通过，共
23 个测试类、85 个测试，Failures 0、Errors 0、Skipped 0，`BUILD SUCCESS`；
`mvn -pl cmd-proxy-app -am -DskipTests compile` 通过；`git diff --check` 通过。

### Phase 5：删除、恢复与回收

- [x] FT-CMD-501：实现幂等 `acpTeamDelete` 和 DELETING 屏障。
- [x] FT-CMD-502：实现 Team 资源关闭、历史归档和 DELETED_WITH_WARNINGS。
- [x] FT-CMD-503：实现启动恢复与 RECOVERING 状态。
- [x] FT-CMD-504：实现 TeamResourceReaper。
- [x] FT-CMD-505：接入 `AcpProxy.stop()` 和 JVM shutdown hook。

#### FT-CMD-501～505 完成证据

`acpTeamDelete` 与其他 Team command 一致，只接受一个 JSON `cmdArg`：

```json
{
  "schemaVersion": "1",
  "requestId": "delete-01",
  "ownerChatterId": "owner-01",
  "teamId": "team-01",
  "expectedVersion": 42
}
```

`expectedVersion` 可省略；传入时匹配删除开始前的 Team version。成功的
`resultMap` 所有 value 仍为 String，示例：

```json
{
  "schemaVersion": "1",
  "requestId": "delete-01",
  "accepted": "true",
  "code": "DELETED",
  "message": "Team deleted",
  "teamVersion": "44",
  "data": "{\"team\":{...},\"warnings\":[],\"archiveExpireAt\":1785967200000,\"resources\":{\"runtimes\":0,\"clients\":0,\"talkToDispatchers\":0,\"pendingCleanup\":0,\"scheduleOwners\":0}}"
}
```

状态与版本严格为：任意非终态 `N` → `DELETING (N+1)` →
`DELETED | DELETED_WITH_WARNINGS (N+2)`。进入 DELETING 前先调用
`TeamRuntime.stopAcceptingRequests()` 增长 generation，之后 send/session/status/context
和 Team talkTo 均返回 `TEAM_DELETING`，并发中的成员启动也会因 generation guard
回滚，不能重新注册僵尸 client。

同一 `requestId + payload` 从 `TeamOperationRecord` 精确重放，不重复关闭资源；
相同 requestId 不同 payload 返回 `IDEMPOTENCY_CONFLICT`。另一 requestId 并发删除
同一 Team 返回 `TEAM_DELETING`。owner 不匹配返回 `UNAUTHORIZED`，版本不匹配返回
`VERSION_CONFLICT`。operation 保留 24 小时，tombstone 与历史归档保留 7 天。

实际删除事件 data：

- `TEAM_DELETE_ACCEPTED`：
  `{"team":<DELETING TeamDefinition>,"previousState":"READY","expectedVersion":42}`
- `TEAM_DELETED`：
  `{"team":<terminal TeamDefinition>,"warnings":[],"archiveExpireAt":1785967200000,"resources":{"runtimes":0,"clients":0,"talkToDispatchers":0,"pendingCleanup":0,"scheduleOwners":0}}`
- `TEAM_DELETE_FAILED`：
  `{"team":<current TeamDefinition>,"error":{"code":"INTERNAL_ERROR","message":"...","retryable":true,...}}`

`resources` 是被删除 Team 的专属资源快照，不会被其他仍运行 Team 污染；
`TeamManager.resourceSnapshot()` 另保留实例级总量诊断。关闭顺序为：DELETING
屏障 → talkTo inbox/去重缓存 → Team schedule 文件/缓存/running 标记 →
成员 ACP client → history 归档 → terminal definition → tombstone → 移除 runtime。
Memory 不属于 Team 删除对象，继续按正常模式共享和持久化。

成员 client 关闭默认等待 30 秒。超时或单个 client/history 归档关闭失败时不重新开放
Team，而是落 `DELETED_WITH_WARNINGS`；warning 使用 `INTERNAL_ERROR`、
`retryable=true`，`details.phase` 为 `client-close` 或 `history-archive`。
超时 Future 由 reaper 继续观察，因此当次 `resources.pendingCleanup=1`，关闭完成后的
下一次 reaper 归零。定义文件在 tombstone 已落盘后删除；残留定义文件不会恢复成
活跃 Team，由启动恢复/reaper 再清除。

恢复策略已固定：

- `READY`：持久化为 `RECOVERING`，发布 `TEAM_RECOVERY_STARTED`，为全部成员创建
  新 ACP session；全员成功后回 `READY` 并发布 `TEAM_RECOVERED`。
- `CREATING`：继续原有全有或全无成员启动，成功进入 `READY`。
- `FAILED`：恢复只挂载权威定义且保持请求关闭，不自动反复拉起。
- `DELETING`：恢复后立即重建删除屏障并续跑清理；完成后补 operation result、
  tombstone 与 `TEAM_DELETED`。
- `DELETED/DELETED_WITH_WARNINGS`：不挂载 runtime，仅清除可能残留的定义和资源。

恢复事件 data 均直接携带完整投影：
`{"team":<TeamDefinition>,"members":[...],"error":<可选 TeamError>}`；
对应事件为 `TEAM_RECOVERY_STARTED/TEAM_RECOVERED/TEAM_RECOVERY_FAILED`。

`TeamResourceReaper` 是 60 秒周期 daemon：续跑 DELETING、关闭孤儿 client、
清除 terminal runtime、清除孤儿 Team schedule、过期 operation/tombstone/history
归档，并清理已完成的超时 Future。单轮局部失败吞并并留待下一轮，保证最终一致性。
`AcpProxy` 初始化成功后启动 reaper；`stop()` 已同步化并保持幂等，JVM shutdown
hook 调用同一 stop 路径。普通 shutdown 只关闭内存 runtime/client/talkTo/executor，
不删除 Team schedule 持久化文件，供重启恢复。

本批主要新增/修改文件：

- `team/model/TeamDefinition.java`、`TeamResourceSnapshot.java`、
  `team/protocol/TeamDeleteCommand.java`
- `TeamManager.java`、`TeamStore.java`、`TeamClientRegistry.java`、
  `TeamStartupCoordinator.java`
- `TeamHistoryArchiver.java`、`TeamResourceReaper.java`
- `TeamEventType.java`、`TeamTransportProtocol.java`、
  `TeamTransportDescriptor.java`、`TeamCommandHandler.java`
- `ScheduleTaskManager.java`、`AcpProxy.kt`
- `TeamDeleteLifecycleTest.java`、`TeamRecoveryTest.java`、
  `TeamResourceReaperTest.java`、`TeamTransportProtocolTest.java`

针对性证据（2026-07-30）：删除/恢复/reaper/transport/startup 共 16/16 通过；
其中 delete 生命周期 4/4、recovery 2/2、reaper 1/1、transport 6/6、
startup generation guard 3/3。

统一回归证据（2026-07-30）：`mvn -pl cmd-proxy-app -am test` 通过，共
26 个测试类、92 个测试，Failures 0、Errors 0、Skipped 0，`BUILD SUCCESS`；
`mvn -pl cmd-proxy-app -am -DskipTests compile` 通过；`git diff --check` 通过。

### Phase 6：测试、观测与交付

- [x] FT-CMD-601：补齐 Team 模型、store、协议、talkTo、schedule、memory 单元测试。
- [x] FT-CMD-602：补齐 create/delete/send/talkTo/schedule 竞态测试。
- [x] FT-CMD-603：完成 100 次创建删除资源泄漏验收。
- [x] FT-CMD-604：完成普通 ACP 全量回归。
- [x] FT-CMD-605：增加 Team 配额配置、指标和结构化日志。
- [ ] FT-CMD-606：与 MolaChat 完成命令/事件 contract 联调（cmdproxy 本地契约与启动准备已完成，等待双方浏览器联调）。

#### FT-CMD-601～605 完成证据

新增 `TeamLimits`，创建配额在实例级 `createQuotaLock` 内完成“读取当前占用 →
判断 → 持久化定义 → 挂载 runtime”，因此不同 requestId 的并发创建不会穿透配额。
幂等重放在配额判断之前完成，已经接受的同 payload 请求不会因后来占满配额而变成失败。

可配置项均为“环境变量优先、JVM property 次之”，非法值回退默认值；修改后需重启
cmdproxy：

| 环境变量 | JVM property | 默认值 | 语义 |
|---|---|---:|---|
| `CMD_PROXY_TEAM_MAX_ACTIVE_TEAMS` | `cmd.proxy.team.maxActiveTeams` | 20 | 实例同时存在的非终态 Team 上限 |
| `CMD_PROXY_TEAM_MAX_MEMBERS_PER_TEAM` | `cmd.proxy.team.maxMembersPerTeam` | 6 | 单 Team 成员上限，合法范围 1～6 |
| `CMD_PROXY_TEAM_MAX_TOTAL_MEMBERS` | `cmd.proxy.team.maxTotalMembers` | 100 | 实例全部非终态 Team 成员总上限 |

超过任一上限返回已有 `QUOTA_EXCEEDED`，结果仍写入 requestId operation 快照，保持
可重放。`acpTeamDescribe.data.limits` 公开当前实际配额。

新增 `TeamMetricsSnapshot`，包含：

- 累计计数：`createAccepted/createQuotaRejected/deleteCompleted/`
  `deleteWithWarnings/deleteFailed/recoveryStarted/reaperRuns`。
- 当前 gauge：`activeTeams/activeMembers` 与 `resources` 中的
  `runtimes/clients/talkToDispatchers/pendingCleanup/scheduleOwners`。

`acpTeamDescribe.data.metrics` 返回当前快照；原有 descriptor 字段、命令列表和
String resultMap 外壳不变，MolaChat 的旧解析向后兼容。累计计数为进程内观测，
重启后归零；当前 gauge 从恢复后的权威 runtime 实时计算。结构化日志统一使用
`team_lifecycle action=... teamId=... requestId=... state=... version=...`；
reaper 以 DEBUG 输出 `team_metrics activeTeams=... resources...`。

竞态与规模测试实际覆盖：

- 两个不同 requestId 同时创建且 `maxActiveTeams=1`：严格 1 个 ACCEPTED、
  1 个 QUOTA_EXCEEDED，无超配 runtime。
- 删除在 client close 阻塞期间：send 返回 `TEAM_DELETING`，旧 dispatcher 的
  talkTo 返回 not accepting，Team schedule 新建抛出 DELETING 门禁。
- talkTo 20 路并发写容量 10 的同一 member inbox：严格 10 queued、10 rejected。
- 100 次顺序执行真实 `create → READY → 创建 talkTo dispatcher →
  创建 Team schedule → delete`：每轮该 Team 的 5 项资源快照均归零；最终
  `activeTeams=0/activeMembers=0`、实例资源全零，100 个 tombstone 按 7 天策略
  保留，不被误判为泄漏。
- 普通 ACP 与 Team 继续使用同一个 `AcpClient`、initializer、provider/model、
  MCP/proxy、permission、compaction、ability、subAgent 与 memory 能力链；
  Team 唯一收紧项仍是 talkTo 临时通讯录，普通 ACP 测试行为未改。

本地 `TeamContractIntegrationTest` 已串联验证：
`acpTeamDescribe → acpTeamCreate → TEAM_READY → acpTeamList/Get →
acpTeamDelete`，全部 resultMap value 为 String，删除资源全零，事件顺序严格为
`TEAM_CREATE_ACCEPTED → TEAM_READY → TEAM_DELETE_ACCEPTED → TEAM_DELETED`。

统一回归证据（2026-07-30）：`mvn -pl cmd-proxy-app -am test` 通过，共
28 个测试类、96 个测试，Failures 0、Errors 0、Skipped 0，`BUILD SUCCESS`。
其中新增 Phase 6 定向测试：规模/配额 2/2、端到端 contract 1/1、transport
discovery/metrics 7/7、删除竞态 4/4。普通 ACP 的 identity、session conflict、
lifecycle guard、feature initializer、shutdown、conversation history、talkTo 隔离
以及 schedule/memory 回归全部通过。
`mvn -pl cmd-proxy-app -am -DskipTests compile` 通过；
`mvn -pl cmd-proxy-app -am -DskipTests package` 通过并重新生成
`cmd-proxy-app/target/cmd-proxy-app-1.0.0-jar-with-dependencies.jar`。

#### FT-CMD-606 可执行联调启动方式

前置条件：

1. `$CMD_PROXY_HOME/acpConfig.json` 至少有 2 个 `enabled=true` 且
   `onlySubAgent=false` 的普通 ACP robot，并配置当前 MolaChat 用户对应的
   `chatterIds`。来源 robot 的 provider、凭据、workDir、MCP、代理等配置必须能在
   普通模式正常启动；Team 不需要额外复制配置文件。
2. MolaChat `application.yml` 保持 `app.use-cmd-proxy: true`。cmdproxy 与
   MolaChat 使用同一套 cmd RPC 服务；Fast Team 不新增 HTTP/RPC 端口。
3. 同一 `CMD_PROXY_HOME` 只能启动一个 cmdproxy 实例。需要并行环境时必须使用
   不同 home、RPC port、ConfigUI port 和 chatterId/robot 集合。

终端 A，构建并启动 cmdproxy：

```bash
cd /home/mola/IdeaProjects/cmd-proxy
mvn -pl cmd-proxy-app -am package -DskipTests
CMD_PROXY_HOME=/home/mola/.cmd-proxy \
CMD_PROXY_RPC_PORT=10020 \
CMD_PROXY_CONFIG_UI_PORT=10528 \
CMD_PROXY_TEAM_MAX_ACTIVE_TEAMS=20 \
CMD_PROXY_TEAM_MAX_MEMBERS_PER_TEAM=6 \
CMD_PROXY_TEAM_MAX_TOTAL_MEMBERS=100 \
java -jar cmd-proxy-app/target/cmd-proxy-app-1.0.0-jar-with-dependencies.jar acp
```

若现有有效配置不在 `/home/mola/.cmd-proxy`，必须把 `CMD_PROXY_HOME` 改成实际环境，
不能用一个空 home 误判联调失败。启动日志应出现：

- `Fast Team transport 已注册`，transportGroup 为
  `team-acp-{CmdProxyHome.instanceId}`，commands 包含 13 个业务/describe 命令。
- `Team 持久定义恢复完成`。
- `acpSyncRobots 回调已发送`，其 `teamDiscovery.businessCommandsReady=true`。

终端 B，启动当前 MolaChat dev profile：

```bash
cd /home/mola/IdeaProjects/molachat
./mvnw -DskipTests spring-boot:run
```

默认 dev 端口为 8550、context path 为 `/chat`，当前配置含 SSL key store，因此优先
访问 `https://localhost:8550/chat`；若本地 profile 显式关闭 SSL，则改用 HTTP。

浏览器联调验收顺序：

1. Teams 弹框能看到至少 2 个普通 `robotGroup=acp` 候选；刷新后
   `/team` list/get 与 cmdproxy 权威定义一致。
2. 发起 1～6 人纯本机 Team，首次 HTTP/RPC 返回 ACCEPTED；事件或轮询进入 READY 后
   MolaChat 自动进入队伍模式，只展示该 Team 的 `robotGroup=team-acp` 成员。
3. 对两个成员分别验证文本、文件、cancel/new/list/restore session、status 和
   context usage；消息事件不得串到普通 robot 或另一 Team。
4. 让一个成员忙碌后验证 talkTo QUEUED/RECEIVE；目标只能使用当前 Team memberId，
   remark 为创建时固化的来源 signature/fallback。
5. 建立 Team schedule 后确认目录为
   `$CMD_PROXY_HOME/schedules/team/{teamId}/{teamMemberId}`；执行时只替换该 member
   session。验证 memory 正常读写且普通 ACP 不受影响。
6. 删除当前 Team：确认二次确认、DELETING UI 门禁、同 requestId+payload 重试、
   TEAM_DELETED 后投影清理并切回主会话；`TEAM_DELETED.data.resources` 对被删 Team
   应全零，`acpTeamDescribe.data.metrics.resources` 是实例总量，只保留其他仍活跃
   Team 的资源。
7. 创建一个 READY Team 后正常停止并重启 cmdproxy，确认
   RECOVERING→READY、新 session、MolaChat 通过 list/get/event 对账恢复投影。
8. 最后执行一次普通 ACP 文本/文件/talkTo/schedule/memory 回归，确认 Team 功能
   没有改变普通模式。

#### MolaChat 单端重启后的 Fast Team discovery 恢复

根因（2026-07-30）：旧实现把 `acpSyncRobots` 注册成返回空 map 的 dummy handler，
包含 `teamDiscovery` 的完整 resultMap 仅在 `AcpProxy.start()` 或 robot 热重载时主动
callback 一次。cmdproxy 不重启而 MolaChat 重启时，cmdproxy 无法感知远端 callback
provider 的“重新注册”这一应用层事件，因此不会再次执行启动 callback；MolaChat 的普通
ACP 有其他恢复路径，但新进程内的 Team discovery cache 为空，最终表现为
“Fast Team运行时尚未就绪”。

修复后的时序：

1. cmdproxy 先恢复 `TeamManager`，注册 `acpTeamDescribe` 与全部 12 个业务命令，生成
   `businessCommandsReady=true`、commands 共 13 个的 descriptor。
2. cmdproxy 用普通 robots、visibleChatterIds 和该 descriptor 创建不可变
   `AcpSyncRobotsSnapshot`，随后才注册 `acpSyncRobots` routeTag；因此该命令一旦可见，
   首次响应就不会处于 Team 半初始化状态。
3. 启动 callback、robot 热重载 callback、MolaChat 重连后的主动 RPC 握手全部读取
   同一个完整快照。普通 robot 更新使用 CAS 替换普通字段，不能删除 Team discovery；
   返回 map 不可变，远端/调用方也不能污染下一次重放。
4. MolaChat 每次进程启动必须先注册 `acpSyncRobots` callback，再主动调用
   `CmdSender.send("acpSyncRobots", "acpSyncRobots", emptyArgs)`，对连接未就绪做有限
   退避重试；成功响应直接走与 callback 相同的 discovery + ordinary sync 消费逻辑。
   这一步同时充当 sync ACK，不依赖 cmdproxy 猜测远端何时完成 callback 注册。
5. 获得 `teamTransportGroup` 后可再调用 `acpTeamDescribe` 校验当前 readiness/metrics，
   但 describe 不能替代首次 sync，因为 MolaChat 新进程在 discovery 前并不知道实例级
   transportGroup。

cmdproxy 的 callback sink 没有捕获 MolaChat 连接对象或 routeTag：每次事件都通过
`CmdReceiver.callback(command, group, response)` 查找 group consumer；Team 逻辑路由
仍在 resultMap 内使用 `teamId/teamMemberId/acpClientId`。主动 sync 响应走新连接的
request/response 通道，不受旧 callback consumer 瞬时重连状态影响。后续 Team event
callback 仍由底层 RPC 对固定 appointedAddress 的 consumer 负责断线重连。

重连后的 `acpSyncRobots` 实际 resultMap 包含：

```json
{
  "robots": "[...]",
  "visibleChatterIds": "[...]",
  "teamSchemaVersion": "1",
  "teamCmdProxyInstanceId": "{instanceId}",
  "teamTransportGroup": "team-acp-{instanceId}",
  "teamDiscovery": "{\"schemaVersion\":\"1\",\"businessCommandsReady\":true,\"commands\":[...13 items...]}"
}
```

自动化证据：`AcpSyncRobotsReconnectTest` 覆盖同一 cmdproxy 快照被两个连续 MolaChat
连接读取、第二次仍为 ready/13 commands；覆盖 ordinary reload 后 Team discovery 不
丢失；覆盖返回 map 不能反向污染权威快照。定向运行
`mvn -pl cmd-proxy-app -Dtest=AcpSyncRobotsReconnectTest,TeamTransportProtocolTest test`
为 10/10 通过。完整 `mvn -pl cmd-proxy-app -am test` 为 29 个测试类、
99/99 通过，Failures 0、Errors 0、Skipped 0，`BUILD SUCCESS`。

#### Team 会话命令能力与 BUSY cancel 修复

排查结论（2026-07-30）：

- cmdproxy 已实现 `acpTeamNewSession/ListSessions/RestoreSession`，其 Team member
  handler、独立 history namespace 和 session replacement 均可用；`#new-session#`
  与 `#list-sessions#` 是否展示属于 MolaChat `AcpExecHandler.cmdDescriptions` 的
  Team 分支，不是 cmdproxy 的 Agent prompt/ability 注入。Team transport discovery
  只发布 RPC command 名，不发布前端 `#...#` 命令文案。
- 普通 `#acp-dream#` 路由到 `acpMemoryDream`，旧 Team transport 没有对应 RPC，
  这是 cmdproxy 侧真实缺口。现新增 `acpTeamMemoryDream`，使用严格
  `teamId + teamMemberId (+ acpClientId)` 路由 member，再按固化的
  `sourceGroupId` 复用来源普通 robot 的同一 `MemoryManager` 与 workspace，
  保持 memory scope、写锁、dream queue 和 ability refresh 语义一致；未开启可写
  memory 时返回 `MEMORY_NOT_ENABLED`，不回退其他 robot。
- `AcpClient.cancel()` 原本已经向当前 `sessionId` 写入 ACP
  `session/cancel` notification，并会先取消该 member 的 sub-agent。问题是 Team
  manager 缺少 cancel 专用门禁。现在 cancel 与 delete/session replace 共用
  operation lock，并在锁内重查路由：READY/BUSY 可取消，其余生命周期状态明确拒绝。

MolaChat 文本命令到 cmdproxy 的最终映射：

| UI 命令/操作 | Team RPC | cmdproxy 行为 |
| --- | --- | --- |
| 普通文本/文件 | `acpTeamSend` | 仅 READY member 可发送 |
| `#acp-cancel#` / BUSY stop-stream 的远端取消 | `acpTeamCancel` | READY/BUSY 发送当前 session/cancel |
| `#new-session#` | `acpTeamNewSession` | 替换目标 member client/session |
| `#list-sessions#` | `acpTeamListSessions` | 读取目标 member 独立 history namespace |
| `#restore-session# {sessionId}` | `acpTeamRestoreSession` | 仅替换目标 member 并恢复指定 session |
| `#acp-dream#` | `acpTeamMemoryDream` | 使用来源 sourceGroupId 的 MemoryManager |
| 状态/上下文查询 | `acpTeamGetStatus/GetContextUsage` | 返回目标 member 数据 |

所有 member RPC 仍只接受单 JSON payload：

```json
{
  "schemaVersion": "1",
  "ownerChatterId": "owner-1",
  "teamId": "team-1",
  "teamMemberId": "member-1",
  "acpClientId": "team-acp-member-1"
}
```

`acpTeamMemoryDream` 成功 data 在既有 member data 之外增加：

```json
{
  "triggered": true,
  "memoryOwnerSourceGroupId": "source-group-1"
}
```

Cancel 状态门禁矩阵：

| Team 状态 | member/client 状态 | 结果 |
| --- | --- | --- |
| READY | READY / READY | 允许（幂等发送 cancel notification） |
| READY | BUSY / BUSY | 允许，解决 stop-stream 远端 prompt 未取消问题 |
| READY | STARTING / CREATED/STARTING | 拒绝 `TEAM_NOT_READY` |
| READY | ERROR/CLOSING/CLOSED | 拒绝 `CLIENT_CLOSED` |
| CREATING/RECOVERING/ROLLING_BACK/FAILED/终态 | 任意 | 拒绝 `TEAM_NOT_READY` 或 `NOT_FOUND` |
| DELETING | 任意 | 拒绝 `TEAM_DELETING` |

descriptor 现为 describe + 12 个业务命令，共 13 commands。新增/更新回归覆盖：
BUSY member cancel 的当前 session、STARTING/ERROR/CLOSING/CLOSED 拒绝矩阵、
DELETING 屏障、memory dream 的 sourceGroupId 路由、discovery 13 commands。
定向 `TeamMemberCommandTest + TeamDeleteLifecycleTest +
TeamTransportProtocolTest + AcpSyncRobotsReconnectTest` 为 22/22 通过。
完整 `mvn -pl cmd-proxy-app -am test` 为 29 个测试类、102/102 通过，
Failures 0、Errors 0、Skipped 0，`BUILD SUCCESS`。
显式 compile 与跳过测试的 package 均成功，fat jar 已重新生成；
`git diff --check` 通过。

#### Team ACP 全输出链路与主会话等价修复

根因（2026-07-30）：`AcpClient.processSessionUpdate` 对普通和 Team client 使用同一套
ACP 解析，文本、tool call、permission 自动确认、MCP、文件/图片 structured rawOutput
都会进入同一个 `AcpResponseListener` 接口；subAgent、schedule、talkTo、compaction
也在 `AcpClient` 内归一为 listener 回调。丢失发生在 listener 的最后一跳：

- 普通 `DefaultAcpResponseListener` 会将 completed tool call、subAgent、schedule、
  compaction 渲染成 `<details class="tool-call">...</details>`，再作为普通 `content`
  流发送。
- 旧 `TeamAcpResponseListener` 只发布 `TOOL_CALL/SUB_AGENT_EVENT/SCHEDULE_EVENT/
  COMPACTION_EVENT` 结构化事件，没有同时生成主会话的 HTML content。
- MolaChat 的 Team 消息投影只把 `MESSAGE_CHUNK/COMPLETE/ERROR` 写入
  `StreamMessage`；因此结构化事件虽到达，但工具结果卡片在会话中不可见。问题与
  provider/MCP 类型无关，也不是仅 talkTo 丢卡。

修复方案：

1. 新增 `AcpResponseContentRenderer`，成为普通 ACP 与 Team 唯一共用的展示渲染器。
   `DefaultAcpResponseListener` 不再持有一份独立 HTML 模板，避免后续两套模板漂移。
2. Team 对 completed tool call、subAgent、schedule 和 completed compaction 先发布
   与普通 ACP 逐字符相同的 `MESSAGE_CHUNK.content`，同时保留原结构化事件用于日志、
   指标和未来原生卡片消费。pending/in_progress tool call 与普通 ACP 一致，不生成
   用户可见卡片。
3. 普通文本仍是 `MESSAGE_CHUNK`；正常结束仍是 `MESSAGE_COMPLETE`。错误
   `MESSAGE_ERROR.data.content` 等于普通 ACP 的终止帧内容，MolaChat 应优先原样使用
   `content` 并以该事件结束 stream，不再自行拼接不同错误前缀。
4. `TeamTalkToDispatcher.managesTalkToEvents=true` 只抑制 `AcpClient` 的普通
   talkTo callback，绝不影响 tool/subAgent/schedule/compaction 渲染。Team talkTo
   继续由 dispatcher 产生队内专属卡片，卡片 target 仅为不可变 `teamMemberId`，
   不接受 robotName/displayName/acpClientId，也不回退普通通讯录。

事件矩阵：

| ACP/listener 输入 | 普通 ACP 可见输出 | Team 可见输出 | Team 额外结构化事件 |
| --- | --- | --- | --- |
| agent text chunk | content, end=N | `MESSAGE_CHUNK` 同 content | 无 |
| tool begin/update | 无 | 无 | `TOOL_CALL` |
| tool completed（含 MCP/permission/tool rawInput/rawOutput、文件/图片字段） | tool HTML, end=N | `MESSAGE_CHUNK` 同 HTML | `TOOL_CALL` |
| subAgent start/progress/complete/error | subAgent HTML, end=N | `MESSAGE_CHUNK` 同 HTML | `SUB_AGENT_EVENT` |
| schedule create/manage | schedule HTML, end=N | `MESSAGE_CHUNK` 同 HTML | `SCHEDULE_EVENT` |
| compaction completed | compaction HTML, end=N | `MESSAGE_CHUNK` 同 HTML | `COMPACTION_EVENT` |
| complete | content="", end=Y | `MESSAGE_COMPLETE` | member READY |
| error | error content, end=Y | `MESSAGE_ERROR.content` 同内容并结束 | member ERROR |
| talkTo | 普通联系人卡片 | Team dispatcher 队内卡片 | `TALK_TO_*` |

实际 completed MCP/tool 卡片开头保持：

```html
<details class="tool-call"><summary>🛠️ ✅ MCP \#image</summary><div class="tool-call-body">
<details class="tool-detail" open><summary>📥 输入参数</summary>
```

输入输出 JSON 仍使用同一套 pretty print、1000 字符截断、HTML escape 和卡片前换行
规则，卡片顺序及最终 `end` 边界由参数化测试逐帧比较。

自动化证据：新增 `AcpResponseListenerParityTest`，以 12 组输入同时驱动
`DefaultAcpResponseListener` 和 `TeamAcpResponseListener`，归一掉仅允许不同的
Team identity/session/envelope 与结构化观测事件后，逐帧比较 content、HTML、顺序和
end；覆盖 text、tool begin/update/completed、MCP/图片字段、subAgent、schedule、
compaction、complete、error、文本后紧跟工具卡片的换行顺序。与
`TeamAcpResponseListenerTest/TeamTalkToDispatcherTest` 的定向回归为 36/36 通过。
完整 `mvn -pl cmd-proxy-app -am test` 为 30 个测试类、126/126 通过，
Failures 0、Errors 0、Skipped 0，`BUILD SUCCESS`。
显式 compile 与 package 均为 `BUILD SUCCESS`，`git diff --check` 通过。fat JAR：
`cmd-proxy-app/target/cmd-proxy-app-1.0.0-jar-with-dependencies.jar`，
49,018,942 bytes，构建时间 2026-07-30 12:17:51 +0800。

## 11. 后续需要双方共同锁定的细节

### 2026-08-08 Phase 1M 开工记录

用户已确认 1A+2B 并授权开发：纯本机 ACP-only Team 继续走 V1；mixed Team 必须
由 MolaChat 保证至少一个可信 home/local member 和至少一个 remote member，remote
可来自多个 cmd-proxy，remote-only 禁止。cmd-proxy fragment 只包含本实例 local
members，同时持久化同一份 1～6 人全局 `roster`。

- B 侧以 `AcpRobotParam.teamSharedWithChatterIds` 为 standing allowlist，remote source
  group 为 `team-shared-{instanceId}-{sourceRobotId}`，不写普通 `visibleChatterIds`；
- discovery capability 为 `mixedTeamFragment/mixedTeamTalkToDeliver`，远程来源置于
  独立 `remoteTeamMemberSources`；
- 新增 `TALK_TO_ROUTE_REQUEST` 和 `acpTeamTalkToDeliver`，目标端校验 owner、Team、
  roster、本地 target、grant、TTL、depth 与 messageId；
- grant 撤销后拒绝新 fragment create/member command/talkTo，cancel/delete 保持可用，
  当前 BUSY turn 不被强杀；ConfigUI 可编辑/撤销 allowlist，状态 API 投影借用 owner、
  Team/member 数与清理状态。

撤销后的清理触发已经闭环：robot 配置热刷新和启动恢复均调用
`TeamManager.reconcileRevokedGrants()`；受影响 fragment 先发布
`TEAM_STATE_CHANGED(cleanupStatus=REVOKED_CLEANUP, reason=TEAM_GRANT_REVOKED)`。
存在 BUSY member 时只登记 pending，不关闭 client；`onMemberState` 观察到 turn 收尾后，
异步调用现有幂等 delete 状态机进入 `DELETING`，随后发布既有
`TEAM_DELETE_ACCEPTED/TEAM_DELETED`。单次 delete 失败保留 pending，resource reaper
每 60 秒使用新的 attempt requestId 重试，因此不会被失败 operation snapshot 永久阻塞。

实现保持 schemaVersion `1` 的 additive compatibility，不改变 V1 payload 语义。

首轮验证：ConfigUI 内联脚本经 JavaScript 语法检查通过；mixed discovery、授权 create、
拒绝未授权 create、roster 持久化、remote route event 与全部既有 cmd-proxy-app 回归
均通过。新增 `TeamGrantRevocationCleanupTest` 覆盖 BUSY 不打断、收尾后删除、事件与资源
释放；最终执行 `mvn -pl cmd-proxy-app test` 共 216 个测试，Failures 0、Errors 0、
Skipped 0，`BUILD SUCCESS`。

以下已由 FT-CMD-104～107、FT-CMD-503 锁定：`schemaVersion=1`；TeamEvent 使用
`acpTeamEvent` 和纯字符串 resultMap（`data` 为 JSON 字符串）；
instance/transport 信息通过 `acpSyncRobots.teamDiscovery` 与 `acpTeamDescribe.data`
返回；`teamId/teamMemberId` 由 MolaChat 预生成、cmd-proxy 校验并固化；
remark 由来源 robot signature 生成，空值采用稳定 fallback，V1 不允许在弹框编辑。

仍需在后续阶段确认：

- event 短期补拉接口是否进入 V1；
- source 配置 fingerprint 漂移后的默认恢复策略。
