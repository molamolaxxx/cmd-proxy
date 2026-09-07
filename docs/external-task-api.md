# 对外任务创建接口

ConfigUI 的“外部渠道”页面可以创建对外任务接口。每个接口配置包含独立鉴权码、默认任务名称与内容，以及固定的 Agent 或队伍目标。

该接口与 ConfigUI 共用监听端口，因此部署时必须启用 ConfigUI。首期定位为服务端到服务端调用，不返回 CORS 响应头；浏览器应用如需跨域调用，应通过同源网关或业务后端转发。

## 请求

```http
POST /api/external/v1/tasks
Authorization: Bearer <鉴权码>
Idempotency-Key: <业务幂等Key>
Content-Type: application/json
```

```json
{
  "name": "处理订单 ORD-20260906-001 的支付异常",
  "content": "用户已付款，但订单状态仍为待支付，请检查支付回调。"
}
```

`name` 和 `content` 都可以省略；省略时使用接口配置的默认值。请求中的非空值优先于默认值。合并后 `name` 仍为空时，请求会被拒绝。调用方不能覆盖接口配置中的任务目标。

示例：

```bash
curl -X POST 'http://127.0.0.1:10528/api/external/v1/tasks' \
  -H 'Authorization: Bearer your-auth-code' \
  -H 'Idempotency-Key: order-ORD-20260906-001' \
  -H 'Content-Type: application/json' \
  --data '{"name":"处理支付异常","content":"检查订单支付状态。"}'
```

## 幂等语义

幂等范围是“接口配置 ID + `Idempotency-Key`”。同一个接口中：

- 相同幂等 Key 和相同有效任务参数返回同一个任务，不会重复创建；
- 相同幂等 Key 对应不同参数时返回 HTTP 409 和 `IDEMPOTENCY_CONFLICT`；
- 幂等数据与任务一起持久化，服务重启后继续生效。

不同接口配置可以使用相同的幂等 Key。

## 成功响应

```json
{
  "accepted": true,
  "code": "TASK_CREATED",
  "message": "Task accepted",
  "data": {
    "taskId": "f515f79d-4bb8-42e6-bf1a-d0bcebd84c18",
    "name": "处理支付异常",
    "status": "START",
    "revision": 1,
    "contentVersion": 1
  }
}
```

HTTP 201 表示任务已持久化并进入现有 outbox 派发流程，不表示 Agent 已经完成任务。

## 主要错误

| HTTP | code | 含义 |
|---|---|---|
| 400 | `INVALID_ARGUMENT` | 请求字段、任务名称或幂等 Key 不合法 |
| 401 | `INVALID_AUTH_CODE` | Bearer 鉴权码不存在或格式错误 |
| 403 | `ENDPOINT_DISABLED` | 接口配置已停用 |
| 409 | `IDEMPOTENCY_CONFLICT` | 同一幂等 Key 被用于不同的有效任务参数 |
| 413 | `RESOURCE_LIMIT` | 请求体或任务内容超过限制 |
| 422 | `TASK_TARGET_UNAVAILABLE` | 接口未配置任务目标 |
| 503 | `TASK_SERVICE_UNAVAILABLE` | 配置、任务存储或运行时暂不可用 |

鉴权码不会出现在普通接口响应或服务日志中。ConfigUI 配置读取时返回掩码，保存掩码值不会覆盖磁盘中的原鉴权码。
