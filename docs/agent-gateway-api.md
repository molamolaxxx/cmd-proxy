# 智能体网关对外接口文档（V1 草案）

## 1. 概述

智能体网关提供 WebSocket 实时双向协议和 HTTP 基础接口。每个鉴权码固定绑定一个网关配置
和一个智能体目标，调用方不能在请求中切换目标。

WebSocket 用于持续发送消息和接收 Agent 的完整事件流；HTTP 用于发送单次消息、查询状态、
取消、新建会话以及断线补拉事件。两种协议共用鉴权、幂等、会话并发保护、事件模型和错误码。

示例地址：

```text
WebSocket: wss://agent.example.com/api/agent-gateway/v1/ws
HTTP:     https://agent.example.com/api/agent-gateway/v1
```

生产环境必须使用 TLS。以下示例省略与业务无关的响应头。

## 2. 鉴权

所有 HTTP 请求和 WebSocket 握手都使用 Bearer token：

```http
Authorization: Bearer agw_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

token 由管理员在 ConfigUI 创建或轮换，只展示一次。token 直接定位 gateway，业务请求不需要
也不允许提交 `gatewayId`。禁止把 token 放在 query string 中。

常见鉴权响应：

| HTTP | code | 含义 |
|---|---|---|
| 401 | `INVALID_TOKEN` | token 缺失、格式错误、未知或已轮换 |
| 403 | `GATEWAY_DISABLED` | token 存在，但网关已停用 |
| 403 | `SOURCE_NOT_ALLOWED` | IP 或 Origin 不符合配置 |
| 429 | `RATE_LIMITED` | token 或来源超过限额 |

## 3. 通用 HTTP 约定

### 3.1 请求头

```http
Authorization: Bearer <token>
Content-Type: application/json
X-Request-Id: <optional-caller-request-id>
Idempotency-Key: <required-for-mutating-requests>
```

`Idempotency-Key` 长度为 1–128 个可打印 ASCII 字符。幂等范围为：

```text
gatewayId + operation + Idempotency-Key
```

相同 key 和相同有效请求返回首次结果；相同 key 对应不同有效请求返回 HTTP 409。

### 3.2 成功响应 envelope

```json
{
  "schemaVersion": "1.0",
  "requestId": "req_01K...",
  "accepted": true,
  "code": "OK",
  "message": "Request accepted",
  "timestamp": 1788852600000,
  "data": {}
}
```

`accepted=true` 只表示命令已被网关和底层会话服务接纳，不表示 Agent turn 已完成。turn 完成
以 `turn.completed`、`turn.error` 或 `turn.cancelled` 事件为准。

### 3.3 错误响应 envelope

```json
{
  "schemaVersion": "1.0",
  "requestId": "req_01K...",
  "accepted": false,
  "code": "SESSION_CONFLICT",
  "message": "The current session has changed",
  "timestamp": 1788852600000,
  "retryable": false,
  "details": {
    "currentSessionId": "sess_current",
    "currentEpoch": 9
  }
}
```

## 4. 会话标识和并发保护

状态响应包含：

- `sessionId`：Provider 当前 session 标识；
- `epoch`：网关观察到的会话槽位代数，每次 new/restore/replacement 都递增；
- `state`：`STARTING / READY / BUSY / ERROR / CLOSED`；
- Team 目标可额外返回 `teamVersion`。

所有修改会话的请求必须回传 `expectedSessionId` 和 `expectedEpoch`。这样可避免旧页面、重试或
另一个连接误操作已经替换的新 session。

## 5. 消息和附件模型

### 5.1 发送内容

```json
{
  "expectedSessionId": "sess_123",
  "expectedEpoch": 8,
  "message": "请分析附件中的错误日志，并给出修复建议。",
  "attachments": [
    {
      "url": "https://files.example.com/build/error.log?signature=...",
      "name": "error.log",
      "mediaType": "text/plain",
      "size": 18342,
      "sha256": "6e6b...optional-lowercase-hex..."
    }
  ],
  "busyPolicy": "REJECT",
  "metadata": {
    "externalConversationId": "order-20260908-001",
    "senderId": "user-42"
  }
}
```

字段约束：

| 字段 | 必填 | 说明 |
|---|---|---|
| `expectedSessionId` | 是 | 必须等于当前 sessionId |
| `expectedEpoch` | 是 | 必须等于当前 epoch |
| `message` | 条件 | 与 attachments 至少有一个非空；最大 1 MiB |
| `attachments` | 否 | 默认最多 10 个，实际限制由网关配置决定 |
| `attachments[].url` | 是 | URL 形式文件；默认只允许 HTTPS 和配置的 host |
| `attachments[].name` | 是 | 展示和传给 ACP 的文件名，不得含路径分隔符 |
| `mediaType` | 否 | 声明类型，服务端可按响应重新判定 |
| `size` | 否 | 预期字节数；与实际不符时请求失败 |
| `sha256` | 否 | 提供时服务端必须校验 |
| `busyPolicy` | 否 | `REJECT`（默认）或 `INTERRUPT` |
| `metadata` | 否 | 外部关联字段；总大小不超过 8 KiB，不注入系统指令 |

附件由 cmd-proxy 下载并进行 SSRF、重定向、大小、超时和摘要校验。任何一个附件失败时整条
消息不被接纳。

## 6. HTTP API

### 6.1 查询当前会话状态

```http
GET /api/agent-gateway/v1/session
Authorization: Bearer <token>
```

成功响应：

```json
{
  "schemaVersion": "1.0",
  "requestId": "req_01K...",
  "accepted": true,
  "code": "OK",
  "message": "Session available",
  "timestamp": 1788852600000,
  "data": {
    "gateway": {
      "id": "gw-order-assistant",
      "name": "订单智能体网关",
      "status": "RUNNING"
    },
    "target": {
      "type": "MOLACHAT_MAIN",
      "displayName": "订单智能体",
      "surface": "MOLACHAT",
      "capabilities": ["SEND", "CANCEL", "NEW_SESSION", "EVENT_REPLAY"]
    },
    "session": {
      "sessionId": "sess_123",
      "epoch": 8,
      "state": "READY",
      "contextUsagePercentage": 21.4,
      "currentTurnId": null,
      "lastEventSeq": 237,
      "updatedAt": 1788852580123
    }
  }
}
```

目标暂不可用时返回 HTTP 503 和 `TARGET_UNAVAILABLE`，`details` 可返回已脱敏的目标状态，
不得泄露内部 groupId 或本地路径。

### 6.2 发送消息

```http
POST /api/agent-gateway/v1/messages
Authorization: Bearer <token>
Idempotency-Key: msg-order-20260908-001-1
Content-Type: application/json
```

请求体使用第 5 节模型。

成功响应：

```json
{
  "schemaVersion": "1.0",
  "requestId": "req_01K...",
  "accepted": true,
  "code": "PROMPT_ACCEPTED",
  "message": "Message accepted",
  "timestamp": 1788852600000,
  "data": {
    "sessionId": "sess_123",
    "epoch": 8,
    "turnId": "turn_01K...",
    "admission": "STARTED",
    "firstEventSeq": 238
  }
}
```

`busyPolicy=REJECT` 且会话正忙时返回 HTTP 409 / `SESSION_BUSY`。`INTERRUPT` 被接纳时表示先
请求中断当前 turn，再启动新 turn；旧 turn 和新 turn 使用不同 `turnId`，客户端必须等待
对应 terminal event，不能按到达时间猜测归属。

### 6.3 取消当前 turn

```http
POST /api/agent-gateway/v1/session/cancel
Authorization: Bearer <token>
Idempotency-Key: cancel-order-20260908-001
Content-Type: application/json
```

```json
{
  "expectedSessionId": "sess_123",
  "expectedEpoch": 8,
  "expectedTurnId": "turn_01K..."
}
```

成功响应：

```json
{
  "schemaVersion": "1.0",
  "requestId": "req_01K...",
  "accepted": true,
  "code": "CANCEL_REQUESTED",
  "message": "Cancellation requested",
  "timestamp": 1788852630000,
  "data": {
    "sessionId": "sess_123",
    "epoch": 8,
    "turnId": "turn_01K..."
  }
}
```

取消是异步请求。最终结果由 `turn.cancelled`、`turn.completed` 或 `turn.error` 给出。如果 turn
已结束，重复相同幂等请求返回首次结果；不同请求取消不存在的 turn 返回
`TURN_NOT_RUNNING`。

### 6.4 新建会话

```http
POST /api/agent-gateway/v1/session/new
Authorization: Bearer <token>
Idempotency-Key: new-session-order-20260908-001
Content-Type: application/json
```

```json
{
  "expectedSessionId": "sess_123",
  "expectedEpoch": 8
}
```

成功响应：

```json
{
  "schemaVersion": "1.0",
  "requestId": "req_01K...",
  "accepted": true,
  "code": "SESSION_CREATED",
  "message": "New session created",
  "timestamp": 1788852700000,
  "data": {
    "previousSessionId": "sess_123",
    "sessionId": "sess_456",
    "epoch": 9,
    "state": "READY"
  }
}
```

只有 `READY` 会话允许直接新建。`BUSY` 时先显式 cancel 并等待 terminal event；网关不隐式
丢弃正在运行的 turn。新建成功会同时产生 `session.replaced` 和 `session.state.changed` 事件。

### 6.5 补拉事件

```http
GET /api/agent-gateway/v1/events?sessionId=sess_123&epoch=8&afterSeq=220&limit=200
Authorization: Bearer <token>
```

成功响应：

```json
{
  "schemaVersion": "1.0",
  "requestId": "req_01K...",
  "accepted": true,
  "code": "OK",
  "message": "Events returned",
  "timestamp": 1788852750000,
  "data": {
    "sessionId": "sess_123",
    "epoch": 8,
    "afterSeq": 220,
    "lastSeq": 237,
    "hasMore": false,
    "events": []
  }
}
```

`limit` 默认 200，最大 1000。若 `afterSeq` 已早于保留窗口，返回 HTTP 410 和
`RESYNC_REQUIRED`，同时给出 `earliestSeq` 和当前 session snapshot。

### 6.6 获取大事件 payload 或 Agent 资源（预留）

```http
GET /api/agent-gateway/v1/resources/res_01K...
Authorization: Bearer <token>
```

该端点是后续大 payload 外置时的兼容预留，本次 V1 实现尚不开放；当前事件 payload 与
`source.nativePayload` 均完整内嵌在 WebSocket/HTTP 事件中。开放后，服务端必须校验资源属于
当前 token 对应 gateway，并返回正确 `Content-Type`、
`Content-Disposition`、`Content-Length`、`X-Content-Type-Options: nosniff`。资源 ID 是不透明
随机值，拒绝文件路径、`..` 和跨 gateway 猜测。资源过期返回 HTTP 410 / `RESOURCE_EXPIRED`。

## 7. WebSocket 协议

### 7.1 握手

```http
GET /api/agent-gateway/v1/ws HTTP/1.1
Host: agent.example.com
Upgrade: websocket
Connection: Upgrade
Authorization: Bearer <token>
Sec-WebSocket-Version: 13
Sec-WebSocket-Protocol: cmd-proxy.agent-gateway.v1
```

服务端接受后选择子协议 `cmd-proxy.agent-gateway.v1`。每条文本帧是一个完整 JSON object，
UTF-8 编码。客户端和服务端都必须忽略 envelope 中未知的可选字段，但不能忽略未知命令类型。

### 7.2 通用 frame envelope

```json
{
  "schemaVersion": "1.0",
  "frameType": "command|command.result|event|control|error",
  "requestId": "req_01K...",
  "timestamp": 1788852600000,
  "type": "message.send",
  "payload": {}
}
```

客户端发送 `command` 或 `control`；服务端发送 `command.result`、`event`、`control` 或
`error`。同一 requestId 只对应一个 command result，业务过程通过后续 event 返回。

### 7.3 建连 hello

握手成功后服务端第一帧：

```json
{
  "schemaVersion": "1.0",
  "frameType": "control",
  "requestId": null,
  "timestamp": 1788852600000,
  "type": "server.hello",
  "payload": {
    "connectionId": "conn_01K...",
    "heartbeatIntervalSeconds": 20,
    "maxFrameBytes": 4194304,
    "gateway": {
      "name": "订单智能体网关"
    },
    "session": {
      "sessionId": "sess_123",
      "epoch": 8,
      "state": "READY",
      "lastEventSeq": 237
    }
  }
}
```

### 7.4 恢复事件流

客户端收到 hello 后应发送：

```json
{
  "schemaVersion": "1.0",
  "frameType": "control",
  "requestId": "req_resume_1",
  "timestamp": 1788852600100,
  "type": "stream.resume",
  "payload": {
    "sessionId": "sess_123",
    "epoch": 8,
    "afterSeq": 220
  }
}
```

服务端先建立 live 高水位屏障，再按 eventSeq 回放 `(220, highWatermark]`，最后无缝切换到
live，保证回放与实时事件之间无空洞。成功控制帧：

```json
{
  "schemaVersion": "1.0",
  "frameType": "control",
  "requestId": "req_resume_1",
  "timestamp": 1788852600200,
  "type": "stream.resumed",
  "payload": {
    "sessionId": "sess_123",
    "epoch": 8,
    "fromExclusive": 220,
    "highWatermark": 237
  }
}
```

当前 session 与客户端保存的不同时，服务端返回 `SESSION_CONFLICT`，同时发送
`session.snapshot`；客户端应明确决定展示旧 session 历史还是切换到新 session，不能把两个
epoch 的事件合并到同一轮。

### 7.5 发送消息命令

```json
{
  "schemaVersion": "1.0",
  "frameType": "command",
  "requestId": "req_send_1",
  "timestamp": 1788852610000,
  "type": "message.send",
  "payload": {
    "idempotencyKey": "msg-order-20260908-001-1",
    "expectedSessionId": "sess_123",
    "expectedEpoch": 8,
    "message": "请分析附件。",
    "attachments": [
      {
        "url": "https://files.example.com/error.log?signature=...",
        "name": "error.log",
        "mediaType": "text/plain"
      }
    ],
    "busyPolicy": "REJECT",
    "metadata": {
      "externalConversationId": "order-20260908-001"
    }
  }
}
```

服务端返回：

```json
{
  "schemaVersion": "1.0",
  "frameType": "command.result",
  "requestId": "req_send_1",
  "timestamp": 1788852610500,
  "type": "message.send.result",
  "payload": {
    "accepted": true,
    "code": "PROMPT_ACCEPTED",
    "message": "Message accepted",
    "data": {
      "sessionId": "sess_123",
      "epoch": 8,
      "turnId": "turn_01K...",
      "admission": "STARTED"
    }
  }
}
```

### 7.6 状态、取消和新会话命令

状态：

```json
{
  "schemaVersion": "1.0",
  "frameType": "command",
  "requestId": "req_status_1",
  "timestamp": 1788852620000,
  "type": "session.get",
  "payload": {}
}
```

取消：

```json
{
  "schemaVersion": "1.0",
  "frameType": "command",
  "requestId": "req_cancel_1",
  "timestamp": 1788852630000,
  "type": "session.cancel",
  "payload": {
    "idempotencyKey": "cancel-order-20260908-001",
    "expectedSessionId": "sess_123",
    "expectedEpoch": 8,
    "expectedTurnId": "turn_01K..."
  }
}
```

新会话：

```json
{
  "schemaVersion": "1.0",
  "frameType": "command",
  "requestId": "req_new_1",
  "timestamp": 1788852700000,
  "type": "session.new",
  "payload": {
    "idempotencyKey": "new-session-order-20260908-001",
    "expectedSessionId": "sess_123",
    "expectedEpoch": 8
  }
}
```

三者的 `command.result.payload` 与对应 HTTP envelope 的 `accepted/code/message/data` 一致。

### 7.7 心跳和确认

服务端每 20 秒发送 WebSocket ping control frame，客户端应返回 pong。连续两个周期未收到
pong，服务端以 `HEARTBEAT_TIMEOUT` 关闭连接。

客户端可发送应用层确认：

```json
{
  "schemaVersion": "1.0",
  "frameType": "control",
  "requestId": null,
  "timestamp": 1788852800000,
  "type": "stream.ack",
  "payload": {
    "sessionId": "sess_123",
    "epoch": 8,
    "eventSeq": 237
  }
}
```

ack 用于观测和恢复游标，不把传输改成 exactly-once。客户端仍必须按 `eventId` 去重。

## 8. 统一事件格式

### 8.1 Event envelope

```json
{
  "schemaVersion": "1.0",
  "frameType": "event",
  "requestId": null,
  "timestamp": 1788852611000,
  "type": "tool_call.updated",
  "event": {
    "eventId": "evt_01K...",
    "eventSeq": 241,
    "sessionId": "sess_123",
    "epoch": 8,
    "turnId": "turn_01K...",
    "cardId": "tool_call_abc",
    "payload": {},
    "source": {
      "surface": "MOLACHAT",
      "nativeType": "TOOL_CALL",
      "nativeEventId": null,
      "nativePayload": {}
    }
  }
}
```

约束：

- `eventId` 全局唯一，客户端用它去重；
- `eventSeq` 在 gateway + sessionId + epoch 内单调递增；
- `turnId` 关联同一轮消息、卡片和 terminal；
- `cardId` 标识可更新卡片，例如 toolCallId；
- `payload` 是规范化数据；
- `source.nativePayload` 保留原始结构化业务数据，以便协议新增字段后外部前端仍可使用；
- 无 native payload 时使用空 object，不返回内部 Java 类型或字符串化二次 JSON。

### 8.2 用户消息已接纳

```json
{
  "type": "user.message.accepted",
  "event": {
    "payload": {
      "text": "请分析附件。",
      "attachments": [
        {
          "name": "error.log",
          "mediaType": "text/plain",
          "size": 18342,
          "sha256": "6e6b..."
        }
      ],
      "metadata": {
        "externalConversationId": "order-20260908-001"
      }
    }
  }
}
```

事件不返回带签名的原附件 URL。

### 8.3 Assistant 文本增量

```json
{
  "type": "assistant.message.delta",
  "event": {
    "payload": {
      "text": "从日志看，根因是数据库连接超时。",
      "format": "markdown"
    }
  }
}
```

增量按 eventSeq 拼接。卡片可穿插在多个 delta 之间，客户端不能把所有文字先合并再把卡片放
到末尾。

### 8.4 工具调用卡片

```json
{
  "type": "tool_call.updated",
  "event": {
    "cardId": "call_abc",
    "payload": {
      "toolCallId": "call_abc",
      "title": "读取错误日志",
      "status": "in_progress",
      "kind": "read",
      "rawInput": {
        "path": "error.log"
      },
      "content": [],
      "rawOutput": null,
      "contentRef": null,
      "redactions": []
    }
  }
}
```

同一 `toolCallId` 的 pending/in_progress/completed/cancelled 使用相同 `cardId`，前端原位更新。
完成事件必须保留 `rawInput`、`content`、`rawOutput` 中用户可见的全部数据。大内容通过
`contentRef` 获取。

### 8.5 子 Agent 卡片

```json
{
  "type": "sub_agent.updated",
  "event": {
    "cardId": "subagent-agent-a",
    "payload": {
      "eventType": "AGENT_PROGRESS",
      "agentName": "Agent A",
      "status": "in_progress",
      "detail": "已完成调用链梳理",
      "progress": 60
    }
  }
}
```

支持的原生事件包括 `DISPATCH_START`、`AGENT_START`、`AGENT_PROGRESS`、`AGENT_COMPLETE`、
`AGENT_ERROR`、`DISPATCH_COMPLETE`。未知值仍原样返回。

### 8.6 定时任务卡片

```json
{
  "type": "schedule.updated",
  "event": {
    "cardId": "schedule-sch_123",
    "payload": {
      "eventType": "SCHEDULE_CREATE",
      "status": "completed",
      "detail": "{...}",
      "expanded": true
    }
  }
}
```

### 8.7 TalkTo 卡片

```json
{
  "type": "talk_to.updated",
  "event": {
    "cardId": "talk-msg_123",
    "payload": {
      "eventType": "TALK_TO_RECEIVE",
      "direction": "inbound",
      "peer": {
        "displayName": "Agent B"
      },
      "content": "接口已核验完成。",
      "status": "delivered"
    }
  }
}
```

`TALK_TO_SEND`、`TALK_TO_RECEIVE`、`TALK_TO_QUEUED`、`TALK_TO_REJECTED` 和熔断事件都要
返回。内部路由 token、通信链预算等控制字段不对外暴露。

### 8.8 任务卡片

```json
{
  "type": "task.updated",
  "event": {
    "cardId": "task-task_123",
    "payload": {
      "eventId": "task-event-456",
      "taskId": "task_123",
      "name": "检查支付异常",
      "status": "IN_PROGRESS",
      "summary": "正在检查回调日志",
      "revision": 3,
      "contentVersion": 2
    }
  }
}
```

任务事件保留原 `eventId/revision/contentVersion`，以便外部前端与实时/历史事件去重。MolaChat
surface 当前不注入 Starweave Task MCP；网关不得因新增传输面改变该能力边界。

### 8.9 上下文压缩

```json
{
  "type": "compaction.completed",
  "event": {
    "payload": {
      "provider": "codex",
      "eventType": "COMPACTION_COMPLETED"
    }
  }
}
```

### 8.10 Turn 结束

完成：

```json
{
  "type": "turn.completed",
  "event": {
    "payload": {
      "finishReason": "completed"
    }
  }
}
```

取消：

```json
{
  "type": "turn.cancelled",
  "event": {
    "payload": {
      "finishReason": "cancelled",
      "requestedBy": "external"
    }
  }
}
```

错误：

```json
{
  "type": "turn.error",
  "event": {
    "payload": {
      "code": "PROVIDER_ERROR",
      "message": "Provider connection closed",
      "retryable": true
    }
  }
}
```

每个已接纳 turn 必须恰好有一个 terminal event。取消请求与 Provider 自然完成竞争时，以底层
权威状态为准，但仍只能发布一个 terminal。

### 8.11 会话切换和状态

```json
{
  "type": "session.replaced",
  "event": {
    "payload": {
      "previousSessionId": "sess_123",
      "sessionId": "sess_456",
      "previousEpoch": 8,
      "epoch": 9,
      "reason": "MANUAL_NEW"
    }
  }
}
```

新 session 的后续 eventSeq 从 1 开始。旧连接若继续提交 epoch=8 的命令，返回
`SESSION_CONFLICT`。

### 8.12 未知扩展事件

当底层新增用户可见事件但 V1 尚无规范化类型时：

```json
{
  "type": "extension.event",
  "event": {
    "payload": {
      "extensionType": "NEW_NATIVE_CARD",
      "data": {}
    },
    "source": {
      "surface": "TEAM",
      "nativeType": "NEW_NATIVE_CARD",
      "nativeEventId": "native-123",
      "nativePayload": {}
    }
  }
}
```

这保证新卡片不会因旧网关协议未识别而被丢弃。

## 9. WebSocket 关闭码

应用使用标准 WebSocket close code 范围中的私有码：

| close code | reason | 含义 |
|---|---|---|
| 4001 | `INVALID_TOKEN` | 鉴权失败或 token 已轮换 |
| 4003 | `GATEWAY_DISABLED` | 网关被停用 |
| 4008 | `RATE_LIMITED` | 连接或消息超过限制 |
| 4009 | `SESSION_REPLACED` | 当前订阅 session 已替换，需要重新 hello/resume |
| 4010 | `SLOW_CONSUMER` | 连接发送队列已满，可按 afterSeq 重连 |
| 4011 | `EVENT_STORE_UNAVAILABLE` | 事件日志不可用，网关 fail closed |
| 4012 | `SERVER_SHUTDOWN` | 服务正在关闭 |
| 4013 | `HEARTBEAT_TIMEOUT` | 心跳超时 |

握手前失败使用 HTTP 状态码；升级成功后的错误使用 error frame 后关闭连接。

## 10. 主要业务错误码

| HTTP | code | retryable | 含义 |
|---|---|---:|---|
| 400 | `INVALID_ARGUMENT` | false | 字段缺失、格式或长度错误 |
| 400 | `UNSUPPORTED_FRAME_TYPE` | false | WebSocket frameType 不支持 |
| 400 | `UNSUPPORTED_COMMAND` | false | command type 不支持 |
| 401 | `INVALID_TOKEN` | false | 鉴权失败 |
| 403 | `GATEWAY_DISABLED` | false | 网关已停用 |
| 403 | `TARGET_FORBIDDEN` | false | 目标权限或队长规则不满足 |
| 404 | `TURN_NOT_RUNNING` | false | 指定 turn 不存在或已结束 |
| 404 | `RESOURCE_NOT_FOUND` | false | 资源不存在或不属于当前 gateway |
| 409 | `SESSION_BUSY` | true | 当前会话忙且策略为 REJECT |
| 409 | `SESSION_CONFLICT` | false | sessionId/epoch 已变化 |
| 409 | `IDEMPOTENCY_CONFLICT` | false | 相同幂等 key 对应不同请求 |
| 410 | `RESYNC_REQUIRED` | false | afterSeq 已超出事件保留窗口 |
| 410 | `RESOURCE_EXPIRED` | false | 资源已过期 |
| 413 | `MESSAGE_TOO_LARGE` | false | 消息或 frame 超限 |
| 413 | `ATTACHMENT_TOO_LARGE` | false | 单文件或总文件超限 |
| 422 | `ATTACHMENT_DOWNLOAD_FAILED` | true | 附件下载、摘要或媒体校验失败 |
| 422 | `ATTACHMENT_URL_FORBIDDEN` | false | URL 不满足 allowlist/SSRF 策略 |
| 422 | `TARGET_UNAVAILABLE` | true | 绑定目标当前不可用 |
| 429 | `RATE_LIMITED` | true | 超过限额 |
| 503 | `EVENT_STORE_UNAVAILABLE` | true | 网关事件持久化不可用 |
| 503 | `COORDINATOR_UNAVAILABLE` | true | mixed Team 协调器不可用 |
| 503 | `PROVIDER_UNAVAILABLE` | true | ACP Provider 不可用 |
| 500 | `INTERNAL_ERROR` | true | 未分类服务端错误 |

`retryable=true` 不表示可原样无限重试。修改请求重试必须复用相同 Idempotency-Key；若收到
`SESSION_CONFLICT`，应先查询状态并由业务决定是否对新 session 发起新请求。

## 11. 客户端推荐流程

1. 使用 Bearer token 建立 WebSocket；
2. 接收 `server.hello` 并保存 sessionId、epoch、lastEventSeq；
3. 用本地最后处理的 eventSeq 发送 `stream.resume`；
4. 发送 `message.send`，保存 requestId、幂等 key 和返回的 turnId；
5. 按 eventSeq 处理事件，按 eventId 去重，按 cardId 更新卡片；
6. 收到 terminal event 后将该 turn 标记完成；
7. 定期发送 `stream.ack` 并响应 ping；
8. 断线后指数退避重连，继续使用原 token 和 afterSeq；
9. 收到 `SESSION_REPLACED` 或 `SESSION_CONFLICT` 时刷新 snapshot，不混合不同 epoch；
10. token 被轮换后停止重试并向管理员申请新 token。

## 12. HTTP 调用示例

查询状态：

```bash
curl 'https://agent.example.com/api/agent-gateway/v1/session' \
  -H 'Authorization: Bearer agw_xxx'
```

发送消息：

```bash
curl -X POST 'https://agent.example.com/api/agent-gateway/v1/messages' \
  -H 'Authorization: Bearer agw_xxx' \
  -H 'Idempotency-Key: order-20260908-001-message-1' \
  -H 'Content-Type: application/json' \
  --data '{
    "expectedSessionId":"sess_123",
    "expectedEpoch":8,
    "message":"请分析附件中的错误。",
    "attachments":[{
      "url":"https://files.example.com/error.log?signature=...",
      "name":"error.log",
      "mediaType":"text/plain"
    }],
    "busyPolicy":"REJECT"
  }'
```

取消：

```bash
curl -X POST 'https://agent.example.com/api/agent-gateway/v1/session/cancel' \
  -H 'Authorization: Bearer agw_xxx' \
  -H 'Idempotency-Key: order-20260908-001-cancel-1' \
  -H 'Content-Type: application/json' \
  --data '{
    "expectedSessionId":"sess_123",
    "expectedEpoch":8,
    "expectedTurnId":"turn_01K..."
  }'
```

## 13. 兼容性规则

- V1 客户端必须校验 major schema version；`1.x` 新增可选字段应保持兼容；
- 服务端不得重命名已有 event type 或改变字段含义；
- 新增卡片优先增加规范化 event type，同时保留 `extension.event` 兜底；
- `source.nativePayload` 只能增加字段，不能替代规范化 payload；
- 不保证 event 只投递一次，客户端必须去重；
- 不保证多个 session epoch 的 eventSeq 连续；序号只在单个 epoch 内比较；
- MolaChat、Starweave、Team 的内部协议可以继续演进，但网关 V1 envelope 保持稳定。
