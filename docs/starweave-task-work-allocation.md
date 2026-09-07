# Starweave 任务模块工作分配

更新时间：2026-09-05（Asia/Shanghai）
用户已授权开始分配开发；技术方案见 [设计稿](starweave-task-module-design.md)，进度入口见 [总进度](starweave-task-progress.md)。

## 实现基线
按设计稿推荐方案启动：SQLite/JDBC、双版本、独立任务 SSE MCP、三种团队成员分发、持久 outbox、一个团队责任成员、taskId 亲和、完成/取消释放占用而挂起保留、终态编辑显式重开、状态变更不强制中断共享会话。上述未单独确认的产品细节属于暂定实现基线，不记为用户逐项确认。混合团队纳入目标范围，外部协调器需要修改时先核实源码及契约并报告，不静默缩减或越权修改外部仓库。

## 分工与文件所有权
| 工作包 | 负责人（不可变 Team ID） | 范围 |
| --- | --- | --- |
| T1 数据与业务 | Cmd Proxy Dev-1 / 140827ab-0d17-3246-8717-f53e993c4353 | SQLite、migration、model、Repository、TaskService、附件、事务 outbox 存储、幂等、REST handler/TaskApiBridge、模块测试、app pom 依赖 |
| T2 执行与 MCP | Code Cmd Dev / b0366fb9-32a3-3709-838b-5acd89c93922 | target resolver、dispatch、outbox 消费、接收确认去重、独立任务 MCP、Provider 注入适配、MAIN/Team/混合投递、事件生成、模块测试 |
| T3 前端 | Code Cmd Dev-1 / bbebc5b7-2e53-3fc9-b2ac-d13a73b7a9f2 | 任务菜单/看板/列表/弹窗、Markdown、附件、备注/历史、分发配置、任务卡实时/历史渲染、前端测试及浏览器验收 |
| T0 集成 | 队长 Cmd Proxy Dev | 契约审查、公共入口接线、跨模块协调、最终测试/打包/集成与双环境验收 |

建议包：acp/task/model、store、service、attachment、api 归 T1；acp/task/dispatch、mcp 归 T2。T3 独占 resources/configui/index.html 和新增任务前端资源。测试按所属模块分目录。

AcpProxy、ConfigUiServer、Main 等公共启动/路由文件由队长集成；T1/T2提供注册及关闭方法和接线说明，未协调不抢改。T2 修改 AcpClient/Team/TalkTo 等公共类前先通过 talk_to 向队长报告文件和必要性。禁止重置、清理或覆盖他人改动，不擅自提交、推送或重启运行实例。

## 接口协作
1. T1 第一里程碑：尽快发布 TaskService/DTO、REST envelope、分页/错误码、附件请求、outbox 存储契约到 starweave-task-contracts.md，并通知 T2/T3 与队长；T1是该契约文件的唯一编辑人，队长审查。
2. 固定基础字段：id/name/contentMarkdown/status/revision/contentVersion/target/assignee/createdAt/updatedAt；状态 START/IN_PROGRESS/COMPLETED/CANCELLED/SUSPENDED；REST 前缀 /api/starweave/v1/tasks。响应沿用现有 Starweave envelope，准确字段由 T1核实后公布。
3. T2 使用 TaskService，不另写状态机、SQL、附件存储；反馈所需 outbox 原子领取、ACK、重试和稳定分配接口，由 T1统一实现。
4. T2 提供 taskId/eventId/contentVersion/事件类型/展示信息等卡片契约给 T1归档、给 T3接入；定义持久接纳与 Agent 开始的不同含义。
5. T3先基于约定 DTO/mock 完成布局，接口就绪后切换真实服务；不得将 mock 或静态演示作为功能交付。
6. 所有跨模块契约改动先发消息说明影响，再由契约所有者更新文档，禁止静默各自改名。

## 里程碑
- M0 契约可用：T1发布服务/REST/outbox 草案，T2补事件和MCP约束，T3核对UI需求，队长定稿。
- M1 模块实现：三人独立实现与聚焦验证，记录真实结果和未覆盖项。
- M2 本地闭环：创建→持久分配→通知→MCP读取→备注→完成；正文变更→版本冲突保护→再次处理。
- M3 可靠性/混合链路：BUSY、满队列、离线、崩溃恢复、去重、跨实例、工具可达性、卡片历史一致。
- M4 集成验收：队长执行干净 Reactor 全量测试及 package；JS语法、diff、敏感文件、浏览器和双环境实测。没有环境的检查必须标未验证，不能宣称全部通过。

## 进度更新规则（每位开发必须执行）
- 收到派单后立即更新自己的进度文件为“已接单/进行中”，记录当前时间、下一步和预计交付的第一个接口。
- 每完成一个可交付小项、接口变化、验证结束、出现/解除阻塞时立即更新；持续工作期间每 15 分钟至少更新一次，不用脚本等待定时或轮询队友。
- 每条更新包含：完成内容、涉及文件、检查命令及真实结果、下一步、阻塞/依赖。无测试不得填写“通过”；不填主观百分比。
- 同时通过 talk_to 通知队长重要里程碑、接口变更和阻塞；需要协作时使用注入名单中的不可变 teamMemberId。
- 三人只编辑各自进度文件；总进度和分工文档由队长维护，避免共同覆盖同一个 Markdown。
- 提交交接前必须更新进度为“待集成”并给出文件清单、接口说明、验证证据及限制；只有队长集成验收后标“完成”。

## 共享构建约定（2026-09-05 10:56补充）
- T1首次compile报告共享target中类文件缺失，原因未确认，不能将并发clean推断记为事实。
- 开发阶段禁止自行在共享工作区运行clean或删除/重建target；已有构建须报告命令和状态，不擅自终止其他开发的进程。
- 各人使用独立/tmp输出目录进行聚焦验证，记录源码、classpath来源、命令、实际测试数和结果。依赖旧target的隔离测试只作有限开发证据。
- 最终由队长串行执行干净Reactor全量测试与package，不能用隔离javac/JUnit替代整体验证。
