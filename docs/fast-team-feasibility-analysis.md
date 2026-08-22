# Fast Team 需求可行性分析与功能点清单

> 分析对象：cmd-proxy 当前仓库实现  
> 日期：2026-07-29  
> 结论：**可行，但不应通过“复制 robot 配置并复用现有全局 Registry/TalkTo”直接实现。** 推荐新增 Team 领域层，并把 Team ACPClient 的身份、历史目录、通讯录、收件箱和生命周期全部与主 robot 会话隔离。

> 2026-08-08 需求纠正：此前“整队可放到 remote、owner 单活 placement”的方案 A
> 已废弃，不得作为实现依据。真实 MVP 必须允许同一 Team 混选本机 cmd-proxy ACP 与
> 一个或多个 remote cmd-proxy ACP，并禁止 remote-only Team。当前唯一基线见
> [Fast Team 本机与 Remote ACP 混选 MVP](fast-team-remote-mixed-mvp.md)。
> cmd-proxy Phase 1M 首轮实现已完成；包含 grant 撤销后的 BUSY turn 收尾、自动删除、
> 事件通知与 reaper 重试清理，`mvn -pl cmd-proxy-app test` 全量回归 216/216 通过。

## 1. 需求理解与范围

Fast Team 的目标是：用户从 MolaChat 的 Teams 操作弹框中，选择多个当前可用的 ACP robot 会话并填写队名；cmd-proxy 为每个入队成员创建一套独立于其原 robot 主会话的 ACPClient；这些 Team 专属 client 在队内拥有临时通讯录并可通过 `talk_to` 协作；创建成功后 MolaChat 自动进入队伍模式，之后可在队伍、队员和主会话之间切换；删除队伍时销毁所有 Team ACP 会话、子进程、队内队列和临时状态。

这里的“独立 ACPClient”建议解释为：**每个 Team member 各有一个独立 ACP 子进程和 ACP session**，而不是全队共享一个 ACPClient。否则多个不同 provider、工作目录、MCP 配置和 robot 身份无法同时保留，`talk_to` 也失去明确的目标。

本报告只设计 cmd-proxy 与 MolaChat 的协作边界，不直接修改 MolaChat 前端实现。

## 2. 现有实现核查

### 2.1 可复用能力

- `AcpClientRegistry` 已支持按 key 创建、发送、取消、恢复、关闭 client；每个 client 对应独立子进程和 ACP session。
- `AcpClient` 已封装 provider 路由、MCP 加载、上下文注入、流式响应、`talk_to` 指令拦截、BUSY/READY 状态和 inbox turn 后续投递。
- `TalkToDispatcher` 已有异步投递、忙时入队（容量 5）、消息深度上限、60 秒重复消息抑制和跨 chatter 转发能力。
- `CmdReceiver` 能在既有 ACP group 上注册多个命令 routeTag，并通过 callback 把流式事件送回 MolaChat。
- `AcpProxy.stop()`、`reloadRobot()` 已有关闭普通 client 及清理部分辅助状态的先例。

### 2.2 不能直接复用的关键点

1. **Registry 身份只有 `groupId`，没有 client scope。** `getGroupIdsByRobot()` 会遍历全部 client 并按 robot 名称匹配。若 Team client 放进同一个 singleton registry，robot 热重载可能误关 Team client，`closeAll()`、定时任务和主会话查询也会混入 Team 实例。

2. **会话历史按 `robotName` 隔离，而不是按 client/group/team 隔离。** `AcpClient` 构造时给 `ConversationHistoryManager` 传 robot 名称，启动又会自动加载该名称目录中的最新 session。Team 复制同名 robot 后会与主会话共享 `lastSessionId` 和历史目录。

3. **存在主/Team 会话冲突时误杀进程的高风险。** 当前 `AcpClient.createSession()` 遇到 `Session is active in another process` 会解析 PID 并执行 `kill -9`。Team client 若误加载主会话，可能杀掉主 robot 的 ACP 进程。这不仅是 Team 风险，也与“优先用 ACP 协议优雅结束，OS kill 仅兜底”的生命周期方向冲突。

4. **TalkTo 全局按 robot 名称寻址。** `robotToGroupId`、`inboxes`、去重 key 都基于 robot name；同名 Team member 会路由到主 client或其他队伍，队伍删除后 inbox 也没有显式清理 API。

5. **现有 `groupId` 同时承担内部 client key、RPC group 和 chatterId 推导。** Team 若发明新 groupId，`AcpClient.extractChatterId()` 按 `acp-{robotName}` 字符串剥离的逻辑会失效；同时动态 RPC group 注册后，`CmdReceiver` 当前没有注销 provider/routeTag 的能力，会形成长期元数据泄漏。

6. **关闭语义不完整。** `AbstractAcpClient.close()` 当前先关 stdin/stdout，再 `process.destroy()`，没有调用 ACP `session/end`、等待优雅退出和强制兜底；`AcpClient.send()` 是异步执行，删除与正在运行的 turn 之间缺少统一生命周期锁，关闭后后台任务可能把状态改成 ERROR/READY或继续回调。

7. **服务重载只清普通状态。** `AcpProxy.stop()` 会清普通 registry、memory/ability/robot 索引，但未来的 TeamManager 若未纳入 stop/reload，将留下子进程和 callback。

8. **MolaChat robot 同步不适合作为 Team 同步。** `acpSyncRobots` 以 `visibleChatterIds` 为作用域全量覆盖 robot；Team 是用户动态运行态，不应伪装成普通 robot 写入此接口，否则可能造成 robot/历史会话互删或多环境冲突。

## 3. 总体推荐架构

推荐新增独立的 `TeamManager` 领域模块，由它持有 `TeamRuntime`，而不是把 Team 状态散落在 `AcpProxy`、普通 registry 和 MolaChat UI 中。

```text
MolaChat（Team 投影与 UI 状态）
  ├─ Teams 弹框 / 队伍列表 / 当前视图
  ├─ 缓存 teamId、队名、成员展示信息及事件
  └─ 通过 Team transportGroup 发送命令、接收带 teamId/teamMemberId 的事件
                         │
                         ▼
cmd-proxy TeamManager（Team 定义、生命周期与运行时权威源）
  └─ TeamRuntime(teamId, ownerChatterId, state)
      ├─ TeamMemberRuntime(teamMemberId, sourceRobot, sourceGroupId)
      │   └─ Team ACPClient（独立进程、session、history namespace）
      ├─ TeamTalkToDispatcher（仅本队成员）
      ├─ 临时通讯录（由成员快照生成，不写 acpConfig.json）
      └─ 生命周期锁 / 删除屏障 / 事件序号
```

核心原则：

- **权威源单一。** cmd-proxy 是 Team 定义、成员身份、状态机和 ACP 运行时的权威源，并在 `CMD_PROXY_HOME` 下持久化必要元数据；MolaChat 保存可重建的 Team 投影、消息和设备 UI 状态。MolaChat 重连后通过 `acpTeamList/Get` 对账，不用反向 `teamEnsure` 覆盖 cmd-proxy。
- **传输地址与逻辑身份分离。** Team member 的逻辑 `robotId=team-acp-{teamMemberId}`、`robotGroup=team-acp`；RPC transportGroup 则必须稳定且按 cmd-proxy 实例隔离，例如 `team-acp-{instanceId}`，或复用已知的实例控制 group。所有请求/事件显式携带 `teamId`、`teamMemberId/acpClientId` 和 transportGroup。不要为每个成员动态注册 RPC group，从根源避免当前 `CmdReceiver` 无 unregister 的问题。
- **Team client 与主 client 完全隔离。** 建立 `TeamClientRegistry`，或把通用 registry 重构成带 `ClientScope` 的多实例 registry；V1 推荐前者，改动面小且误操作风险低。
- **Team 内只按不可变 `teamMemberId` 路由。** 来源 robotName 只用于展示；`talk_to.target` 可使用稳定的 displayName/alias，内部必须解析成 teamMemberId。UUID 身份使同一来源 robot 可同时加入多个 Team，而不会与普通 `acp-{robotName}` 或其他 Team 冲突。
- **Team 配置是快照。** 创建时复制 source robot 的 provider、workDir、model、MCP/代理必要字段，contacts 替换成临时队员列表；不修改 `acpConfig.json`，普通 robot 热重载也不隐式改变已运行 Team。
- **Team 不进入 `acpSyncRobots`。** 普通同步继续只处理 `robotGroup=acp` 的 `acp-{robotName}`；Team 使用独立的 create/delete/list/event 接口和 `robotGroup=team-acp`，避免全量同步误删 Team robot。

### 3.1 与 MolaChat ID/分组约束的兼容性结论

MolaChat 提出的不可变 UUID 方案与 cmd-proxy 架构兼容，且应提升为双方接口契约：

- `teamMemberId` 是成员实体主键，创建后不可修改；同一来源 robot 加入不同 Team 时必须获得不同 teamMemberId。
- `robotId` 与 `acpClientId` V1 可统一为 `team-acp-{teamMemberId}`。如果以后一个成员拥有多代 client，建议保留 robotId 不变，另给每次运行生成 `acpClientInstanceId`，避免“成员身份”和“进程实例身份”再次耦合。
- `robotGroup=team-acp` 与普通 `robotGroup=acp` 分离，所有普通 `acpSyncRobots` 查询、覆盖和删除都必须增加 group 条件，或更安全地让 Team 完全不经过该同步接口。
- 消息路由显式携带 `teamId + teamMemberId/acpClientId + groupId`。V1 已对齐：`groupId/transportGroup=team-acp-{cmdProxyInstanceId}` 是实例级稳定 RPC 地址，teamId/teamMemberId 是该地址内的逻辑路由键；`sourceGroupId` 只用于定位来源普通 robot、解析本地配置快照和校验归属。
- `robotGroup=team-acp` 仅作为逻辑分类，不是全局 RPC 地址。该方案避免每 Team/member 动态注册造成资源泄漏，也避免多个 cmd-proxy 实例争抢同一个全局 `team-acp` group。
- V1 不采用 Team/member 独立动态 RPC group，因此不依赖 `CmdReceiver.unregister`。如果未来引入动态 group，必须先实现 unregister、断线重注册、删除回收和对应压力测试，再开放该路由模式。

## 4. 功能点清单、待确认问题与默认推荐

### 4.1 Teams 操作弹框

功能点：

- 在会话页提供 Teams 入口，弹框至少包括“新建队伍”“已有队伍”“删除队伍”。
- 新建区展示可选择的 ACP 会话 robot、状态、provider、工作目录或能力摘要。
- 已有队伍展示状态：CREATING、READY、PARTIAL、DELETING、FAILED；支持进入、重试失败成员、删除。
- 防重复提交、创建进度和逐成员失败原因可见。

待确认问题：

- 弹框入口是 chatter 级还是 robot 会话级？
- 可选成员范围是当前 chatter、用户全部 chatter，还是跨 cmd-proxy 实例？
- 是否允许选择 BUSY、ERROR、onlySubAgent robot？
- 队名是否允许重名，长度和字符限制是什么？

V1 历史推荐：**入口为 chatter 级；纯本机 V1 仅列当前 cmd-proxy 实例、当前 chatter
可见且已启动的普通 ACP robot，BUSY 可选（因为会新建 Team client），
ERROR/disabled/onlySubAgent 不可选；1～6 人；队名去首尾空格后 1～40 字，允许重名但
用 teamId 唯一识别。** 跨实例混选不改变该纯本机路径，其当前范围以文首链接的混选
MVP 基线为准。

### 4.2 发起 Team：多选 ACP 会话 robot + 队名

功能点：

- MolaChat 生成请求级 UUID/ULID `teamId` 和 `teamMemberId` 候选值，cmd-proxy 幂等接收并固化为不可变身份；也可由 cmd-proxy 返回最终 ID，但同一 requestId 必须始终得到同一组 ID。
- 请求携带 ownerChatterId、队名、成员的 sourceGroupId/robotName，以及幂等键 requestId。
- cmd-proxy 校验成员确属当前实例和 chatter 可见范围，冻结 source robot 配置快照，并行创建 Team clients。
- 返回逐成员状态；创建成功后发出 `TEAM_READY` 事件。

待确认问题：

- teamId/teamMemberId 由 MolaChat 预生成还是 cmd-proxy 生成并返回？
- 创建必须全员成功才算成功，还是允许部分成功？
- 是否要复用原会话上下文、记忆和文件？
- 同一 robot 能否加入多个 Team，或在同一 Team 中出现多次？

默认推荐：**teamId/teamMemberId 由 MolaChat 预生成、cmd-proxy 校验并持久固化，便于请求超时重试和 UI 乐观建模，同时不削弱 cmd-proxy 的权威性；Team robot ID 固定为 `team-acp-{teamMemberId}`。创建采用全有或全无，任一成员失败则关闭已创建成员并返回 FAILED；Team 全部新建 session，不复制主会话对话/附件；允许一个来源 robot 同时存在于多个 Team，但同一 Team 中只允许一次。** 记忆读取是否复用可配置，V1 默认关闭 Team 写记忆，避免临时协作污染长期记忆。

### 4.3 Team 专属 ACPClient

功能点：

- 每个 member 创建独立 `AcpClient`、executor、ACP 子进程和 session。
- 新增历史命名空间，例如 `team/{teamId}/{teamMemberId}`，不得使用原 robotName。
- Team client 强制 `session/new`；不得自动加载主 robot 最近 session。
- Team listener 把 callback 发到实例级 Team transportGroup（或双方已约定的显式 groupId），事件 payload 始终携带 teamId/teamMemberId/acpClientId/eventSeq。
- Team client 支持 send/cancel/status/context usage；是否开放 new/restore 由产品决定。

待确认问题：

- Team 是否继承 source robot 的 MCP、模型、代理、system harness、memory、subAgent、schedule？
- 切走队伍后 client 是否继续运行？空闲多久回收？
- cmd-proxy 重启后要恢复 Team 定义与上下文吗？
- 一个用户最多可保留多少 Team/ACP 进程？

默认推荐：**继承 provider、workDir、MCP、model、代理、subAgent 能力；使用 Team 临时 talkTo；禁用 schedule；memory 只读可继承、写入默认关闭。切走 UI 不关闭 client，只有删除、cmd-proxy stop 或明确 TTL 才回收。V1 限每 chatter 5 个活跃 Team、每队 6 人、每实例 20 个 Team client，可配置。cmd-proxy 从 V1 就持久化 Team 定义、成员 UUID、配置指纹和状态；重启时先恢复定义并标为 RECOVERING，由 MolaChat 查询对账，ACP session 恢复可在 V2 通过本地 sessionId journal 与 `session/resume/load` 完成。**
我的要求：无需禁用 schedule，memory需要正常写入。除了talkTo的通讯录限制，其他acp的配置需要和正常模式下一模一样


### 4.4 临时通讯录与 `talkTo`

功能点：

- 创建完成后，为每个成员生成“除自己外的队员”通讯录。
- 通讯录只存在于 `TeamRuntime`，不写 source robot 的 contacts，也不写 `acpConfig.json`。
- 每个 Team 一个 `TeamTalkToDispatcher`，inbox key 为 teamMemberId，去重 key 至少包含 teamId/senderTeamMemberId/targetTeamMemberId。
- 队伍删除时拒绝新消息、排空/丢弃队内 inbox 并清理去重状态。
- 复用现有 JSON 形式：`{"action":"talk_to","target":"成员显示名","content":"..."}`，内部将显示名解析到 teamMemberId。

待确认问题：

- Team 成员是否还能联系原全局 contacts 或跨 chatter 联系人？
- 是否要求用户能看到 Agent 间消息卡片和完整审计？
- 队员同名如何区分？是否需要广播、@全体、队长角色？
- inbox 容量、TTL 和公平性是否沿用现值？

默认推荐：**V1 Team 模式只开放本队临时通讯录，禁止 ad-hoc 全局 target 和跨 chatter，避免消息逃逸；用户可看到 sender/target/时间/摘要卡片但不阻塞当前 turn；不做广播和队长特权；同队禁止重名；每 member inbox 容量 10、FIFO、消息 TTL 30 分钟，删除时直接丢弃并记录数量。** 现有“通讯录仅供参考、可向未列出 Agent 发送”的 prompt 必须为 Team 改成白名单语义。

### 4.5 MolaChat 自动进入队伍模式

功能点：

- create 请求返回 ACCEPTED 后弹框显示创建中；只有收到 TEAM_READY 才自动进入队伍模式。
- 队伍模式顶部展示队名、成员状态和当前选中成员。
- 每个 callback 通过 teamId/teamMemberId 路由到对应子会话视图，不能仅按 groupId 或 sourceGroupId 合并。
- PARTIAL/FAILED 不自动进入；保留错误和重试/删除入口。

待确认问题：

- “进入队伍模式”默认打开聚合时间线还是某个队员会话？
- 谁是初始发言对象，创建后是否自动发送任务？
- talkTo 消息是否进入聚合时间线？

默认推荐：**进入 Team 聚合页，并默认选中创建请求中第一个成员；创建动作本身不自动发 prompt，用户确认任务后再发送；聚合时间线展示用户消息、各 member 回复和 talkTo 事件，单成员页只展示该 member 的 ACP turn。**

### 4.6 队伍切换与切回主会话

功能点：

- 前端维护 `activeConversation = MAIN(groupId) | TEAM(teamId, teamMemberId?)`，切换只改变视图，不迁移消息。
- Team client 在后台继续处理 turn/talkTo；切回时按 eventSeq 补齐事件。
- 主会话与 Team 会话各自维护输入草稿、附件和滚动位置。
- 对不存在或已删除的 teamId 返回明确 `TEAM_NOT_FOUND/GONE`，前端回主会话。

待确认问题：

- 切走时是否取消正在运行的 Team turn？
- 未读数按队伍还是按成员计算？
- 多设备同时切换是否同步 activeConversation？

默认推荐：**切换不取消 turn；未读数同时提供队伍总数和成员数；activeConversation 默认是设备本地 UI 状态，不做跨设备强同步。** 后台结果必须通过事件缓存或 MolaChat 持久消息补齐，不能依赖一次性 callback 恰好被当前页面接收。

### 4.7 删除队伍与 ACP 实例清理

功能点：

- 删除接口幂等；状态先从 READY 进入 DELETING，立即拒绝新 send/talkTo/create duplicate。
- 对 BUSY member 先发 `session/cancel`，再发 `session/end`（provider 支持时），等待 2~3 秒；超时后 `destroy()`，最终才 `destroyForcibly()`。
- 等待/中断 client executor，关闭 stdin/stdout、MCP/子 Agent资源，清 inbox、去重、临时 contacts、listener、history 引用和 registry entry。
- cmd-proxy 返回逐成员清理结果；MolaChat 收到 DELETED 后移除 Team，若超时则显示“删除中”并允许查询，不应重复创建同 ID。
- cmd-proxy stop/reload 必须先 `TeamManager.closeAll()`；JVM shutdown hook 做最后兜底。

待确认问题：

- 删除是否连本地 Team 历史文件一起物理删除？
- 删除过程中某个 provider 无法退出，是整体失败还是带告警完成？
- cmd-proxy 离线时用户删除 Team，如何最终一致？
- 是否需要“离队”与“删除整队”两个动作？

默认推荐：**删除 Team 是运行时硬删除、历史软删除：立即停止并移除所有 client，磁盘历史移动到 `team-archive/{teamId}` 并设置 7 天 TTL，避免误删不可恢复；单 member 强杀失败时 Team 仍进入 DELETED_WITH_WARNINGS，并由后台 reaper 持续回收；cmd-proxy 持久化删除 tombstone，MolaChat 重连后通过 list/get 收敛并移除投影；V1 只允许创建者删除整队，不做单成员离队。**

## 5. 数据模型草案

### 5.1 cmd-proxy 权威持久模型（MolaChat 保存同构投影）

```json
{
  "teamId": "01J...",
  "ownerChatterId": "chatter-123",
  "name": "fast team",
  "status": "CREATING|READY|FAILED|DELETING|DELETED",
  "version": 1,
  "members": [
    {
      "teamMemberId": "01J...",
      "acpClientId": "team-acp-01J...",
      "robotId": "team-acp-01J...",
      "robotGroup": "team-acp",
      "robotName": "Code Chat Dev",
      "displayName": "Code Chat Dev",
      "sourceGroupId": "...",
      "status": "STARTING|READY|BUSY|ERROR|CLOSED"
    }
  ],
  "createdAt": 0,
  "updatedAt": 0
}
```

cmd-proxy 在自己的数据根目录持久化该权威定义及 runtime journal；MolaChat 持久化不含凭据的投影，用于列表和消息关联，并以 cmd-proxy 的 `version/status` 对账。不建议把 apiKey、代理、完整 AcpRobotParam 传给或存入 MolaChat；cmd-proxy 应用 sourceGroupId/robotName 从本地注册表解析配置快照。

### 5.2 cmd-proxy 运行时模型

```text
TeamRuntime
  teamId: String
  ownerChatterId: String
  name: String
  state: AtomicReference<TeamState>
  version: long
  operationLock: ReentrantLock
  transportGroup: String             // team-acp-{instanceId}，实例内稳定
  members: ConcurrentMap<teamMemberId, TeamMemberRuntime>
  dispatcher: TeamTalkToDispatcher
  createdAt / lastActiveAt

TeamMemberRuntime
  teamMemberId: String
  acpClientId/robotId: String         // team-acp-{teamMemberId}
  robotGroup: String                  // 固定 team-acp，仅逻辑分类
  displayName: String
  sourceRobotName: String
  sourceGroupId: String          // 来源普通 robot 会话定位，仅用于解析配置/归属校验
  clientKey: TeamClientKey      // (teamId, teamMemberId)，不可拼接猜测
  historyNamespace: String      // team/{teamId}/{teamMemberId}
  configSnapshot: AcpRobotParam
  client: AcpClient
  state / lastError
```

持久恢复仅落盘 `teamId/teamMemberId/acpClientId/sourceRobotName/sourceGroupId/transportGroup/sessionId/historyNamespace/configFingerprint/state`，不要重复持久化凭据。

## 6. 接口草案

### 6.1 传输约定

- `robotGroup=team-acp` 是 MolaChat 逻辑分类，不直接等于一个全局共享 RPC 地址。推荐 cmd-proxy 启动时一次性注册实例级 `transportGroup=team-acp-{instanceId}`；如果 MolaChat RPC 已有实例控制通道，也可复用该通道。两种方式都避免按 member 动态注册/注销。
- 所有 mutation 带 `requestId`（幂等）、`teamId`、`teamMemberId/acpClientId`（成员操作时）和 `expectedVersion`（可选乐观锁）。
- 命令同步响应只表示接收/校验结果；耗时创建、删除通过 callback 事件完成。
- 所有事件包含 `eventId`、`eventSeq`、teamId、teamMemberId、acpClientId、groupId/transportGroup、type、timestamp，MolaChat 去重并按序落库。

### 6.2 cmd-proxy commands

| 命令 | 请求关键字段 | 响应/说明 |
|---|---|---|
| `acpTeamCreate` | requestId, teamId, ownerChatterId, name, members[{teamMemberId, robotName, sourceGroupId}] | ACCEPTED / VALIDATION_ERROR / ALREADY_EXISTS；返回 transportGroup 与最终 member IDs |
| `acpTeamGet` | teamId | Team 状态及逐 member 状态 |
| `acpTeamList` | ownerChatterId, sinceVersion? | cmd-proxy 权威 Team 列表/增量，供 MolaChat 重连对账 |
| `acpTeamSend` | teamId, teamMemberId/acpClientId, message, files? | ACCEPTED；流式事件走 callback |
| `acpTeamCancel` | teamId, teamMemberId/acpClientId | 取消指定 member 当前 turn |
| `acpTeamDelete` | requestId, teamId, expectedVersion? | ACCEPTED / ALREADY_DELETED；异步清理 |

V1 不建议暴露 Team 的 listSessions/restoreSession；它会显著扩大恢复语义。需要时在 V2 添加 `acpTeamNewSession` 和 `acpTeamRestoreSession`，并继续使用 Team history namespace。

### 6.3 callback events

统一 callback 名可用 `acpTeamEvent`，payload 示例：

```json
{
  "eventId": "01J...",
  "eventSeq": 18,
  "teamId": "01J...",
  "teamMemberId": "01J...",
  "acpClientId": "team-acp-01J...",
  "type": "TEAM_READY|MEMBER_STATE|MESSAGE_CHUNK|TOOL_CALL|TALK_TO|TEAM_DELETED|TEAM_ERROR",
  "data": {},
  "timestamp": 0
}
```

相比给每种事件建立一个 RPC routeTag，统一事件信封更便于版本化、去重、离线补发和前端 reducer 处理。`groupId/transportGroup` 可作为信封字段返回，但服务端路由不能仅依赖可变的 robotName。

## 7. 生命周期与状态机

```text
不存在
  │ create（幂等占位）
  ▼
CREATING ──全员成功──> READY <──成员 turn──> ACTIVE/BUSY
  │ 任一失败             │
  │ 回滚已创建 client     │ delete / TTL / stop
  ▼                      ▼
FAILED                 DELETING
                           │ 关闭全部 client + 清路由状态
                           ▼
                        DELETED
```

建议创建顺序：

1. `putIfAbsent(teamId, CREATING)`，记录 requestId；校验 owner、成员上限、source client 存在。
2. 冻结每个 source robot 配置快照，生成临时 contacts 和 historyNamespace。
3. 受控并行启动成员（全局 semaphore 限制同时启动 ACP 进程数，例如 4）。
4. 全员 READY 后一次性发布 TeamTalkTo 路由，状态切 READY，再发 TEAM_READY。
5. 任一失败则进入 rollback：拒绝流量，关闭全部已创建成员，发 TEAM_ERROR。

建议删除顺序：

1. 原子 READY/FAILED → DELETING；重复 delete 返回同一个 operation 状态。
2. 关闭入口，dispatcher 拒绝新消息并清 inbox；取消正在执行的 turn。
3. 并行但限流地关闭各 member：`session/cancel` → `session/end` → wait → destroy fallback。
4. 清 Team registry/listener/executor/去重/临时通讯录；归档历史。
5. 状态置 DELETED，写 tombstone/reaper 任务并发 TEAM_DELETED。

## 8. 并发、异常与资源回收风险

| 风险 | 后果 | 建议防护 |
|---|---|---|
| 重复点击创建/网络重试 | 同一 Team 启动多套进程 | teamId + requestId 幂等，`putIfAbsent`，状态机 CAS |
| create 与 delete 并发 | 创建完成后“复活”已删除 Team | 每 Team operationLock + generation/version；异步任务提交前后检查 generation |
| delete 与 send/talkTo 并发 | 已关闭 pipe 写入、消息丢失、状态回跳 | DELETING 先关闭入口；client lifecycle lock；回调检查 generation |
| 大量 Team 同时创建 | 进程/内存/文件句柄突增 | Team/member 配额、全局启动 semaphore、按成员启动超时 |
| Team 与主会话历史同目录 | 加载错 session、杀掉主进程 | 强制独立 historyNamespace；禁止按 robotName 恢复 |
| robot 热重载误关 Team | 队伍无故断开 | 独立 Team registry；source config 用创建时快照 |
| 全局 talkTo 名称冲突 | 消息投递到主会话/其他队伍 | 每 Team dispatcher，teamMemberId 寻址，白名单校验 |
| 动态 RPC group 无法注销 | provider/consumer 元数据泄漏 | 启动时仅注册实例级稳定 transportGroup，payload 带逻辑身份；robotGroup 只作分类 |
| callback 重复/乱序/断线 | UI 重复 chunk 或状态倒退 | eventId 去重、eventSeq 排序、MolaChat 持久化事件 |
| 部分创建失败 | 僵尸 client | 全有或全无 + rollback + 周期 reaper |
| provider 不支持 session/end | MCP 子进程残留 | capability/错误探测；超时 destroy，最终 forcibly；记录 PID/退出结果 |
| cmd-proxy 崩溃 | MolaChat 显示 READY 但 runtime 不存在 | heartbeat/TeamGet；重连后 Ensure；本地 journal + shutdown hook |
| files/history 无限增长 | 磁盘泄漏 | Team archive TTL、配额、定时清理；删除默认软归档 |
| 当前 `send()` 异常置 ERROR | inbox 永久不再消费 | 明确 recoverable ERROR/terminal CLOSED；reaper 或 member restart API |

额外建议：在实施 Team 前先修正通用 `AbstractAcpClient.close()`，优雅协议关闭优先；同时移除/严格限制 `tryKillConflictingProcess()` 的 `kill -9` 行为。至少必须保证 Team history namespace 永不指向主 session，从设计上消除误杀入口。

## 9. 分阶段落地建议

### Phase 0：生命周期与身份基础（先做）

- 为 `AcpClient` 引入显式 `ClientIdentity(scope, logicalId, transportGroupId, historyNamespace)`，至少先支持 Team 专用 historyNamespace。
- 实现协议优先的 close：cancel/end/wait/destroy/forcibly，并加入生命周期锁。
- 新增 `TeamClientRegistry`、配额和 `closeAll()`；加入 `AcpProxy.stop()`。
- 给 TalkTo dispatcher 增加清理语义，或直接实现隔离的 TeamTalkToDispatcher。
- 单元测试身份隔离、重复 create/delete、create-delete race、主会话不受影响。

验收门槛：创建/删除 100 次后无残留 ACP 进程、线程、registry、inbox；Team 启动不得访问主 robot 的 lastSessionId。

### Phase 1：最小可用 Team

- MolaChat Teams 弹框：当前 chatter、1～6 个本地普通 robot、队名。
- `acpTeamCreate/Get/Send/Cancel/Delete` 和统一 TeamEvent。
- 全有或全无创建；Team 内临时白名单 talkTo；切换视图不关闭 client。
- 自动进入 Team 聚合页；删除后回主会话。
- 本阶段是纯本机 V1，不包含跨实例混选；混选由下方 Phase 1M 单独扩展。

### Phase 1M：本机与 Remote ACP 混选 MVP（当前新增范围）

- 纯本机 1～6 人继续走 V1；混选必须至少一个可信本机成员和至少一个 remote 成员。
- participants 为本机加 N 个 remote cmd-proxy，`N >= 1`，不限制为单个 remote；全队
  成员总数仍不超过 6。
- 采用 MolaChat 轻量全局记录、复用 V1 TeamDefinition 作为本地 fragment、直接 create
  加 delete 补偿、成员 placement 路由和 MolaChat 网关 talkTo。
- 禁止 remote-only；详细协议、文件和验收以
  [混选 MVP 基线](fast-team-remote-mixed-mvp.md) 为准。

2026-08-08 开工契约：远程授权不复用 `visibleChatterIds`，由来源 Robot 的
`teamSharedWithChatterIds` 明确授权，remote discovery 独立发布
`remoteTeamMemberSources`。fragment 复用 `TeamDefinition/TeamManager/TeamStore`
和既有 runtime；schemaVersion=1 增量增加 `mixedPlacement/roster`；跨实例 talkTo
使用 `TALK_TO_ROUTE_REQUEST` 加目标命令 `acpTeamTalkToDeliver`。

### Phase 2：可靠性与恢复

- 完善 Team runtime/session journal、删除 tombstone、离线重放和 reaper；MolaChat 用 list/get 对账权威状态。
- eventSeq/断线补发、软归档 TTL、资源看板和管理命令。
- member 失败重启、部分状态可观测、可配置空闲 TTL。

### Phase 3：能力扩展

- 在 Phase 1M 已支持当前 owner 可见的跨 cmd-proxy 混选基础上，增加跨 chatter 来源
  注册/授权、强 fencing、多节点协调和部分 participant 离线可用。
- member alias/同名成员、广播/队长/任务编排。
- Team 历史恢复、模板队伍、长期队伍、独立 memory policy。

## 10. 测试与验收清单

- 创建：2/6 人成功、重复 requestId、同 teamId 不同 payload、非法 sourceGroup、配额超限、成员启动超时。
- 隔离：主会话和 Team 会话 sessionId/historyPath/进程不同；两个 Team 同一 robot 互不串话。
- talkTo：READY 直投、BUSY 入队、队内白名单、循环/重复抑制、删除时拒绝、同名校验。
- 切换：后台流式响应不丢，切回按 eventSeq 恢复，主会话输入/附件不串。
- 删除：READY/BUSY/ERROR/创建中删除、重复删除、provider 不响应、cmd-proxy stop、进程崩溃后 reaper。
- 并发：create/delete/send/talkTo 交叉压测，确保状态不复活、无 ConcurrentModification、无 callback 到已删除 UI。
- 资源：进程数、活动线程、打开文件、session 目录、registry/inbox 数量回到基线。
- 回归：普通 ACP send/new/restore/cancel、robot reload、全局 talkTo、schedule/subAgent 不受影响。

## 11. 核心决策摘要

1. Fast Team 在现有技术栈上可实现，核心成本不在 UI，而在 **client identity、生命周期和 talkTo scope 的隔离**。
2. 一个 Team 应包含多个 Team member ACPClient；每个均与原 robot 主 client 的进程、session、历史目录独立。
3. 不复用 singleton `AcpClientRegistry` 和全局 `TalkToDispatcher` 保存 Team 运行态；新增 TeamManager/Team registry/Team dispatcher。
4. Team member 使用不可变 UUID，`robotId/acpClientId=team-acp-{teamMemberId}`，`robotGroup=team-acp`；普通 `acpSyncRobots` 永不包含 Team。
5. 不为每个 Team/member 动态创建 RPC group；使用实例级稳定 transportGroup，teamId/teamMemberId 做逻辑路由。
6. cmd-proxy 是 Team 定义、生命周期与 ACP 运行时权威源，MolaChat 保存投影与 UI 状态；通过幂等命令、事件序号、list/get 对账和 tombstone 达到最终一致。
7. 删除必须是状态机操作，优先 ACP `session/cancel`/`session/end`，OS 级强杀仅作超时兜底。
8. 建议先完成 Phase 0 再做弹框，否则最容易出现的不是功能缺失，而是主会话被误恢复/误杀、跨队串话和僵尸 ACP 进程。

## 12. 代码依据索引

- `cmd-proxy-app/src/main/java/com/mola/cmd/proxy/app/acp/acpclient/AcpClientRegistry.java`：singleton registry、按 groupId 管理、按 robotName 遍历和 closeAll。
- `cmd-proxy-app/src/main/java/com/mola/cmd/proxy/app/acp/acpclient/AcpClient.java`：历史管理器按 robotName 创建、自动 load 最近 session、异步 send、talkTo 和 inbox 投递。
- `cmd-proxy-app/src/main/java/com/mola/cmd/proxy/app/acp/acpclient/context/ConversationHistoryManager.java`：session 根目录按传入名称隔离。
- `cmd-proxy-app/src/main/java/com/mola/cmd/proxy/app/acp/acpclient/AbstractAcpClient.java`：进程启动、ACP 状态机及当前 close 行为。
- `cmd-proxy-app/src/main/java/com/mola/cmd/proxy/app/acp/talkto/TalkToDispatcher.java`：全局 robotName 路由、inbox、去重和跨 chatter callback。
- `cmd-proxy-app/src/main/java/com/mola/cmd/proxy/app/acp/talkto/TalkToContextInjector.java`：当前通讯录 prompt 允许向未列出 Agent 发送，不满足 Team 白名单。
- `cmd-proxy-app/src/main/kotlin/com/mola/cmd/proxy/app/acp/AcpProxy.kt`：group command 注册、普通 client 初始化、sync、reload 和 stop。
- `cmd-proxy-client/src/main/kotlin/com/mola/cmd/proxy/client/provider/CmdReceiver.kt`：RPC group/routeTag 注册与 callback consumer，当前无 unregister。
- `cmd-proxy-app/src/main/kotlin/com/mola/cmd/proxy/app/Main.kt`：chatter×robot groupId 构造和 `acpSyncRobots` 输入。
