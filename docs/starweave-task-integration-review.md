# 任务模块集成审查待办

2026-09-05 11:05，队长源码审查记录。用于集成门槛，不代表已修复。

## T2 新增接收与执行代码
- LocalTaskDeliveryAdapter.deliver与recoverPending可能并发submit同一记录，需要与T1协调持久领取/互斥、租约fencing及恢复测试。
- send返回accepted立即completeReceipt会使Agent尚未推进状态时的崩溃失去接收恢复依据；区分持久接纳、已提交及业务开始，恢复核验当前任务，不承诺外部工作exactly-once。
- 恢复旧receipt前查询最新任务状态/contentVersion和当前session/generation，取消/挂起/旧内容不可盲目重发。
- LocalTaskPromptSink先project再send，需明确卡片是接纳通知还是执行确认，失败不留虚假成功状态，实时及历史按eventId幂等。
- Team发送使用随机requestId，应核验TeamManager契约后使用稳定事件请求标识，保持崩溃重试去重语义。
- MCP descriptor目前http类型指向legacy /sse，必须验证真实ACP transport兼容性。
- 本地目录robotId仍需核实持久配置身份，不把name派生值说成不可变ID。

## 混合团队
当前缺participantInstanceId/sourceRobotId及TASK_ACCEPT/执行实例receipt ACK协议，只有fail-closed适配不算实现。T2需提供具体字段、请求/响应/恢复语义及只读定位协调器源码结果，队长纳入后续集成。

## 2026-09-05 14:52 收敛结果

- 已用 fenced `claimReceipts/completeReceipt/retryReceipt` 消除本地并发领取；周期恢复已覆盖测试。
- receipt 恢复前会核对当前任务状态和 contentVersion，挂起、终态、旧内容的可执行事件直接完成receipt而不重发prompt。
- 卡片改为执行入口返回 accepted 后投影；Team send 使用业务 `eventId` 作为稳定 requestId。
- `STARWEAVE_TASK` 通过 listener 投影至 Starweave MAIN 持久事件流和 Team 实时/历史流，普通 MAIN 提供兼容展示；UI按eventId统一去重。
- Task MCP descriptor 已在 `AcpClient` 启动前注入，new/load共用；HTTP+SSE真实 initialize/tools/list/tools/call 测试通过。
- robot ID 仍遵循现有权威 source descriptor 的 `acp-` 规范，并统一半角/全角空格归一化；这不是跨实例自报身份授权。
- 混合 Team 缺口未在本仓库内伪造：协调器离线或远端身份/receipt能力不可用时继续fail-closed。

## 验证与通知
T2首轮13条测试不能覆盖之后新增receiver/sink代码，需补充上述边界回归。继续使用隔离构建输出。
11:05发送上述审查意见的talk_to明确失败：T2 inbox已满10/10；因此改为此文档留档，待其处理已有消息后再通知。没有将失败发送记为已送达。
