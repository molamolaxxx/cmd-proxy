# Fast Team 事件异步投递契约

## 目标

Fast Team 的权威状态由 cmd-proxy 的 `TeamStore` 和 `TeamRuntime` 维护，MolaChat
事件 callback 是下游投影。创建、删除、成员 turn 和 talk-to 主流程不得同步等待
下游 callback，否则同步命令与回调重入可能形成跨 RPC 死锁。

## 执行模型

- `RpcTeamEventSink` 使用单个 daemon worker，线程名为
  `team-event-callback-delivery`。
- worker 使用容量为 1024 的 FIFO `ArrayBlockingQueue`。事件成功提交后按 executor
  的任务接收顺序串行投递，不使用 common pool。
- 业务线程只编码事件并调用非阻塞的 `execute()`。`CmdReceiver.callback` 和
  `RpcCallbackRetry` 均在 delivery worker 中执行。
- callback 传输沿用三次有界重试和退避。重试耗尽只丢弃下游投影，不回滚已经完成的
  Team 权威状态。
- `TeamManager.close()` 和 `closeForShutdown()` 负责关闭 event sink。关闭不等待慢
  callback；排队但未执行的任务会被丢弃并计入拒绝计数。

## 背压与可观测性

队列满或 sink 已关闭时，提交不会阻塞业务线程，也不会向生命周期主流程抛出异常。
sink 会记录包含以下字段的 WARN 日志：

- `eventId`、`teamId`、`eventSeq`；
- 拒绝原因 `queue_full` 或 `sink_closed`；
- 当前队列长度、容量和累计拒绝数。

运行时/测试可通过 `getQueueCapacity()`、`getQueuedEventCount()`、
`getRejectedEventCount()` 和 `isClosed()` 查看状态。

## TALK_TO_ROUTE_REQUEST 特殊语义

生命周期、成员状态和 UI 消息等事件可通过 list/get snapshot 重建，因此保持弱依赖
投影策略。`TALK_TO_ROUTE_REQUEST` 承载跨实例消息投递，不能在本地 admission 失败时
仍宣称已提交。

`TeamTalkToDispatcher` 对该事件使用 `TeamEventSink.tryPublish()`：

- 成功进入异步队列后，才返回“跨实例消息已提交路由”；
- 队列满或 sink 已关闭时，明确返回“发送失败：消息未提交，请稍后重试”；
- admission 失败会移除本次 dedup 占位，相同内容可以立即重试。

这里的成功仅表示事件已经进入 cmd-proxy 本地投递队列，不表示远端 callback 已经处理。
入队后的临时传输故障仍由 delivery worker 中的 `RpcCallbackRetry` 处理。

## 混选 Team talk-to 卡片投影

混选 Team 的跨实例消息沿用本地 Team talk-to 的卡片交互：

- 路由请求成功进入本地异步队列后，发送方收到 `TALK_TO_SEND` 和对应的
  `TEAM_TALK_TO` 消息卡片，投递状态为 `ROUTE_REQUESTED`；
- 远端消息直达本地成员时，接收方收到 `TALK_TO_RECEIVE` 和接收卡片；
- 本地成员忙碌时先记录 `TALK_TO_QUEUED`，不提前显示接收卡片；消息从 inbox
  实际取出后再发送 `TALK_TO_RECEIVE` 和 `DELIVERED_FROM_INBOX` 接收卡片；
- inbox 条目保留远端成员的 roster 引用与原始 `messageId`，因此延迟投递的卡片仍能
  显示正确的对端 `teamMemberId` 和展示名。

卡片属于可重建的 UI 投影，继续遵循普通事件的弱依赖语义；只有承载实际跨实例投递的
`TALK_TO_ROUTE_REQUEST` 使用显式 admission 成败。

## 验收覆盖

测试覆盖以下行为：

- callback 慢或阻塞时，create/delete 命令仍及时返回；
- 单 worker 的事件提交顺序；
- callback 重试运行在 delivery worker；
- 队列满和关闭后提交返回失败并累计拒绝数；
- manager shutdown 不等待阻塞中的 callback；
- `TALK_TO_ROUTE_REQUEST` admission 失败显式返回未提交，并允许相同消息立即重试。
- 混选 Team 跨实例发送、直达接收和 inbox 延迟接收均产生与本地交互一致的卡片。

2026-08-09 最终验收命令与结果：

- `mvn -pl cmd-proxy-app test`：228/228 通过，0 failures，0 errors，0 skipped；
- `mvn -pl cmd-proxy-app -DskipTests package`：成功生成普通 jar 和
  jar-with-dependencies；
- Node.js 编译 ConfigUI 的内联脚本：1 个脚本，syntax ok；
- `git diff --check`：通过。

本次验收不包含提交或部署。
