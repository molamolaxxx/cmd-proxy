# MCP Server 接入 cmd-proxy 企微鉴权接口文档

## 1. 文档用途

本文面向需要接入 cmd-proxy 企微用户鉴权的 MCP Server 开发者。

接入后，MCP Server 在工具执行前向 cmd-proxy 查询：当前调用对应的 Agent 是否正在处理企微信息，以及该企微 `userid` 是否位于当前 MCP Server 的白名单。

企微 userid 不由 LLM 或工具参数提供。MCP Server 通过初始化阶段取得的 `authSessionId` 查询 cmd-proxy。

## 2. 接入流程

```text
1. MCP Server 启动或建立 MCP connection
2. 读取 cmd-proxy 注入的 authSessionId 和 authBaseUrl
3. 准备稳定 serverId、展示 name 和工具列表
4. 调用注册接口
5. 每次工具 handler 执行前调用鉴权接口
6. allowed=true 才继续执行业务逻辑
7. 接口异常或 allowed=false 时返回 MCP tool error
```

## 3. 初始化参数

### 3.1 HTTP transport

cmd-proxy 在 MCP Server 配置中注入以下静态 HTTP header：

| Header | 必填 | 说明 |
|---|---:|---|
| `X-Cmd-Proxy-Auth-Session-Id` | 是 | 当前 Agent runtime 的鉴权关联 ID |
| `X-Cmd-Proxy-Auth-Base-Url` | 是 | cmd-proxy 鉴权接口基地址 |

示例：

```http
POST /mcp HTTP/1.1
Host: mcp.example.internal
X-Cmd-Proxy-Auth-Session-Id: xM8...url-safe-random...9Q
X-Cmd-Proxy-Auth-Base-Url: http://127.0.0.1:10528
Content-Type: application/json

{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
```

MCP Server 应在 connection/session 上保存这两个值。一个 MCP Server 进程可能同时接收多个 Agent connection，不得把最后一次收到的 authSessionId 存成全局唯一值。

### 3.2 stdio transport

cmd-proxy 通过环境变量注入：

| 环境变量 | 必填 | 说明 |
|---|---:|---|
| `CMD_PROXY_AUTH_SESSION_ID` | 是 | 当前 Agent runtime 的鉴权关联 ID |
| `CMD_PROXY_AUTH_BASE_URL` | 是 | cmd-proxy 鉴权接口基地址 |

stdio MCP Server 每个子进程对应一组环境变量，可在进程启动时读取并保存。

### 3.3 authSessionId 使用约束

- 不要输出到工具描述、工具结果或面向 LLM 的日志。
- 不要把它当作企微 userid；它只是查询当前企微身份的关联 ID。
- 不要允许工具调用参数覆盖它。
- MCP connection 重建后重新读取并重新注册。

## 4. Server 身份要求

MCP Server 必须定义稳定的 `serverId`：

```text
com.mola.order-mcp
```

建议使用反向域名或组织前缀，满足：

```text
^[A-Za-z0-9][A-Za-z0-9._-]{2,127}$
```

`serverId` 一旦用于权限配置，不应随版本或展示名称变化。以下字段含义不同：

| 字段 | 示例 | 用途 |
|---|---|---|
| `serverId` | `com.mola.order-mcp` | 稳定权限主键 |
| `name` | `订单 MCP` | ConfigUI 展示 |
| `tool.name` | `create_order` | 工具展示和审计 |

## 5. 注册接口

### 5.1 请求

```http
POST {authBaseUrl}/api/mcp-auth/v1/servers/register
Content-Type: application/json
```

请求体：

```json
{
  "authSessionId": "xM8...url-safe-random...9Q",
  "serverId": "com.mola.order-mcp",
  "name": "订单 MCP",
  "protocolVersion": "2025-06-18",
  "tools": [
    {
      "name": "query_order",
      "title": "查询订单",
      "description": "按订单号查询订单"
    },
    {
      "name": "create_order",
      "title": "创建订单",
      "description": "创建一笔新订单"
    }
  ]
}
```

字段说明：

| 字段 | 类型 | 必填 | 约束 |
|---|---|---:|---|
| `authSessionId` | string | 是 | 从 header/env 读取，不能由工具参数取得 |
| `serverId` | string | 是 | 3～128 字符，稳定唯一 |
| `name` | string | 是 | 1～128 字符，展示名称 |
| `protocolVersion` | string | 否 | 当前 MCP 协议版本 |
| `tools` | array | 是 | 可以为空；每次注册按全量列表替换 |
| `tools[].name` | string | 是 | MCP tool name |
| `tools[].title` | string | 否 | 工具展示标题 |
| `tools[].description` | string | 否 | 最长 1000 字符，超长可截断 |

### 5.2 成功响应

接口幂等。首次注册和重复注册均返回 `200 OK`：

```json
{
  "success": true,
  "serverId": "com.mola.order-mcp",
  "authEnabled": true,
  "registeredAt": 1786767600000,
  "serverTime": 1786767600100
}
```

### 5.3 失败响应

#### 参数错误：400

```json
{
  "success": false,
  "code": "INVALID_REQUEST",
  "message": "serverId must not be blank"
}
```

#### authSessionId 不存在：404

```json
{
  "success": false,
  "code": "AUTH_SESSION_NOT_FOUND",
  "message": "auth session is not active"
}
```

#### 服务异常：500

```json
{
  "success": false,
  "code": "INTERNAL_ERROR",
  "message": "registration failed"
}
```

### 5.4 注册时机

推荐在以下时机注册：

- MCP `initialize` 完成且工具列表就绪后。
- MCP connection 重建后。
- 工具列表发生变化后。

重复注册会刷新在线时间并全量替换该 connection 对应的工具列表。

## 6. 鉴权检查接口

### 6.1 请求

MCP Server 必须在工具业务逻辑执行前调用：

```http
POST {authBaseUrl}/api/mcp-auth/v1/check
Content-Type: application/json
```

请求体：

```json
{
  "authSessionId": "xM8...url-safe-random...9Q",
  "serverId": "com.mola.order-mcp",
  "toolName": "create_order",
  "requestId": "mcp-jsonrpc-request-42"
}
```

字段说明：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| `authSessionId` | string | 是 | 初始化阶段取得的关联 ID |
| `serverId` | string | 是 | 必须与注册时一致 |
| `toolName` | string | 否 | 当前执行的工具名；当前用于审计 |
| `requestId` | string | 否 | MCP Server 自己的调用追踪 ID，最长 128 字符 |

### 6.2 允许响应

#### Server 未开启鉴权

```json
{
  "success": true,
  "allowed": true,
  "code": "AUTH_DISABLED",
  "serverId": "com.mola.order-mcp",
  "userId": null,
  "serverTime": 1786767610000
}
```

#### 当前企微用户在白名单

```json
{
  "success": true,
  "allowed": true,
  "code": "WECOM_USER_ALLOWED",
  "serverId": "com.mola.order-mcp",
  "userId": "zhangsan",
  "channelId": "wecom-main",
  "turnId": "4fe84b86-90e4-4e9d-a8c5-829c0abc18ee",
  "expiresAt": 1786768200000,
  "serverTime": 1786767610000
}
```

MCP Server 可以使用返回的 `userId` 记录业务审计，但不得用请求参数中的 userid 替代它。

### 6.3 拒绝响应

权限拒绝统一返回 `200 OK`，由 `allowed=false` 表示。这能让 MCP middleware 对所有业务拒绝使用同一种解析路径。

#### 当前没有企微用户

```json
{
  "success": true,
  "allowed": false,
  "code": "NO_ACTIVE_WECOM_USER",
  "serverId": "com.mola.order-mcp",
  "userId": null,
  "message": "当前调用没有关联的企微用户",
  "serverTime": 1786767610000
}
```

#### 用户不在白名单

```json
{
  "success": true,
  "allowed": false,
  "code": "WECOM_USER_NOT_ALLOWED",
  "serverId": "com.mola.order-mcp",
  "userId": "wangwu",
  "message": "当前企微用户无权访问该 MCP Server",
  "serverTime": 1786767610000
}
```

#### Session 或 Server 无效

```json
{
  "success": true,
  "allowed": false,
  "code": "SERVER_NOT_REGISTERED",
  "serverId": "com.mola.order-mcp",
  "userId": null,
  "message": "MCP Server 尚未完成注册",
  "serverTime": 1786767610000
}
```

可能的判定码：

| code | allowed | MCP Server 行为 |
|---|---:|---|
| `AUTH_DISABLED` | true | 继续执行 |
| `WECOM_USER_ALLOWED` | true | 继续执行，可记录 userId |
| `AUTH_SESSION_NOT_FOUND` | false | 拒绝并提示鉴权 session 已失效 |
| `SERVER_NOT_REGISTERED` | false | 拒绝并尝试重新注册；本次调用不自动放行 |
| `NO_ACTIVE_WECOM_USER` | false | 拒绝，当前调用不是有效企微 turn |
| `WECOM_USER_NOT_ALLOWED` | false | 拒绝，用户不在白名单 |

### 6.4 协议错误

- 请求 JSON 非法或缺少必填字段：`400 INVALID_REQUEST`。
- cmd-proxy 内部异常：`500 INTERNAL_ERROR`。
- 网络错误、超时、非 2xx、响应无法解析：按拒绝处理。

## 7. MCP 工具入口处理规范

每个工具执行前必须遵循：

```text
handleToolCall(toolName, arguments):
    decision = check(authSessionId, serverId, toolName, requestId)

    if decision request failed:
        return MCP tool error("鉴权服务不可用，已拒绝执行")

    if decision.allowed != true:
        return MCP tool error(decision.message or "无权访问该 MCP Server")

    auditUserId = decision.userId
    return executeBusinessTool(arguments, auditUserId)
```

推荐统一做成 MCP Server middleware，避免某个工具忘记调用鉴权接口。

拒绝时建议返回标准 MCP tool result：

```json
{
  "content": [
    {
      "type": "text",
      "text": "当前用户无权访问该 MCP Server"
    }
  ],
  "isError": true
}
```

不要把以下内容返回给 LLM：

- authSessionId。
- 白名单完整内容。
- cmd-proxy 内部异常堆栈。
- 其他企微用户 ID。

## 8. 超时、重试与缓存

### 8.1 超时

- 建议连接超时：500 ms。
- 建议请求总超时：1000 ms。
- 超时后拒绝工具执行。

### 8.2 重试

- 注册接口可以对网络错误做有限重试，例如最多 2 次。
- 鉴权检查默认不重试，或只对明确的瞬时连接错误快速重试 1 次。
- `allowed=false` 不得重试。

### 8.3 缓存

- 不得跨工具调用缓存 `allowed=true`，因为当前企微用户和 ConfigUI 白名单都可能变化。
- 可以缓存 authBaseUrl、authSessionId、serverId 等静态初始化数据。
- 工具列表变化后重新注册。

## 9. 并发要求

- HTTP MCP Server 必须按 MCP connection/session 取得对应 authSessionId。
- 多个连接并发时，每次 `/check` 使用发起该工具调用的 connection 所属 authSessionId。
- 不得使用进程级“最后一次 authSessionId”。
- 同一工具调用只使用一次鉴权结果，不在业务执行中途切换 userId。

## 10. 日志与审计

MCP Server 建议记录：

```text
serverId
toolName
requestId
allowed
decisionCode
userId（仅在业务允许记录时）
耗时
```

authSessionId 只记录哈希或末尾 6～8 位，不记录完整值。

示例：

```text
mcp auth check: serverId=com.mola.order-mcp, tool=create_order,
session=***7a91fd, userId=zhangsan, allowed=true,
code=WECOM_USER_ALLOWED, elapsedMs=12
```

## 11. 接入验收清单

- MCP Server 能从 HTTP header 或 stdio env 读取 authSessionId 和 authBaseUrl。
- `serverId` 稳定且不会随展示名称或版本变化。
- initialize 后能完成幂等注册并上传完整工具列表。
- 每个工具入口都经过统一鉴权 middleware。
- `allowed=false` 时不会进入业务 handler。
- 鉴权服务超时或返回非法响应时默认拒绝。
- HTTP 多 connection 场景不会串 authSessionId。
- MCP tool 参数中的 userid 不参与权限判断。
- 日志不输出完整 authSessionId 和白名单。
- ConfigUI 关闭鉴权时工具可正常执行。
- ConfigUI 开启鉴权后，白名单用户允许，非白名单用户拒绝。
- 普通 Agent 对话没有企微用户绑定时，受保护 Server 拒绝。

## 12. 版本兼容

接口前缀固定为：

```text
/api/mcp-auth/v1
```

兼容原则：

- v1 内可以增加可选响应字段。
- 不删除或改变既有字段语义。
- 新增必填请求字段或改变判权语义时升级到 v2。
- MCP Server 应忽略未知响应字段。
