# 开发2进度：执行与 MCP

关联：[工作分配与更新规则](starweave-task-work-allocation.md) · [总进度](starweave-task-progress.md)

## 当前状态
- 状态：T2 本地执行链已完成集成；混合链等待外部协调器
- 更新时间：2026-09-05 14:58（Asia/Shanghai）
- 已完成：完成 T1 TaskService/outbox 对接；实现 outbox 原子领取/分配/ACK/退避重试/过期事件丢弃、执行实例 receipt 去重与恢复、MAIN/本地 Team prompt sink、混合协调器 fail-closed 适配契约、TaskService MCP 适配及 AcpClient 运行时 MCP 注入
- 当前工作：本地范围已收敛，保留混合 Team fail-closed 边界
- 下一步：具备隔离实例后执行真实 Provider/浏览器闭环；外部协调器协议落地后补双环境验收
- 阻塞/依赖：仅剩仓库外的混合 Team 协调器能力：稳定 participant identity、`TASK_ACCEPT` 与执行实例持久 receipt；现有 gateway 无对应 operation，本仓库保持 fail-closed
- 验证：T2 聚焦 17 tests 全绿，最终模块 497 tests 全绿，根 Reactor test/package 全绿；覆盖运行时 MCP 合并、真实HTTP+SSE、固定/随机/亲和解析、本地稳定地址、outbox分配/ACK、receiver fencing去重与恢复；`git diff --check`通过

## 更新记录
由本工作包负责人追加，格式：
- 时间：
- 完成内容及文件：
- 验证命令/结果：
- 下一步：
- 阻塞或所需协作：

- 时间：2026-09-05 10:47（Asia/Shanghai）
- 完成内容及文件：接单并完成必读材料核验；更新 `docs/starweave-task-progress-dev2.md`
- 验证命令/结果：`git status --short` 成功；确认现有未跟踪文档属于共享任务改动，未覆盖或清理
- 下一步：沿执行入口 → Provider MCP 配置 → 会话/Team/TalkTo 投递 → SSE/历史投影追踪完整调用链，并向 T1/队长同步接口需求
- 阻塞或所需协作：等待 T1 的 TaskService/DTO/outbox 契约；公共类若确需修改将先报队长

- 时间：2026-09-05 10:56（Asia/Shanghai）
- 完成内容及文件：新增 `acp/task/dispatch/TaskExecutionDirectory.java`、`TaskTargetResolver.java`、`TaskCardEventFactory.java` 及测试；新增 `acp/task/mcp/TaskMcpOperations.java`、`TaskMcpServer.java` 及 HTTP+SSE 测试；卡片 schema 已发 T3，契约风险已发 T1/队长
- 验证命令/结果：`mvn -pl cmd-proxy-app -Dtest=TaskTargetResolverTest,TaskCardEventFactoryTest test` 首次失败，原因是沙箱无法创建 `~/.m2/...sqlite-jdbc...lock`，并非测试断言失败；授权后的同命令仍在下载新依赖
- 下一步：完成聚焦测试，修正编译/协议问题；接入 T1 TaskService 和 outbox 契约
- 阻塞或所需协作：T1需确认 TaskService准确包名、robotId/agentId 契约、跨实例 receiver receipt 的本地持久化所有权

- 时间：2026-09-05 11:06（Asia/Shanghai）
- 完成内容及文件：新增 `TaskDispatchService`、delivery/router、local receipt、MAIN/Team prompt sink、mixed coordinator adapter、local execution directory、TaskService MCP adapter；`AcpClient.java` 增加启动前附加 MCP server 快照并统一进入 new/load 装配
- 验证命令/结果：扩展聚焦测试通过 13 条（0 failures/errors）；真实 HTTP+SSE initialize/tools/list/tools/call 已在回环端口执行，不是仅单元模拟
- 下一步：补测 receipt 去重/恢复和本地稳定地址；检查全模块测试与 diff；形成 T0 接线参数清单
- 阻塞或所需协作：混合 Team 当前 coordinator roster 不含 participantInstanceId/sourceRobotId，且 gateway 无 TASK_ACCEPT/receiver ACK operation；已用接口 fail-closed 隔离，不能宣称混合链路可用

- 时间：2026-09-05 11:13（Asia/Shanghai）
- 完成内容及文件：适配 T1 v2 fencing：候选发现后用 `assign(taskId,candidates,mode)` 原子选择，TASK_CREATED 用 `completeAssignment`，receipt 用 claim/leaseToken/complete/retry；增加本地 receiver 周期恢复
- 验证命令/结果：`mvn -pl cmd-proxy-app -Dtest=TaskTargetResolverTest,LocalTaskExecutionDirectoryTest,TaskCardEventFactoryTest,TaskDispatchServiceTest,LocalTaskDeliveryAdapterTest,TaskMcpServerTest,AcpClientBuiltInMcpTest test` 成功，17 tests，0 failures/errors/skipped；前一轮按旧契约出现3项失败，已确认是T1接口并发升级并完成适配，不再存在
- 下一步：运行 `mvn -pl cmd-proxy-app test` 模块回归和最终差异检查
- 阻塞或所需协作：T0需决定任务MCP监听端口/advertisedBaseUrl并加入实例能力快照；跨实例协调器需携带participantInstanceId、TASK_ACCEPT及持久receipt响应

- 时间：2026-09-05 14:34（Asia/Shanghai）
- 完成内容及文件：T3 已确认按最终 `STARWEAVE_TASK` payload 接入，实时/历史统一以 `eventId` 去重，普通 MCP 卡不派生业务卡；完成模块级回归取证并将失败归属及改法同步 T1/队长
- 验证命令/结果：`mvn -pl cmd-proxy-app test` 共 494 tests，T2 相关测试全部通过；模块仅 `TaskServiceTest` 失败 2 项，分别是旧的 TASK_CREATED acknowledge 流程和无 leaseToken 的 `completeReceipt` 调用；`git diff --check` 通过
- 下一步：T1 更新测试后复跑模块回归；配合 T0 验证 TaskMcpServer/AcpClient/dispatch/receiver 启停接线
- 阻塞或所需协作：模块全绿依赖 T1 将测试迁移到 `completeAssignment` 与 fenced `claimReceipts/completeReceipt`；混合 Team 仍依赖外部协调器能力扩展

- 时间：2026-09-05 14:58（Asia/Shanghai）
- 完成内容及文件：完成 `AcpProxy` 组合根、MAIN/Team listener 卡片投影、稳定 Team requestId、普通 MAIN 历史兼容、receipt 恢复前最新状态/内容核验；T1 两处旧 fencing 测试已迁移
- 验证命令/结果：最终根 `mvn test` 共497项全绿且三模块 Reactor 全绿；最终根 `mvn -DskipTests package`及assembly全绿；JS语法/diff/本次改动范围敏感值检查通过
- 下一步：真实运行实例验收与外部混合协调器联调
- 阻塞或所需协作：混合 Team 的 `TASK_ACCEPT`/participant identity/执行实例receipt不在当前仓库，保持fail-closed

## 交接清单
- 修改文件：原 T2 范围为 `acp/task/dispatch/*`、`acp/task/mcp/*`、`AcpClient.java` 及对应测试；临时接任队长后补齐 `AcpProxy` 组合根、listener/Team 卡片投影、普通 MAIN 历史恢复及相关测试与总进度记录
- 对外接口及公共入口接线说明：先启动 `TaskMcpServer` 并把 `acpServerDescriptor(advertisedBaseUrl)` 通过 `AcpClient.setAdditionalMcpServers` 注入，再恢复 MAIN/Team；构造 local directory/prompt sink/receipt adapter/router/dispatch；关闭时先停 dispatch/receiver/MCP，再 clear bridge/close TaskService
- 验证证据：T2 聚焦 17 tests 全绿，含真实回环 HTTP+SSE；最终模块 497 tests 全绿，根 Reactor test/package 全绿；`git diff --check` 通过
- 未覆盖项与风险：外部协调器尚无 TASK_ACCEPT/持久 receipt，混合 Team 必须 fail-closed；MCP 监听端口与 advertisedBaseUrl 必须进入可信实例能力快照，远端不可使用 localhost
