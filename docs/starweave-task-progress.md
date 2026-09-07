# Starweave 任务模块总进度

更新时间：2026-09-05 14:58（Asia/Shanghai）
[技术方案](starweave-task-module-design.md) · [工作分配与更新规则](starweave-task-work-allocation.md)

此表由队长维护，各成员实时详情以各自进度文件为准。派单成功仅表示消息被路由接收，不表示开发已接单或完成。

| 工作包 | 负责人 | 当前状态 | 实时进度 |
| --- | --- | --- | --- |
| T0 | 临时队长 | 本地 TaskService/REST/MCP/Provider/dispatch/receipt/卡片生命周期已完成接线 | 本文件 |
| T1 | 开发1 | 数据、领域服务、REST、附件、outbox与fencing测试已集成通过 | [数据与业务](starweave-task-progress-dev1.md) |
| T2 | 开发2 | 本地 MAIN/Team 执行、MCP与卡片投影已集成通过；混合协议保持 fail-closed | [执行与 MCP](starweave-task-progress-dev2.md) |
| T3 | 开发3 | 页面与任务卡已集成，静态/浏览器mock/模块测试通过；待真实实例浏览器验收 | [页面与卡片](starweave-task-progress-dev3.md) |

## 当前里程碑
- 技术方案已落盘。
- 工作分配、文件所有权、更新规则已落盘。
- 服务/REST/outbox 契约：[v1 草案](starweave-task-contracts.md)已发布，队长完成首轮审查并反馈修订；T2/T3可依主体契约并行，卡片细节待 T2补充。
- 本地业务实现与代码级集成：完成；完整 Reactor 测试和 package 已通过。
- 真实 Provider/浏览器实例验收：未执行（遵守不重启现有实例约束）。
- 混合 Team 双环境：外部协调器尚无 `TASK_ACCEPT`、稳定 participant identity 与执行实例持久 receipt 协议，本仓库保持 fail-closed，未伪报完成。

## 队长更新记录
- 2026-09-05：用户授权开始派单。暂定规则沿用设计稿推荐方案，未将未回答的问题伪记为逐项确认。创建分工及三份独立开发进度文件。
- 2026-09-05：已通过 Team talk_to 向三位开发发送具体任务和进度更新要求，三次工具调用均返回发送成功；尚未收到接单或开发完成回执。
- 2026-09-05 10:47：收到 T2 接单回执并核对其进度文件。正在核验执行入口、Provider MCP 与 Team/TalkTo/SSE 链路；首个交付为卡片事件 schema、投递适配边界及 outbox 接口需求，依赖 T1 业务契约。尚未交付功能或运行测试。
- 2026-09-05 10:48：收到 T3 接单回执并核对其进度文件。正在核验 ConfigUI 前端链路及实现页面骨架；依赖 T1 REST/上传契约和 T2 卡片事件契约，公共启动/路由入口仍由队长维护。尚未交付完整页面或浏览器验收结果。
- 2026-09-05 10:48：收到 T1 接单及契约 v1 发布回执；完成首轮契约审查并向三位开发同步。要求修订精确执行人筛选、内部创建事件完成与外部 ACK 区分、过期租约隔离、接收侧并发恢复、挂起未分配后恢复及远程附件 URL 等边界。三位开发均已接单，尚无完整功能验收结果。
- 2026-09-05 10:50：T2报告Agent配置稳定ID及跨实例receipt边界。队长核验AgentAddress、AcpClient MCP装配，授权T2限范围修改AcpClient附加任务MCP合并及测试，保留Provider门控和现有runtime权限。确认接收凭据在执行实例持久化、与owner任务数据分离，通知T1/T2协同契约；稳定Agent身份方案仍待核验，混合任务协议仍待实现。
- 2026-09-05 10:55（回执时间）：T1数据库、Repository、TaskService、附件及REST初版已落盘，开始聚焦编译/事务回归，尚无通过结果。队长核对API bridge与handler接口，确认outbox已校验租约有效期；接收侧领取/恢复和契约v2仍待完成。队长暂不并行运行Maven以避免共享target争用，并要求修正T1进度顶部及时间记录。
- 2026-09-05 10:56：确认T2公共文件方案：AcpClient.setAdditionalMcpServers在new/load共用路径合并附加配置；要求深拷贝、同名冲突明确拒绝、保留Provider门控和原runtime配置，运行中设置不宣称热生效。启动前hook由T2提供、队长接线；提醒与T1协调Maven验证时段。
- 2026-09-05 10:56：T1报告首次mvn compile失败，ExternalTalkToContactProvider.class出现FileNotFoundException，是否并发clean尚未证实。T1改用/tmp隔离javac/JUnit，8组业务测试已编写但尚无通过回执。队长向全员发送共享构建约定并写入分工文档，最终干净Reactor验证仍待执行。
- 2026-09-05 10:59（回执时间）：T3页面初版已落盘，报告Node语法与diff检查通过，真实REST/事件联调未进行。T0新增ConfigUiServer任务前缀代理及动态bridge解析、AcpProxy任务服务创建/撤销/关闭；源码未构建部署，MCP/dispatch与卡片投影仍待接线。已向T2/T3通知边界，不将源码接线当作运行成功。
- 2026-09-05 11:01（回执时间）：T1报告7个生产类、2个JUnit类，经/tmp/verify-task-t1.py隔离Java8/JUnit验证13 tests通过；队长核对进度记录与新增事务内候选分配接口，未独立重跑。契约仍有精确过滤、接收领取恢复等未关闭审查项，要求T1继续修订，非最终完成。全量Reactor/package待三模块及接线稳定后统一执行。
- 2026-09-05 11:02（回执时间）：T1提交待集成交接，报告最终修改后11个服务测试与2个真实HTTP测试通过，已通知T2候选原子分配重载。队长复核当前契约/Repository仍存在先前待修事项，交接不视为审查完成；修订要求已在T1 inbox，避免重复消息挤满队列。
- 2026-09-05 11:05（回执时间）：T2报告首轮13项聚焦测试通过（分配/outbox/SSE/MCP合并），新receiver/sink/目录待补测。混合roster缺participantInstanceId/sourceRobotId，gateway缺TASK_ACCEPT及执行实例ACK，目前显式阻塞，未实现混合执行。队长审查发现接收恢复并发、send接纳即complete的崩溃窗口、恢复前版本/状态核验、卡片与请求幂等边界，详见[集成审查待办](starweave-task-integration-review.md)。本次talk_to发送失败（T2 inbox已满10/10），待其处理消息后再通知，未记为送达。
- 2026-09-05 14:52：临时队长完成公共组合根接线：任务 MCP 在会话恢复前启动并进入 MAIN/Team `session/new` 与 `session/load`；客户端恢复后启动 receipt recovery/outbox dispatch；关闭顺序调整为 dispatch→receiver→MCP→bridge/service。卡片通过 listener 进入 Starweave MAIN 与 Team 的实时/历史投影，普通 MAIN 提供兼容展示；Team 请求使用稳定 eventId。receipt 恢复会淘汰挂起、终态及旧内容可执行事件。
- 2026-09-05 14:58：验证完成：最终根 `mvn test` 共 497 tests 全绿，三模块 Reactor 全绿；最终根 `mvn -DskipTests package` 三模块及 assembly 全绿；inline JavaScript 语法、`git diff --check`、本次改动范围敏感值扫描通过。未执行 clean、提交、推送或运行实例重启。
