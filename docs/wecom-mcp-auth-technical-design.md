# cmd-proxy 通用用户 MCP 鉴权技术方案（企微信道首个接入）

## 0. 通用身份抽象

鉴权核心不依赖企业微信，统一使用 `AuthPrincipalContext`：

```text
authSessionId -> { principalId, displayName, sourceType, sourceId, turnId, expiresAt }
```

企微信道仅负责将 `from.userid` 适配为 `principalId`。后续任何信道只需生成同一通用上下文，即可复用注册、绑定、策略和 `/check`。主会话派生出的 SubAgent、TalkTo（含 inbox 与跨实例 Fast Team）和定时任务均传播 principal 上下文；每个目标 Client 仍使用自己的 `authSessionId` 建立绑定。定时任务会持久化创建时的可选 principal，并在每次执行时重新绑定和实时判权。

## 1. 背景

cmd-proxy 已能从企业微信智能机器人消息的 `from.userid` 取得发送者 ID，并将消息投递给绑定的 ACP Agent。现在需要让 MCP Server 在工具真正执行前，按当前企微发送者判断是否有权访问该 MCP Server。

本方案采用“鉴权会话关联 + MCP Server 回查”的方式：

1. cmd-proxy 为每个 ACP Client 预生成唯一 `authSessionId`，并在初始化 MCP 时传给 MCP Server。
2. MCP Server 使用 `authSessionId` 向 cmd-proxy 注册自身信息。
3. ConfigUI 按 MCP Server 配置是否鉴权及企微用户白名单。
4. 企微 turn 执行期间，cmd-proxy 临时绑定 `authSessionId -> 当前企微 userid`。
5. MCP Server 每次执行工具前，通过鉴权接口回查是否允许。

## 2. 目标与非目标

### 2.1 目标

- 企微 `userid` 不进入 prompt，不暴露给 LLM 作为工具参数。
- 不增加 MCP HTTP 转发层，不代理实际 `tools/call` 流量。
- 多个企微用户继续复用现有 ACP Client 和 Agent session。
- 权限按 MCP Server 维度配置。
- MCP Server 可配置为不鉴权；开启鉴权后仅拦截已识别的企微用户，非企微来源继续放行。
- 同一逻辑企微 turn 的多轮 Agent 推理、Action Loop 和恢复续接使用同一个企微身份。
- 注册、绑定、判权和清理均可审计，异常情况下默认拒绝，不沿用旧身份。

### 2.2 非目标

- 本期不做 OAuth、JWT、用户登录或第三方身份提供商接入。
- 本期不做 MCP tool 维度的差异化白名单；注册工具列表仅用于展示、审计和后续扩展。
- 本期不拦截或代理 Agent 与 MCP Server 之间的 MCP 请求。
- 本期不保证不配合接入规范的 MCP Server 一定执行鉴权。鉴权由 MCP Server 在工具入口主动调用并执行判定。
- 本期不将企微身份自动委托给子 Agent、定时任务或普通聊天入口；这些没有企微 userid 的来源不参与企微白名单拦截。

## 3. 核心概念

### 3.1 authSessionId

`authSessionId` 是 cmd-proxy 生成的鉴权关联 ID，专门用于关联：

```text
ACP Client / Agent runtime
        ↕
MCP Server connection
        ↕
当前企微用户
```

它不等同于：

- ACP `sessionId`：由 Agent 在 `session/new` 后返回，生成时间太晚，不能用于初始化 MCP header。
- MCP `Mcp-Session-Id`：由 Agent 与 MCP Server 的 transport 管理，cmd-proxy 当前不可见。

约束：

- 在 `AcpClient` 创建时、发起 `session/new/session/load` 前生成。
- 使用至少 256 bit 安全随机数，采用 URL-safe Base64 编码。
- 同一 `AcpClient` 运行生命周期内保持不变；重新创建 client 时重新生成。
- 不在普通 info 日志中完整输出，只记录末尾短标识或哈希。
- 仅作为关联凭据使用，不允许由 LLM 或用户输入指定。

### 3.2 serverId 与 name

- `serverId`：MCP Server 声明的稳定唯一 ID，是权限配置和鉴权检查的主键，例如 `com.mola.order-mcp`。
- `name`：ConfigUI 展示名称，例如“订单 MCP”。允许修改，不参与权限主键判断。
- `toolName`：MCP 工具名。当前只用于注册展示和调用审计，不参与白名单规则。

同一个 `serverId` 可以被多个 `authSessionId` 注册，表示多个 Agent runtime 正在连接同一个逻辑 MCP Server。

### 3.3 当前企微身份

当前企微身份是 turn 级临时状态：

```text
authSessionId -> {
  userId,
  channelId,
  senderDisplayName,
  turnId,
  boundAt,
  expiresAt
}
```

只有来源为企微信道的逻辑 turn 才建立该绑定。普通聊天、定时任务和无授权继承的子 Agent 不建立绑定。

## 4. 总体架构

```text
企业微信
  │ from.userid
  ▼
WeComChannelAdapter
  ▼
ChannelEvent / ChannelTalkToMessage
  ▼
ChannelTurnContext(senderId, channelId, turnId)
  ▼
AcpClient(authSessionId)
  │ turn 开始：bind(authSessionId, userId)
  │ turn 结束：unbind(authSessionId, turnId)
  ▼
Agent ───────── MCP tools/call ─────────► MCP Server
                                          │
                                          │ POST /api/mcp-auth/v1/check
                                          ▼
                                     cmd-proxy
                                          │
                    server policy + active user binding
                                          │
                                          ▼
                                  allowed / denied
```

## 5. 模块设计

### 5.1 AcpClient 鉴权身份

`AcpClient` 增加只读字段：

```java
private final String authSessionId;
```

创建 client 时生成，向外只提供 getter。MCP 配置加载时将它注入各 MCP Server：

- HTTP MCP：静态 header `X-Cmd-Proxy-Auth-Session-Id`。
- stdio MCP：环境变量 `CMD_PROXY_AUTH_SESSION_ID`。
- 同时传递 cmd-proxy 鉴权接口基地址：
  - HTTP header `X-Cmd-Proxy-Auth-Base-Url`；或
  - stdio 环境变量 `CMD_PROXY_AUTH_BASE_URL`。

基地址必须使用 MCP Server 实际可访问的地址，不能由 MCP Server 根据请求 `Host` 自行猜测。

### 5.2 ChannelTurnContext 扩展

当前 `ChannelTurnContext` 只有会话回复地址；群聊时地址是 `chatId`，不能代表消息发送者。因此新增不可变字段：

```java
private final String senderId;
private final String senderDisplayName;
```

`ChannelTalkToMessage` 创建 context 时必须同时传入 `senderId`。内部 TalkTo 恢复原企微任务时继续携带原始 context，从而恢复原始发送者身份。

### 5.3 McpAuthSessionRegistry

运行时权威状态，负责：

- 注册和注销 `authSessionId`。
- 将企微用户绑定到当前逻辑 turn。
- 按 `authSessionId` 查询当前用户。
- 用 `turnId` 做 compare-and-remove，避免旧 turn 的清理误删新 turn。
- 按 `expiresAt` 清理异常残留。

建议接口：

```java
registerSession(authSessionId, clientIdentity)
bindWeComUser(authSessionId, turnContext, expiresAt)
findActivePrincipal(authSessionId)
unbind(authSessionId, turnId)
removeSession(authSessionId)
```

运行时映射不持久化。cmd-proxy 重启后不存在当前企微用户绑定，此时按无企微 userid 的非企微来源放行；新的企微 turn 建立绑定后恢复白名单判断。

### 5.4 McpServerRegistry

保存 MCP Server 注册信息：

```text
(authSessionId, serverId) -> {
  name,
  tools,
  registeredAt,
  lastSeenAt
}
```

行为：

- 注册接口幂等。
- 相同 `(authSessionId, serverId)` 再次注册时替换 name 和工具列表并刷新 `lastSeenAt`。
- 同一 `serverId` 的展示元数据可持久化，运行时连接状态不持久化。
- 注册记录超过 TTL 未刷新时标记为离线，不直接删除权限配置。

### 5.5 McpAuthPolicyStore

保存 server 维度的鉴权配置：

```json
{
  "servers": [
    {
      "serverId": "com.mola.order-mcp",
      "name": "订单 MCP",
      "authEnabled": true,
      "allowedPrincipalIds": ["zhangsan", "lisi"]
    }
  ]
}
```

规则：

- `authEnabled=false`：无需企微身份，允许访问。
- `authEnabled=true`：存在当前身份时，`principalId` 必须位于 `allowedPrincipalIds`；没有身份的请求放行。
- 开启鉴权但白名单为空：拒绝所有已识别到 userid 的企微用户，非企微来源仍放行。
- 未注册的 `serverId`：拒绝。
- 已注册但还没有显式保存配置的 server：默认 `authEnabled=false`，保持现有 MCP 行为。

配置建议独立保存在 `$CMD_PROXY_HOME/mcpAuthConfig.json`，不混入信道连接配置。

### 5.6 WeComPrincipalStore

新增企微发送者发现记录，不能复用 `knownChatTargets`：群聊中的 `knownChatTargets` 记录的是群 `chatId`，而权限白名单需要消息的 `from.userid`。

建议数据：

```json
{
  "userId": "zhangsan",
  "displayName": "张三",
  "channelIds": ["wecom-main"],
  "firstSeenAt": 1786767600000,
  "lastSeenAt": 1786768200000
}
```

每次收到合法企微消息时更新。权限规则当前按原始 `userId` 匹配；`channelId` 仅用于 UI 来源展示和审计。

### 5.7 McpAuthHttpHandler

cmd-proxy 通过现有 ConfigUI HTTP Server 暴露 MCP 接入接口：

- `POST /api/mcp-auth/v1/servers/register`
- `POST /api/mcp-auth/v1/check`

ConfigUI 使用的管理接口：

- `GET /api/mcp-auth/v1/servers`
- `GET /api/mcp-auth/v1/principals`
- `GET /api/mcp-auth/v1/policies`
- `PUT /api/mcp-auth/v1/policies/{serverId}`

MCP 接入接口应复用实例路由能力；如果 MCP Server 直接访问某个实例端口，则由该实例本地处理，不通过其他实例转发。

## 6. 关键时序

### 6.1 ACP 和 MCP 初始化

```text
cmd-proxy 创建 AcpClient
  │
  ├─ 生成 authSessionId
  ├─ 注册到 McpAuthSessionRegistry
  └─ 构造 mcpServers
       └─ header/env 注入 authSessionId + authBaseUrl

cmd-proxy ── session/new(mcpServers) ──► Agent
Agent ── initialize ──► MCP Server
MCP Server 读取 authSessionId
MCP Server ── register(authSessionId, serverId, name, tools) ──► cmd-proxy
cmd-proxy 校验 authSessionId 存在并保存注册信息
```

`session/load` 同样重新携带 `authSessionId`，MCP Server 重连后重新注册。

### 6.2 企微调用

```text
企微用户 zhangsan 发送消息
  │
  ▼
cmd-proxy 接收 ChannelEvent(senderId=zhangsan)
  │
  ├─ 更新 WeComPrincipalStore
  ├─ 消息若进入 inbox：暂不绑定
  └─ 消息真正取得 client BUSY 所有权时：
       bind(authSessionId, zhangsan, turnId)

Agent 调用 order-mcp.create_order
  │
  ▼
MCP Server 调用 /check
  │ authSessionId + serverId + toolName
  ▼
cmd-proxy：
  1. 校验 session 和 server 注册关系
  2. 读取 server policy
  3. policy 未开启鉴权 -> allowed
  4. policy 开启鉴权 -> 读取当前企微 userId
  5. 检查白名单 -> allowed/denied

逻辑 turn 完成/取消/异常
  └─ unbind(authSessionId, turnId)
```

### 6.3 Action Loop 与 TalkTo 续接

- 同一顶层 `sendInternal` 内部发生的多轮 `sendPrompt` 共享 `PromptOptions`，绑定保持到整个逻辑 turn 返回。
- 原企微 turn 委托给其他 Agent 后，如果后续内部回信唯一恢复到该企微 context，新 turn 再按 context 中的原始 `senderId` 绑定。
- 子 Agent 自己执行 MCP 时默认没有企微绑定，因此按非企微来源放行；本期不隐式继承企微身份。

## 7. 判权规则

判权顺序固定如下：

1. `authSessionId` 是否为当前 cmd-proxy 实例创建的有效 session。
2. `(authSessionId, serverId)` 是否完成注册。
3. server policy 是否存在；不存在按未开启鉴权处理。
4. `authEnabled` 是否为 `false`；若是则允许。
5. 当前是否绑定未过期的企微用户；若否则按非企微来源放行。
6. 当前 `userId` 是否位于 server 白名单；若是允许，否则拒绝。

判权结果码：

| code | allowed | 说明 |
|---|---:|---|
| `AUTH_DISABLED` | true | Server 未开启鉴权 |
| `NO_PRINCIPAL_ALLOWED` | true | 当前没有用户身份，不参与用户白名单拦截 |
| `PRINCIPAL_ALLOWED` | true | 当前用户在白名单 |
| `AUTH_SESSION_NOT_FOUND` | false | authSessionId 无效或已销毁 |
| `SERVER_NOT_REGISTERED` | false | 当前 session 未注册该 server |
| `PRINCIPAL_NOT_ALLOWED` | false | 当前用户不在白名单 |

## 8. ConfigUI 设计

新增一级页面或页签“MCP 权限”，包含两部分。

### 8.1 MCP Server 列表

每个 server 展示：

- name 与 serverId。
- 在线/离线状态、最近注册时间、连接的 auth session 数量。
- 注册的工具列表。
- “启用企微鉴权”开关。
- 企微 userid 白名单。

白名单采用输入框加标签交互：

- 从已发现企微用户中搜索选择。
- 标签显示昵称与 userid。
- 支持输入 userid 后回车添加。
- 支持一次粘贴逗号或换行分隔的多个 ID。
- 自动去重，标签可单独删除。
- 后端保存为通用 `allowedPrincipalIds` 数组。

开关打开且白名单为空时，页面明确提示“当前将拒绝所有企微用户”。

### 8.2 已发现企微用户

展示：

- userid。
- 最近昵称。
- 来源信道。
- 首次发现和最后活跃时间。
- 已被哪些 MCP Server 授权。

保存策略配置后立即热生效，不重启 ACP、不刷新信道、不重连 MCP。

## 9. 并发与生命周期要求

### 9.1 绑定时机

- 企微消息仅进入 inbox 时不得提前绑定。
- 只有消息真正将目标 `AcpClient` 从 `READY` 切换为 `BUSY` 后才能绑定。
- 普通消息开始前主动确认不存在残留企微绑定。

### 9.2 清理时机

以下路径必须清理，并使用 `turnId` 条件删除：

- 正常完成。
- Agent 返回错误。
- `session/cancel`。
- executor 拒绝任务。
- ACP 进程退出。
- client close/reload。
- prompt 超时。

清理操作必须幂等。旧 turn 的 finally 不得删除新 turn 的绑定。

### 9.3 过期保护

- 绑定必须设置 `expiresAt`。
- `/check` 在读取时同步判断过期，过期视为没有当前企微用户。
- 后台任务只负责回收内存，不作为权限正确性的唯一保障。
- 建议初始 TTL 与最大逻辑 turn 超时一致，并在合法 follow-up round 开始时刷新。

## 10. 安全边界

- 本方案是合作式鉴权：MCP Server 必须在每个受保护工具入口调用 `/check`，cmd-proxy 不代理流量，因此无法阻止恶意 MCP Server 跳过检查。
- `authSessionId` 视为敏感关联凭据，不放入工具描述、tool result、LLM prompt 或完整日志。
- cmd-proxy 鉴权接口如果跨主机访问，应使用 HTTPS 或可信内网；公开网络部署需额外增加服务级认证。
- MCP Server 不得接受工具参数中的 userid 替代 `/check` 返回结果。
- 鉴权接口不可用、超时或响应无法解析时，MCP Server 必须拒绝受保护工具执行。
- `/check` 不返回完整白名单，避免暴露其他用户 ID。

## 11. Provider 与 transport 边界

- 由 cmd-proxy 通过 ACP `mcpServers` 注入的 HTTP/stdio MCP，可以统一加入 header/env。
- 如果 Agent Provider 自己加载 MCP 配置，必须在其原生配置中加入同名 header/env，或由 provider 实现配置转换；无法注入时标记为“不支持 MCP 企微鉴权关联”。
- HTTP MCP Server 需要能读取 initialize 请求的静态 header。
- stdio MCP Server 从启动环境变量读取。
- MCP Server 可能同时服务多个连接，不能用进程级单变量保存唯一 authSessionId；HTTP 场景应按 MCP connection/session 保存关联。

## 12. 持久化与权威状态

| 数据 | 是否持久化 | 权威所有者 |
|---|---:|---|
| authSessionId 有效 session | 否 | `McpAuthSessionRegistry` |
| 当前 authSessionId -> 企微用户 | 否 | `McpAuthSessionRegistry` |
| MCP Server 在线注册 | 否 | `McpServerRegistry` |
| MCP Server 最近发现信息 | 是 | `McpAuthPolicyStore` |
| Server 鉴权开关与白名单 | 是 | `McpAuthPolicyStore` |
| 已发现企微 userid | 是 | `WeComPrincipalStore` |

ConfigUI 展示是权威状态的投影，不自行维护第二份运行时绑定。

## 13. 实施拆分

### P0：核心闭环

1. `AcpClient` 生成 `authSessionId`。
2. `McpConfigLoader` 支持为 MCP 配置追加鉴权 header/env。
3. `ChannelTurnContext` 保存 senderId。
4. 实现 session registry、server registry、policy store 和 principal store。
5. 实现注册与判权 HTTP 接口。
6. 在企微逻辑 turn 生命周期绑定和清理身份。
7. ConfigUI 增加 MCP 权限页面。

### P1：可观测性与增强

1. 工具调用鉴权审计页。
2. 注册心跳和离线状态优化。
3. tool 维度策略。
4. 显式的子 Agent 权限委托。
5. 跨主机接口认证。

## 14. 测试清单

- authSessionId 在 `session/new` 前已生成并注入 HTTP header/stdio env。
- 相同 server 重复注册幂等更新工具列表。
- 未知 authSessionId 注册失败。
- server 未开启鉴权时，无企微绑定也允许。
- server 开启鉴权且白名单为空时，已识别的企微用户全部拒绝，非企微来源放行。
- 白名单用户允许，非白名单用户拒绝。
- 普通聊天、MolaChat、定时任务和默认子 Agent 没有企微 userid 时放行。
- A 用户 BUSY、B 用户排队期间始终按 A 判权；B 真正执行后切换为 B。
- Action Loop 多轮 prompt 不提前清理。
- 正常、异常、cancel、reload、close 均清理绑定。
- 旧 turn 清理不会删除新 turn 绑定。
- 过期绑定即使后台尚未回收也不再作为企微身份使用，按无企微 userid 放行。
- ConfigUI 标签输入支持选择、手工输入、批量粘贴、去重和删除。
- 策略保存热生效，不触发 ACP 或信道刷新。
