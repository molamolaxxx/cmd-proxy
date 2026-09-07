# Starweave 任务模块技术方案（待评审）

日期：2026-09-05。本文是设计稿，不代表功能已实现。

## 1. 范围与建议

新增「任务」菜单，支持手动创建、查询、编辑内容、状态推进、Markdown 备注及附件、任务快照历史、Agent/Team 分配和任务消息卡。后台业务服务同时供 UI REST 与独立任务 MCP 使用，后续上游 API 创建复用服务，本期不交付上游集成。

建议使用 SQLite + JDBC，数据库及任务附件由创建任务的 cmd-proxy 实例持有。执行人所在实例可以不同，但不复制任务权威数据。数据库位置通过 CmdProxyHome.resolve("starweave/tasks/tasks.db") 获取；附件放在同目录 attachments 下，不能放入仓库或会话临时目录。

当前项目 Java 8/Kotlin 1.9；SQLite JDBC 版本需锁定并验证 Java 8、Linux/Windows、assembly 打包及 native 提取。采用少量直接 JDBC DAO 和有序 SQL migration，不引入 ORM 或外部数据库服务。WAL 支持读写并行但只有一个写者，使用短事务、单实例写入调度、busy_timeout、每连接 foreign_keys=ON；禁止数据库事务内启动 Agent 或调用远程服务。备份采用 SQLite 一致性备份或停写后备份，不能在线只复制主 db 文件而漏掉 WAL。

## 2. 当前代码核验与复用边界

- `channel/model/ChannelBinding.java`：MAIN、TEAM_MEMBER；团队选择模式 FIXED、RANDOM、AFFINITY。
- `channel/DefaultChannelBindingResolver.java`：本地实例解析，亲和按群 chatId / 私聊 userId；不能直接承担跨实例任务路由。
- `channel/ChannelTalkToBridge.java` → `talkto/TalkToDispatcher.java`：支持直接投递、内存 inbox 排队及拒绝；内存入队不是持久送达凭据。
- `starweave/StarweaveSessionManager.java` 和 `StarweaveTeamApiBridge.java`：普通会话及本地/协调型团队生命周期入口；混合团队操作必须经协调器并携带稳定 sessionId。
- `action/CmdProxyMcpHttpHandler.java`：现有 runtime MCP 是 loopback、POST-only 端点，不能直接用作 HTTP+SSE 任务服务，也不应放宽其权限以实现任务工具免权限。
- `acpclient/AcpClient.java`：session/new 与 session/load 装配 MCP 配置；部分 Provider 不接受客户端 MCP 注入，必须验证实际 Provider 配置路径。
- `starweave/StarweaveUploadStore.java`：会话上传绑定 group/session/generation，20 MiB 上限、1 小时临时有效期；可借鉴受限文件处理，不能用作任务历史附件库。
- `configui/ConfigUiServer.java`、`resources/configui/index.html`：复用实例切换、弹窗、Markdown 渲染、会话事件投影；任务模块新增独立接口与前端逻辑。

## 3. 数据模型

### 3.1 task：当前任务

| 字段 | 说明 |
| --- | --- |
| id | UUID，不依赖显示名称路由 |
| owner_instance_id | 创建实例，权威数据归属 |
| name | 必填、创建后不可修改，允许重名 |
| content_markdown | 正文 Markdown |
| status | START / IN_PROGRESS / COMPLETED / CANCELLED / SUSPENDED |
| revision | 任务快照版本，创建为 1；正文、状态、实际分配变化时递增 |
| content_version | 需求内容版本，创建为 1；正文或正文附件变化时递增 |
| target_type / target_config_json | 用户选择的 Agent 或 Team 以及分发配置 |
| assignee_ref_json | 解析后的稳定执行引用，分配前为空 |
| creator_type / creator_id / creator_name | 人工创建者记录，名称保留快照 |
| created_at / updated_at / completed_at | 服务端生成 UTC 时间 |

执行引用至少包含 instanceId、ownerId、Agent 稳定配置引用、groupId（适用时）、teamId/teamMemberId（适用时）及名称快照；sessionId、generation、acpClientId 是投递时定位当前运行实例的辅助信息，不能单独用作长期执行人身份。实现时须核实 Agent 配置是否已有不可变 ID，没有则补充持久引用，不能用显示名假装不可变 ID。

用户要求的「agentid」以该稳定执行引用表达，Team 最终解析为一个负责成员。字段用于业务分配与投递，不作为任务 MCP 授权规则。

### 3.2 task_history：任务快照

主键 (task_id, revision)，保存与 task 相同的业务字段快照，包括内容版本、正文附件引用、状态、目标配置、实际执行人、时间，以及 change_type、操作人、变更时间。创建即保存 v1，此后每次任务业务字段变化后保存完整新快照；不关联、不复制备注列表。

revision 记录任务完整演进；content_version 只记录需求变化，避免 Agent 更新状态后误判需求发生变化。按 revision 可精确读取历史，按 contentVersion 可定位该需求版本首次产生的快照。备注查询独立于快照，因此查询旧版任务默认仍返回当前备注，响应明确 commentsScope=CURRENT。

### 3.3 task_comment：备注

id、task_id、author_type(HUMAN/AGENT)、author_id（可空）、author_name、content_markdown、observed_content_version、created_at、request_id。本期备注只追加，创建任务时禁止附带备注；任务创建后人和 Agent 都可备注，包括完成、取消或挂起后。

author_name 是展示及审计信息。按需求不做任务操作权限控制，调用者填写的身份不具备可信认证含义。备注可标记它针对哪个内容版本，防止用户把旧需求下的反馈误认为新需求结果。

### 3.4 附件与可靠投递辅助表

- task_attachment：id、原始文件名、服务端存储键、MIME、大小、SHA-256、创建时间、暂存/已关联状态。
- task_content_attachment：(task_id, content_version, attachment_id)，历史版本附件引用不可覆盖。
- task_comment_attachment：(comment_id, attachment_id)。
- task_outbox：event_id、task_id、content_version、事件类型、任务内顺序号、执行引用、投递状态、尝试次数、下次重试时间、错误摘要、接收凭据。
- task_request_dedup：operation、request_id、请求摘要、结果，用于创建/状态/备注的持久幂等；相同 ID 不同参数报错。
- schema_migration：已应用的数据库结构版本。

索引：task(status, updated_at)、task(created_at)、执行引用中常用查询键；task_comment(task_id, created_at, id)；task_outbox(state, next_attempt_at)。正文首期不做全文检索，名称/ID、状态、执行人和时间筛选配合分页。

## 4. 状态与版本一致性

建议允许：START → IN_PROGRESS/CANCELLED/SUSPENDED；IN_PROGRESS → COMPLETED/CANCELLED/SUSPENDED；SUSPENDED → IN_PROGRESS/CANCELLED。COMPLETED/CANCELLED 如需继续工作，由用户在 UI 显式重新打开到 START，Agent 不能通过普通状态推进自动重开。重复设置当前状态可幂等成功，但仍核验参数与版本。

创建后保持 START；任务分配和消息入队均不自动设为 IN_PROGRESS，Agent 实际开始时调用工具推进。Agent turn 结束、模型 stop 或会话 READY 都不自动完成任务。

挂起/取消阻止尚未发出的执行通知，并新增状态通知告知已分配 Agent。默认不直接 cancel 整个 ACP 会话，因为该会话可能正在处理其他任务；Agent 读到状态后应停止该任务。若需要强制即时中断，应作为独立能力设计，不能把状态更新成功解释为进程已停止。

正文及正文附件保存使用 expectedRevision；同一事务内更新 task、插入 history、关联附件、写 outbox。无实际正文/附件变化时不新增内容版本。状态更新要求 expectedRevision 和 observedContentVersion：过期请求返回 VERSION_CONFLICT/LATEST_CONTENT_REQUIRED，附当前版本，禁止基于旧内容提交完成状态。备注追加不增加 task revision，可针对旧版本补充反馈并标注版本。

编辑已完成/已取消任务时，建议 UI 提示并要求用户选择「同时重新打开」后才能保存新需求；重开与内容变更同事务提交并通知。编辑挂起任务保持挂起，通知明确先了解变更、等待恢复。

## 5. 执行分配与消息可靠性

用户配置与实际分配分开保存：

| 模式 | 首次分配 | 后续行为 |
| --- | --- | --- |
| 具体 Agent | 解析所选 Agent 的可用会话，必要时走既有生命周期入口创建/恢复 | 固定 Agent，不因 BUSY 改派 |
| Team / FIXED | 指定成员 | 固定成员 |
| Team / RANDOM | 首次随机选一个符合条件成员，原子保存 | 重试、编辑内容均复用已选成员 |
| Team / AFFINITY | 以 taskId 作为任务亲和键，优先尚无活跃任务占用的成员，全部占用后确定性 Hash 复用 | 同一任务长期固定成员；BUSY/暂时离线不漂移 |

AFFINITY 复用企微的「优先未占用、然后 Hash、持久绑定」语义，但任务没有 userId/chatId，不能原样复制 routing key。建议占用按未完成、未取消任务计算，挂起保留占用；结束释放选择优先级，但历史执行引用保留。未来上游 API 可增加显式 affinityKey，让关联任务共享成员。这一规则需产品确认。

Team 模式表示选择一个责任成员，由其通过既有 Team 能力协调其他成员，不自动广播给全队。首期编辑弹窗默认不支持改派；原成员被删除或永久不可用时保留明确失败原因，需另行确定是否加入「重新分配」动作，不能静默随机换人。

完整链路：

1. 创建事务写入任务 v1、附件引用和待分配事件，提交后返回任务详情。
2. TaskDispatchService 原子确定并保存实际执行人及新任务快照，同时写 TASK_ASSIGNED outbox；分配阶段崩溃可恢复，重试不得重新随机选人。
3. 投递前核对最新任务状态与执行目标、session/generation，确认任务 MCP 已进入目标 Provider 的实际工具配置。没有会话时通过既有生命周期入口创建/恢复，不误恢复已删除槽位。
4. READY 时可唤起执行，BUSY 时进入任务持久队列并按顺序等待；容量不足和离线保留失败/重试状态，不能宣称已处理。
5. 卡片包含 eventId、任务 ID、名称、内容版本、状态、摘要、查看入口；Agent 收到相同事件的可执行文本，要求先 get_task，再推进状态、备注、最后基于最新内容完成。
6. 正文变化产生 TASK_CONTENT_CHANGED，携带旧/新版本；重试顺序按任务保持，投递前取消不再适用的旧执行请求。多次编辑可合并唤醒通知，但数据库历史及各原始变更事件保留，卡片注明合并范围。
7. 会话实时消息与持久历史用同一 eventId 投影，刷新/重连不重复显示；MCP 普通工具卡与任务业务卡定义统一显示规则，避免重复业务卡。

outbox 与数据库事务保证保存成功后的通知意图不丢失；真正可靠送达仍需接收侧按 eventId 持久接纳和去重。现有 TalkTo 内存 inbox 不能充当此凭据，需要新增任务投递适配及接收确认。ACK 表示已持久接纳，Agent 更新 IN_PROGRESS 才表示开始；崩溃窗口中不能承诺外部工作 exactly-once，Agent 必须先读当前任务再恢复。

投递状态独立显示为待分配、待投递、已排队、已接纳、失败待重试。关机停止新投递、收束有界工作线程、关闭数据库；重启从 outbox 恢复。任务页可手动重试通知，不能通过重复创建替代重试。

## 6. 独立任务 MCP

服务名 `starweave-tasks`，cmd-proxy 进程内独立生命周期及工具集合，可独立监听端口；不依赖浏览器页面是否打开。按用户要求提供 HTTP+SSE：`GET /task-mcp/sse` 建立通道、`POST /task-mcp/messages?sessionId=...` 提交 JSON-RPC，SSE 推送协议响应。传输 sessionId 只关联连接，不承担用户身份权限。规范版本协商必须只返回真正支持的版本。

HTTP+SSE 是旧式 MCP 传输；现代 Streamable HTTP 与它不是同一协议。为 Provider 兼容，可同服务补充 `/task-mcp/mcp` Streamable HTTP 适配，两种传输共享 TaskService、工具 schema 和幂等机制。实现验收须分别验证，不能将普通页面 SSE 当作 MCP SSE。

严格保留三个业务工具：

| 工具 | 参数与行为 |
| --- | --- |
| get_task | taskId，revision/contentVersion 可选且互斥；默认最新；返回所查快照、当前任务头(currentRevision/currentContentVersion/currentStatus)、附件、当前备注第一页、备注游标及历史版本分页索引；可用游标继续读取，绝不静默截断 |
| update_task_status | taskId、status、expectedRevision、observedContentVersion、requestId、actorName；核验状态转换、版本并持久幂等 |
| add_task_comment | taskId、contentMarkdown、attachmentIds、observedContentVersion、authorName、requestId；追加备注，正文和附件不得同时为空 |

按需求三个任务工具均不做登录、角色、任务归属或执行人权限校验；仍做参数校验、合法状态转换、版本冲突、幂等和资源限制。此处不授权绕过现有混合团队的 transport/owner 路由约束。

附件上传/下载使用任务服务 HTTP 文件端点，Agent 上传后获得 attachmentId，再经 add_task_comment 关联；不额外增加业务 MCP 工具。get_task 返回可访问下载地址和服务说明，注入上下文给出上传方法。不支持 HTTP 上传的 Agent/运行环境必须在验收时暴露兼容性缺口，不能假定路径即上传。

在 session/new、session/load 及 Provider 自身配置路径注入 MCP，Provider 若不支持动态增加工具，对已有会话提示需在安全时机恢复/重建后生效。派单前验证任务工具配置已具备，不向无法回写任务的会话盲投。

跨实例时 MCP 指向任务创建实例对目标运行环境可达的 advertisedBaseUrl，不使用远程 Agent 的 localhost。混合团队任务通知必须经已有可信协调器路由；如果需要扩展协调器协议，必须将 MolaChat 配套修改纳入交付清单。协调器离线、安全握手未恢复或 MCP 不可达时保留任务并显示阻塞，不降级为本地投递。

## 7. 附件与前端接口

任务文件上传独立于 ACP session 生命周期。首期建议单文件 20 MiB、每次最多 10 个，前后端同时验证并限制单任务累计体积。暂存附件 24 小时清理，只有未关联资源可回收；任何当前版本、历史版本或备注仍引用的文件不能清理。文件内容不可覆盖，附件替换生成新 ID。

客户端只提交服务端 attachmentId，不提交服务器路径或远程 URL 让服务端抓取。下载按 ID 在任务资源根内解析，阻止路径逃逸和符号链接逃逸；Markdown 转义 HTML、限制链接协议，危险类型默认下载，不能直接以同源 HTML 执行。大附件使用流式处理，避免 base64 使整个请求膨胀。

建议 REST 前缀 `/api/starweave/v1/tasks`：

- GET 集合与 `/stats`：分页、名称/ID、状态、执行人、时间范围；统计遵守相同非状态筛选条件，点击状态卡仅改变列表状态条件。
- POST 集合：手动创建，requestId 持久幂等。
- GET `/{id}`，PATCH `/{id}`：读取/编辑正文及附件，PATCH 拒绝 name 和未知可写字段。
- POST `/{id}/status`，GET/POST `/{id}/comments`，GET `/{id}/history`，GET `/{id}/history/{revision}`。
- POST `/attachments`，GET `/attachments/{id}/download`。
- POST `/{id}/delivery/retry`：重试现有事件。

UI 和 MCP 同用 TaskService，不能各写一套状态机。第一期任务列表可采用页面可见时短轮询并在操作后立即刷新；会话任务卡继续走既有事件 SSE。实例切换或弹窗关闭后丢弃旧请求响应。

页面顶部保留留白，不显示「任务」标题；右上角创建按钮。其下五张状态统计卡，再下方筛选条和分页列表，列包括名称、状态、执行人/团队及实际成员、内容版本、更新时间、投递异常摘要、查看/编辑。

创建/编辑共用宽屏弹窗外壳：创建显示名称、Markdown 编辑器（工具栏、编辑/预览、拖拽/选择附件）、执行目标配置，不出现备注输入；编辑名称只读，内容编辑/预览、状态操作、备注时间线和备注编辑器、历史版本入口。备注独立提交，正文草稿未保存时有明确提示，不能暗中把草稿一起保存。关闭取消不污染列表数据。移动端近全屏，弹窗内部独立滚动并锁定背景滚动。

## 8. 实现拆分与验收

拟新增 `acp/task/`：TaskService、TaskRepository、TaskMigrationRunner、TaskDispatchService、TaskTargetResolver、TaskAttachmentStore、TaskMcpServer、TaskApiBridge。接入 AcpProxy 启停、ConfigUiServer 路由、会话 MCP 装配、会话/Team 任务消息及历史投影。避免将任务逻辑塞进 ScheduleTaskManager；定时调度与业务任务不同。

实施顺序：数据库/领域服务 → REST 与页面/附件 → 本地分配和持久投递 → 独立 MCP 与 Provider 实测 → 混合团队协议及双环境验收。这里只列工作包，评审通过前不派发开发或修改运行配置。

验收覆盖：

1. 创建没有备注、名称不可改、状态看板筛选、弹窗草稿隔离、移动端、Markdown 和附件真实浏览器验收。
2. 同时编辑、状态与正文竞态、过期版本完成被拒、重复请求、历史快照完整和历史附件仍可下载。
3. FIXED/RANDOM/AFFINITY 首次分配、持久绑定、结束释放占用、BUSY、队列满、离线、成员删除、重启及重试。
4. 数据库提交后崩溃、接收后 ACK 丢失、接纳后进程退出；恢复不丢通知，重复卡片去重，并明确外部工作不可保证 exactly-once。
5. 三个 MCP 工具的真实 initialize/tools/list/tools/call、SSE 重连、并发请求、错误与资源限制；各实际 Provider new/load 后工具可见且可回写。
6. 本地 MAIN、本地 Team、混合 Team 的任务卡实时与历史一致；协调器离线不降级；远程 Agent 可访问任务 MCP 和附件。
7. 实施完成后以干净 Reactor 全量测试和 package 为整体验证基线，再检查 git diff --check、前端 JS 语法、敏感文件及浏览器/双环境结果；仅设计稿阶段不运行构建，不宣称运行通过。

## 9. 待对齐决策

1. Team 任务先分配一个责任成员，由其协调全队；不广播，是否符合预期？
2. AFFINITY 用 taskId，优先无活跃任务占用成员；完成/取消释放占用、挂起保留，是否接受？
3. 完成/取消后编辑需求须显式重开；挂起/取消为任务协作状态，默认不强制中断共享会话，是否接受？
4. 第一版是否必须覆盖跨环境混合团队？建议覆盖现有 Starweave 可选执行目标，但这会增加协调器协议和任务 MCP 网络可达性验收工作。

## 10. 外部依据

- SQLite WAL（并发及备份注意事项）：https://www.sqlite.org/wal.html
- SQLite JDBC（跨平台打包及驱动）：https://github.com/xerial/sqlite-jdbc
- MCP HTTP+SSE 2024-11-05：https://modelcontextprotocol.io/specification/2024-11-05/basic/transports
- MCP Streamable HTTP 2025-06-18：https://modelcontextprotocol.io/specification/2025-06-18/basic/transports
