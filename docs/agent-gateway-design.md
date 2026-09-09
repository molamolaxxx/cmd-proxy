# 智能体网关技术设计方案

## 1. 结论

在 ConfigUI 的“消息渠道”页面增加第三个页签“智能体网关”。管理员可创建多个网关配置，
每个配置绑定一个明确的运行中智能体目标，并生成独立鉴权码。外部系统通过同一个共享的
Agent Gateway 服务端口接入；服务端依据鉴权码确定网关配置、目标智能体和权限。

首期支持三类目标：

1. `STARWEAVE_MAIN`：本环境已开启的 Starweave MAIN 会话；
2. `TEAM_MEMBER`：当前实例中已启动的 Fast Team 成员；
3. `MOLACHAT_MAIN`：当前 cmd-proxy 中已启动的 MolaChat MAIN 会话。

网关是已有会话的一个新传输面和结构化输出投影，不复制 Provider session，也不伪造
MolaChat 用户。因此，外部发送、取消和新建会话都会作用于所绑定的真实会话；MolaChat、
Starweave 或团队页面若同时在线，也会看到同一个会话的变化。ConfigUI 保存配置时必须显著
提示这一共享会话语义。

所有 Agent 输出统一转换为版本化结构化事件，并在发送 WebSocket 前写入网关事件日志。
文字增量、工具调用、子 Agent、定时任务、TalkTo、上下文压缩、任务卡片、团队状态、错误、
完成和会话切换都必须输出，外部前端自行渲染。网关不得复用 MolaChat 已渲染的 HTML 作为
唯一协议，也不得只返回最终文本。

## 2. 需求范围

### 2.1 包含

- “消息渠道 / 智能体网关”二级页签；
- 网关配置的新增、编辑、启停、删除、鉴权码生成与轮换；
- MolaChat MAIN、Starweave MAIN、Fast Team 成员的统一目标选择；
- 一个共享监听端口上的 WebSocket 服务和 HTTP API；
- Bearer 鉴权、连接限额、请求限流和审计；
- 文本以及 URL 形式附件的入站消息；
- 全量结构化出站事件、断线续传、事件去重和慢消费者隔离；
- 会话状态查询、取消当前 turn、新建会话；
- WebSocket 与 HTTP 命令的统一幂等、并发和错误语义；
- 服务重启后恢复网关配置、鉴权、事件序号和启用状态。

### 2.2 首期不包含

- 让调用方在请求中任意指定或切换智能体；
- 暴露 sub-agent、memory、ability 等内部 client；
- 通过公网自动签发证书；TLS 由反向代理或部署方提供；
- 外部系统直接提交本地文件路径或 Base64 大文件；
- 修改、删除或重写 Provider 历史消息；
- 将内部 ACP harness、权限控制帧、心跳、调试日志当成聊天内容返回；
- 在协调器不可用时绕过权限直接连接远程 Team member；
- 对一个 Team 使用随机或亲和路由。网关配置固定到一个成员；队长模式只能固定到队长。

## 3. 与现有实现的关系

当前仓库已有三套输出投影：

| Surface | 当前入口 | 当前输出 |
|---|---|---|
| MolaChat MAIN | `acpSendMessage` 等 RPC command | `DefaultAcpResponseListener` 渲染为 Markdown/HTML 后执行 `acp` callback |
| Starweave MAIN | ConfigUI REST | `StarweaveAcpResponseListener` 写结构化 JSONL，并通过 SSE 返回 |
| Fast Team | Team command / Starweave Team bridge | `TeamAcpResponseListener` 产生 `TeamEventEnvelope` |

现有 `MainSessionApplicationService` 已提供 MAIN 会话的 send、cancel、status、sessionId、
new/restore 所需底层能力；`TeamManager` 已提供 Team member 的对应命令。新网关应在这些应用层
边界之上增加统一适配器，不应复制 ACP client 的启动、取消和会话替换逻辑。

MolaChat MAIN 当前只有一个 `globalListener`。网关不得用自己的 listener 覆盖
`DefaultAcpResponseListener`，否则 MolaChat 会丢失消息。需要引入多投影机制，让原有投影与
网关投影并行工作。

## 4. 核心设计决策

### 4.1 一个共享物理服务，多个逻辑网关

配置层可以有多个 `AgentGatewayConfig`，但进程内只启动一个 `AgentGatewayServer`：

```text
External System
  | Authorization: Bearer <token>
  | WebSocket / HTTP
  v
AgentGatewayServer (one bind host/port)
  |
  +-- token A -> gateway A -> STARWEAVE_MAIN/group-A
  +-- token B -> gateway B -> TEAM_MEMBER/team-B/member-1
  +-- token C -> gateway C -> MOLACHAT_MAIN/group-C
```

共享端口避免配置数量增长造成端口冲突，且便于统一 TLS、反向代理、连接池、限流和健康检查。
“启用网关”表示该逻辑配置开始接受鉴权和连接；当至少一个配置启用时启动物理服务，全部停用
后可停止服务，也可保留监听并对所有业务请求返回 `GATEWAY_DISABLED`。

现有 ConfigUI 使用 JDK `HttpServer`，不适合直接升级 WebSocket。建议新增独立的、Java 8
兼容的嵌入式服务器模块，同时承载网关 HTTP 与 WebSocket；依赖版本应固定并经过依赖扫描。
当前项目仍以 Java 8 编译，因此首版固定使用该分支最后发布的 Jetty `9.4.58.v20250814`；
Jetty 9 已结束社区支持，项目升级到 Java 17 时应同步迁移到受支持的 Jetty 12。
ConfigUI 仍使用现有端口，不因网关流量占满管理线程池。

默认绑定 `127.0.0.1`。需要外部访问时，由管理员明确改为内网地址，生产环境推荐在前面放置
TLS 反向代理。禁止默认监听所有网卡。

### 4.2 固定目标，调用方不可覆盖

鉴权码与 `gatewayId` 一一对应，网关配置固定目标。外部请求不接受 `groupId`、`teamId`、
`teamMemberId` 或 `robotName` 等路由字段，防止拿到一个 token 后横向访问其他智能体。

目标使用稳定、带 surface 的地址，而不是仅用 robotName：

```json
{
  "type": "MOLACHAT_MAIN",
  "instanceId": "instance-a",
  "ownerId": "mola-user-1",
  "groupId": "...",
  "robotId": "acp-order-agent"
}
```

TEAM 目标额外保存 `teamId`、`teamMemberId`、`acpClientId` 和协调器定位信息。保存配置和每次
执行命令时都重新解析权威运行时，不能长期缓存 `AcpClient` 引用。

### 4.3 共享会话语义

首期网关绑定现有会话槽位：

- 外部消息进入所选会话的当前 Provider session；
- `cancel` 取消该会话当前 turn；
- `new session` 替换该会话的当前 Provider session；
- 同一个网关 token 建立的多个连接观察同一事件流；
- 原 MolaChat/Starweave/Team UI 与外部系统可能同时操作，所有变更用 sessionId、epoch 和
  Team version 做乐观并发保护。

如果未来需要“同一智能体模板、外部客户各自独立上下文”，应另增
`sessionMode=DEDICATED`，由网关拥有独立 `GATEWAY` surface 和会话槽位。不要在首期偷偷
复制 client，因为这会改变 TalkTo、Team、记忆、schedule 和历史的 owner 语义。

### 4.4 结构化事件优先，渲染由外部前端负责

网关协议以领域事件为主，MolaChat HTML 仅可作为可选兼容字段。统一事件至少包含：

- `assistant.message.delta`；
- `tool_call.updated`；
- `sub_agent.updated`；
- `schedule.updated`；
- `talk_to.updated`；
- `compaction.completed`；
- `task.updated`；
- `team.state.changed` / `team.member.state.changed`；
- `turn.completed` / `turn.error` / `turn.cancelled`；
- `session.state.changed` / `session.replaced`；
- `resource.created`。

每个事件保留规范化 `payload`，并在 `source` 中保留原 surface、原事件类型、原事件 ID 和
原始结构化数据。未知的新卡片类型使用 `extension.event` 透传，不能静默丢弃。内部控制帧不
作为 UI 事件暴露，但由其产生的用户可见结果必须暴露。

## 5. 总体架构

```text
ConfigUI
  +-- AgentGatewayConfigStore
  +-- AgentGatewayAdminService
  +-- AgentGatewayTargetCatalog

AgentGatewayServer
  +-- BearerAuthenticator
  +-- WebSocketHandler
  +-- SessionHttpHandler
  +-- RateLimiter / ConnectionRegistry
              |
              v
       GatewaySessionFacade
        /       |        \
 MolaChat MAIN  |    Fast Team member
 MainSession    |    TeamManager / coordinator
 Application   |
 Service       +-- Starweave MAIN
                     MainSessionApplicationService

ACP / Team callback source
              |
              v
       AgentEventMultiplexer
        +-- existing MolaChat projection
        +-- existing Starweave event projection
        +-- existing Team event projection
        +-- GatewayEventProjector
                  |
                  v
          GatewayEventJournal (SQLite/WAL)
                  |
                  v
          WebSocket subscribers
```

## 6. 配置和持久化

### 6.1 建议配置

在 `acpConfig.json` 顶层增加非敏感运行配置和逻辑网关引用：

```json
{
  "agentGatewayServer": {
    "enabled": true,
    "bindHost": "127.0.0.1",
    "port": 10529,
    "publicBaseUrl": "https://agent.example.com",
    "maxConnections": 100,
    "eventRetentionDays": 7
  },
  "agentGateways": [
    {
      "id": "gw-order-assistant",
      "name": "订单智能体网关",
      "enabled": true,
      "tokenId": "tok_01J...",
      "tokenPrefix": "agw_7K2P",
      "target": {
        "type": "MOLACHAT_MAIN",
        "instanceId": "instance-a",
        "ownerId": "mola-user-1",
        "groupId": "...",
        "robotId": "acp-order-agent"
      },
      "limits": {
        "requestsPerMinute": 60,
        "maxConnections": 5,
        "maxFileCount": 10,
        "maxFileBytes": 20971520,
        "maxTotalFileBytes": 52428800
      },
      "filePolicy": {
        "allowedSchemes": ["https"],
        "allowedHosts": ["files.example.com"],
        "allowPrivateAddress": false
      }
    }
  ]
}
```

鉴权码明文不写入 `acpConfig.json`。单独的本地凭据存储仅保存：

- `tokenId`；
- 随机盐；
- 慢哈希结果；
- 可展示前缀；
- 创建、轮换和最后使用时间。

创建或轮换时只返回一次完整 token，例如 `agw_<32-byte-random-base64url>`。ConfigUI 后续只
展示前缀，并提供“轮换”操作。若首期必须沿用已有配置文件 secret 机制，也至少要权限收紧、
普通读取掩码、日志脱敏；这只能作为过渡方案。

当前首版代码采用上述过渡 secret 方案，与企微和对外任务接口共用配置保存时的掩码保留
机制；哈希凭据表和只展示一次的轮换流程保留为上线公网前的安全加固项。

### 6.2 事件和幂等存储

使用独立 SQLite，例如：

```text
${CMD_PROXY_HOME}/agent-gateway/agent-gateway.db
```

核心表：

| 表 | 用途 |
|---|---|
| `gateway_credentials` | token 哈希、前缀、轮换状态 |
| `gateway_events` | gateway/session/epoch/eventSeq、事件类型、payload、时间 |
| `gateway_requests` | gatewayId + Idempotency-Key、请求指纹、结果 |
| `gateway_audit` | 鉴权失败、连接、命令、拒绝和管理操作 |
| `gateway_resources` | 大事件 payload 和 Agent 产物的受限资源索引 |

数据库使用 WAL、busy timeout 和显式 schema version。事件按 gateway + session epoch 单调
递增。保留策略按时间和总大小清理，但不能清理仍被当前连接恢复窗口引用的数据。

## 7. 目标发现与权限边界

`AgentGatewayTargetCatalog` 汇总以下权威来源：

1. `AcpClientRegistry` 中 `scope=MAIN,surface=MOLACHAT` 的 client；
2. `StarweaveSessionManager.list()` 中 ACTIVE 的 MAIN 会话；
3. `TeamManager.snapshotDefinitions()` 和可信协调器返回的可操作 Team member。

候选项返回稳定地址、显示名、surface、owner、当前 sessionId、状态和可执行能力。以下目标不
展示：`SUB_AGENT`、`MEMORY`、`ABILITY`、`INTERNAL`、已删除会话、CLOSING/CLOSED member、
不可验证 owner 的远程目标。

Team 约束：

- 普通 Team 可固定选择任意可操作成员；
- 队长模式只返回唯一队长作为可绑定成员；
- 配置保存和每次请求均再次校验队长身份；
- mixed Team 只能经可信协调器转发，协调器不可用时 fail closed；
- 不允许随机、Hash、亲和路由，也不允许请求覆盖成员。

同一精确目标首期只允许绑定一个启用的网关，避免两个 token 将同一会话内容暴露给不同外部
系统。若后续要支持多租户观察者，必须增加明确 ACL，而不是放宽唯一性校验。

## 8. 统一会话应用层

新增 `GatewaySessionFacade`，对 transport 隐藏 MAIN、Starweave 和 Team 差异：

```java
interface GatewaySessionFacade {
    GatewaySessionSnapshot status(GatewayTarget target);
    CommandResult send(GatewayTarget target, SendCommand command);
    CommandResult cancel(GatewayTarget target, GuardedCommand command);
    CommandResult newSession(GatewayTarget target, GuardedCommand command);
}
```

实现必须复用：

- MolaChat/Starweave MAIN：`MainSessionApplicationService` 和现有 replacement initializer；
- Team member：`TeamManager.send/cancel/newSession/getStatus`；
- mixed Team：现有 `StarweaveTeamGateway`/协调器命令链。

不得从 HTTP handler 直接调用 `AcpClient.send()`，不得用 OS kill 实现取消，也不得复制会话
初始化器。新建会话必须重新安装原 surface 的 listener、memory、MCP、sub-agent、schedule、
TalkTo 等能力。

每个变更命令都携带：

- `requestId`：响应关联；
- `Idempotency-Key`：跨重试幂等；
- `expectedSessionId`；
- `expectedEpoch`；
- Team 目标额外携带 `expectedTeamVersion`（若协议支持）。

状态不匹配返回 `SESSION_CONFLICT`，不能对已经替换的新会话执行旧请求。

## 9. 事件枢纽与全量输出

### 9.1 多投影机制

新增 `AgentEventMultiplexer` 或等价的复合 listener/event sink：

- MolaChat MAIN：保留 `DefaultAcpResponseListener`，并并行调用网关结构化 listener；
- Starweave MAIN：保留现有结构化 event store/SSE，同时发布到网关；
- Team member：在既有 `TeamEventSink` 组合中加入网关 sink；
- 无网关订阅的目标不创建事件日志，降低开销。

单个投影失败不得阻塞其他投影，但网关投影持久化失败必须把网关状态改为 `DEGRADED`，关闭
其 WebSocket 并拒绝后续 send，返回 `EVENT_STORE_UNAVAILABLE`。不能继续接收新消息并静默
丢掉卡片。

### 9.2 顺序和去重

事件写入同一目标的串行 append 通道，落盘后才广播：

```text
ACP callback -> normalize -> append SQLite -> publish websocket
```

`eventSeq` 在 `gatewayId + sessionId + epoch` 范围内严格递增。传输是至少一次：断线重连可能
重复收到事件，客户端必须按 `eventId` 去重。`turnId` 用于聚合同一轮的文本和卡片，
`cardId/toolCallId` 用于原位更新卡片。

慢连接使用有界发送队列。队列满时关闭该连接并给出 `SLOW_CONSUMER`，但不影响 Agent turn，
客户端可按最后确认的 `eventSeq` 重连补拉。

### 9.3 大 payload 与资源

正常事件直接内嵌完整 payload。超过单帧上限的工具输出或产物写入
`gateway_resources`，事件返回 `contentRef`、长度、媒体类型和摘要；外部系统通过同一 Bearer
鉴权下载完整内容。这仍属于完整返回协议，不能只给截断文本且无获取路径。

本地绝对路径、Provider 凭据、鉴权码和内部 harness 不得出现在事件中。工具卡片中原本对
用户可见的 `rawInput/rawOutput/content` 必须保留；仅对明确的系统凭据字段做安全脱敏，并在
payload 标记 `redactions`。

## 10. URL 附件处理

外部消息的附件只接受 `https` URL（开发环境可显式允许 `http`）。网关在接纳 prompt 前完成：

1. 校验数量、URL 长度、协议和 host allowlist；
2. DNS 解析后拦截 loopback、link-local、私网和云 metadata 地址；
3. 每次重定向重新校验，限制重定向次数；
4. 使用独立下载池、连接/读取超时和响应体上限；
5. 校验声明大小、实际大小、可选 SHA-256；
6. 写入 gateway staging，再转换为现有 ACP files 输入；
7. prompt 成功接纳后消费 staging；失败或超时按 TTL 清理。

所有附件下载全部成功后才接纳消息，避免 Agent 收到半套文件。下载失败返回明确的附件级错误。
日志不记录带签名参数的完整 URL，只记录 host、文件名、大小和 URL 摘要。

## 11. 生命周期

### 11.1 启动

1. 读取并校验 server 配置；
2. 打开凭据和事件数据库并迁移 schema；
3. 初始化目标目录与 session facade；
4. 安装事件 multiplexer/sink；
5. 启动物理 HTTP/WebSocket server；
6. 将可解析的逻辑网关标为 `RUNNING`，目标暂不可用的标为 `TARGET_UNAVAILABLE`。

单个错误配置不应阻止 ACP 核心与其他网关启动。端口占用或数据库不可用属于物理服务错误，
所有网关进入 `ERROR`。

### 11.2 热更新

- 启停逻辑网关不重启 ACP client；
- 修改目标时先停止旧 token 接收新命令、关闭旧连接，再原子切换订阅；
- 目标修改后旧事件仍按原 target/session 保留，只读查询需带旧 sessionId；
- token 轮换立即撤销旧 token 并以 `AUTH_REVOKED` 关闭旧连接；
- bindHost/port 变化需要重启物理网关 server，但不重启 ACP 核心。

### 11.3 停止

1. 停止接纳新连接与命令；
2. 向连接发送 `server.shutdown`；
3. 在短宽限期内排空已落盘事件；
4. 关闭 WebSocket 和下载池；
5. 卸载网关订阅；
6. flush/close SQLite。

停止网关默认不取消正在运行的 Agent turn，除非管理员显式执行取消。

## 12. ConfigUI 设计

“消息渠道”页面顶部增加：

```text
[消息渠道] [智能体网关]
```

智能体网关首屏使用现有卡片、表单、按钮和分页风格。列表卡片展示名称、目标类型、目标名称、
状态、监听地址、token 前缀、当前连接数、最近使用时间和错误摘要。操作包括编辑、启停、复制
连接示例、轮换鉴权码、删除。

编辑弹窗高频字段默认展开：

- 名称；
- 是否启用；
- 目标类型和目标；
- 鉴权码创建/轮换；
- WebSocket 和 HTTP 地址。

限流、文件域名白名单、连接上限和事件保留策略放在“展开高级设置”。目标选择按
“Starweave 主会话 / Fast Team / MolaChat 会话”分组，显示 owner、状态和来源，不能仅显示
可能重名的 robotName。

窄屏改为单列卡片，所有状态和操作均保留。鉴权码只在创建/轮换成功弹窗中展示一次，并明确
提示用户立即保存。

## 13. 安全与可观测性

### 13.1 安全

- Bearer token 至少 256 bit 随机熵，恒定时间校验；
- token 不进入 URL、日志、异常、事件 payload；
- 管理 API 继续受 ConfigUI 自身访问边界保护，不能使用业务 token 修改配置；
- WebSocket handshake 和 HTTP 共用鉴权、启用状态、限流和 IP 策略；
- 默认拒绝跨站浏览器来源，可配置允许的 `Origin`；
- 单 IP 和单 token 分别限速，连续鉴权失败触发短期封禁；
- 文件下载实施 SSRF 防护；
- 事件资源下载每次重新鉴权并校验 gateway ownership；
- 队长模式、mixed Team 和跨实例权限在保存及请求时双重校验；
- 审计正文默认不落日志，只记录摘要和标识。

### 13.2 状态

物理服务状态：`STOPPED / STARTING / RUNNING / DEGRADED / ERROR`。

逻辑网关状态：`DISABLED / STARTING / RUNNING / TARGET_UNAVAILABLE / DEGRADED / ERROR`。

结构化日志至少包含：

- `gatewayId/tokenId`，不含 token；
- `targetType/targetId/surface/ownerId`；
- `connectionId/requestId/idempotencyDigest`；
- `sessionId/epoch/turnId/eventId/eventSeq`；
- `command/admission/code/latencyMillis`；
- `attachmentCount/totalBytes`；
- `errorCode/retryable`。

## 14. 错误和一致性原则

- cmd-proxy 运行时/manager 是权威状态，WebSocket 连接和 ConfigUI 只是投影；
- HTTP 2xx 或 WebSocket `command.result.accepted=true` 只表示命令被领域服务接纳，不表示 turn
  已完成；完成以 terminal event 为准；
- send/new/cancel 均要求乐观锁字段，旧请求不能影响新 session；
- 幂等范围为 `gatewayId + operation + Idempotency-Key`；相同 key、相同有效请求返回原结果，
  相同 key、不同请求返回 `IDEMPOTENCY_CONFLICT`；
- 事件至少一次传输，按 eventId 去重；
- 数据库、事件日志或目标权限不可确认时 fail closed；
- 不在 owner/manager 锁内执行 WebSocket 写、远程协调器调用或附件下载。

## 15. 建议代码结构

```text
acp/gateway/
├── AgentGatewayManager.java
├── AgentGatewayServer.java
├── AgentGatewayAdminService.java
├── AgentGatewayTargetCatalog.java
├── GatewaySessionFacade.java
├── GatewayTargetResolver.java
├── GatewayEventMultiplexer.java
├── GatewayEventProjector.java
├── GatewayEventJournal.java
├── GatewayCredentialStore.java
├── GatewayRequestDeduplicator.java
├── GatewayAttachmentDownloader.java
├── http/
│   ├── GatewayHttpHandler.java
│   └── GatewayWebSocketHandler.java
├── model/
│   ├── AgentGatewayConfig.java
│   ├── GatewayTarget.java
│   ├── GatewaySessionSnapshot.java
│   ├── GatewayEvent.java
│   └── GatewayCommandResult.java
└── security/
    ├── GatewayAuthenticator.java
    ├── GatewayRateLimiter.java
    └── GatewayUrlPolicy.java
```

ConfigUI 沿用现有整份配置保存与应用链路，并补充下列只读辅助端点：

```text
GET    /api/agent-gateways/targets
GET    /api/agent-gateways/status
GET    /api/agent-gateways/auth-code?id={gatewayId}  # 过渡 secret 方案
POST   /api/config
POST   /api/refresh
```

这些是本地管理接口，不属于对外业务 API。

## 16. 实施阶段

### 阶段一：领域边界和配置

- 定义 gateway config、target address、session facade；
- 实现统一目标目录和严格 target 校验；
- 建立凭据、事件、幂等 SQLite；
- 完成 ConfigUI 页签和管理 API。

### 阶段二：事件全量化

- 引入多投影 listener/sink；
- 对齐 MolaChat、Starweave、Team 的全部事件；
- 实现持久 eventSeq、replay、large payload resource；
- 验证原有三个 UI 投影不重复、不缺失。

### 阶段三：WebSocket 与 HTTP

- 启动共享 server；
- 实现鉴权、命令、事件、心跳、断线续传；
- 实现 HTTP 状态、send、cancel、new、events；
- 实现连接和请求限流。

### 阶段四：URL 附件和运行验收

- 实现安全下载、staging 和清理；
- 完成真实 Provider、慢消费者、重启、token 轮换、协调器离线验证；
- 补充反向代理/TLS 部署说明。

## 17. 测试与验收

至少覆盖：

1. 三种 target 的发现、稳定身份、启停和失效；
2. 队长模式只能绑定队长，伪造普通成员被拒绝；
3. mixed Team 协调器离线 fail closed；
4. token 创建只展示一次、落盘无明文、轮换立即断开旧连接；
5. 文本、多个附件、下载失败、重定向和 SSRF 拦截；
6. send 的 READY/BUSY/INTERRUPT、cancel、新 session 和 stale epoch；
7. 工具 running/completed 原位更新、连续工具卡、子 Agent、schedule、TalkTo、task、
   compaction、错误、取消和 terminal；
8. MolaChat、Starweave、Team 原 UI 与网关同时在线，均不丢失且不重复；
9. WebSocket 断线后按 afterSeq 补发，eventId 去重，session 替换后旧流被拒绝；
10. 慢消费者被单独关闭，不阻塞 Agent 和其他连接；
11. SQLite 重启续号、幂等并发、retention 和故障恢复；
12. 管理线程池、业务 HTTP 线程池、WebSocket 写队列和下载池相互隔离；
13. 日志和普通配置响应不出现 token、签名 URL、消息正文和本地路径；
14. Java 8 编译、聚焦回归、`cmd-proxy-app` 全量测试和 package。

真实运行验收必须遍历 MolaChat MAIN、Starweave MAIN、本地 Team、mixed Team 的所有业务卡片，
同时验证实时 WebSocket 与断线重放结果一致。

## 18. 主要风险

| 风险 | 控制措施 |
|---|---|
| 网关覆盖 MolaChat listener | 使用 multiplexer，多投影并存，禁止 `setGlobalListener` 替换原投影 |
| 外部新建会话影响原 UI | 明确共享会话语义、乐观锁、ConfigUI 警告、会话替换事件广播 |
| 工具卡片缺字段 | 结构化 payload + 原生 source payload + 未知事件兜底透传 |
| 慢连接拖住 ACP | 落盘与连接发送分离，有界队列，慢连接单独断开 |
| 事件日志失败仍接收消息 | 网关进入 DEGRADED 并拒绝 send，不静默丢事件 |
| URL 附件造成 SSRF | host allowlist、DNS/IP 双检、重定向重检、大小和超时限制 |
| token 泄漏 | 只展示一次、哈希存储、日志脱敏、支持即时轮换 |
| 多入口并发误操作 | expectedSessionId + epoch + Team version + 幂等键 |
| mixed Team 越权 | 仅走可信协调器，队长模式强制唯一队长，运行时重复校验 |
