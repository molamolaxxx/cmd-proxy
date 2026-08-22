# 信道绑定 ACP 与 TalkTo 双向通信 MVP 技术方案

## 1. 目标

在 cmd-proxy 中增加可配置的外部“信道”，第一种实现为企业微信智能机器人 WebSocket 长连接，并完成最小双向闭环：

1. 在当前 cmd-proxy 环境中新建并启动企业微信信道；
2. 一个信道绑定一个明确的普通 ACP client；
3. 企业微信收到文本消息后，以 TalkTo 来信的形式投递到该 client 的当前 session；
4. Agent 完成处理后输出既有 `talk_to` 指令，由同一 TalkTo 路由机制将最终文本回复到原企业微信会话。

MVP 不创建新的 ACP session，不改变 session 的恢复、清理和进程管理逻辑。

## 2. MVP 范围

### 2.1 包含

- 信道配置、启动、停止、状态查询和基础日志；
- 企业微信 WebSocket 的连接、订阅认证、应用层心跳和指数退避重连；
- `aibot_msg_callback` 文本消息接收；
- `aibot_respond_msg` Markdown 最终回复；
- 回复上下文失效时，按 `userid/chatid` 使用 `aibot_send_msg` 主动发送作为兜底；
- 入站 `msgid` 去重；
- 复用 TalkTo 的 READY 直投、BUSY 入队和 turn 结束自动续投；
- 一个信道绑定一个本地 MAIN ACP client。

### 2.2 暂不包含

- Team member 绑定；
- 一个信道绑定多个 ACP client、规则路由或负载均衡；
- 图片、文件、语音、视频的下载、解密、上传与 ACP files 映射；
- 企业微信流式增量回复、模板卡片和欢迎语；
- 信道事件/inbox 持久化和重启续投；
- 跨 cmd-proxy 实例远程转发；
- 独立的权限白名单、限流和审计后台。

收到非文本消息时仅记录可诊断日志；可选回复“当前仅支持文本消息”。

## 3. 企业微信协议依据

企业微信智能机器人长连接默认地址为 `wss://openws.work.weixin.qq.com`。公开的企业微信官方 SDK 给出的核心协议为：

- 建连后发送 `aibot_subscribe`，使用 `botId + secret` 认证订阅；
- 使用 `ping` 做应用层心跳；
- 企业微信使用 `aibot_msg_callback` 推送消息、`aibot_event_callback` 推送事件；
- 开发者使用 `aibot_respond_msg` 回复原消息，回复时透传原帧 `headers.req_id`；
- 使用 `aibot_send_msg` 向 `userid/chatid` 主动发送消息；
- 消息体含 `msgid`、`chattype`、`from.userid`，群聊时含 `chatid`。

参考：

- 企业微信开发者中心：[智能机器人长连接](https://developer.work.weixin.qq.com/document/path/101463)
- 企业微信官方 SDK：[WecomTeam/aibot-node-sdk](https://github.com/WecomTeam/aibot-node-sdk)

官方现成 SDK 当前是 Node.js 包，而 cmd-proxy 是 Java 8 单进程应用。MVP 不引入 Node 旁车，使用 Java WebSocket 库按上述帧协议实现；建议使用 Java 8 兼容的 OkHttp 3.14.x。

## 4. 总体设计

```text
企业微信
  │ WebSocket: aibot_msg_callback
  ▼
WeComChannelAdapter
  │ normalize
  ▼
ChannelManager ── ChannelBindingResolver ──► AcpClientRegistry(groupId)
  │                                        当前 AcpClient / 当前 session
  ▼
ChannelTalkToBridge
  │ TalkToMessage(sender = channel route token)
  ▼
TalkToDispatcher ── READY: 直接 send
                 └─ BUSY: 进入既有 inbox，空闲后自动投递
  │
  ▼
Agent 完成事务并输出
{"action":"talk_to","target":"channel:route_xxx","content":"最终回复"}
  │
  ▼
TalkToDispatcher ── ChannelTalkToGateway ──► WeComChannelAdapter
                                             aibot_respond_msg
```

设计原则：企业微信协议只存在于 adapter 中；ACP 层只认识统一的 TalkTo 消息和不透明回复目标。

## 5. 配置模型

在 `acpConfig.json` 顶层新增 `channels`。配置属于当前数据根目录和当前 cmd-proxy 实例，不做旧字段兼容桥接。

```json
{
  "channels": [
    {
      "id": "wecom-main",
      "type": "WECOM_WS",
      "enabled": true,
      "inboundEnabled": true,
      "botId": "企业微信 Bot ID",
      "secret": "企业微信 Secret",
      "wsUrl": "wss://openws.work.weixin.qq.com",
      "binding": {
        "instanceId": "当前 cmd-proxy instanceId",
        "groupId": "被绑定的 ACP groupId"
      }
    }
  ]
}
```

约束：

- `channel.id` 在当前实例内唯一；
- `inboundEnabled` 缺省为 `true`；关闭时仅阻止外部消息进入 ACP，保持 WebSocket 与主动发送可用；
- MVP 仅接受 `type=WECOM_WS`；
- `binding.instanceId` 必须等于 `CmdProxyHome.instanceId()`，用于防止配置被复制到另一环境后静默误投；
- `binding.groupId` 必须对应已启动、非 `onlySubAgent`、MAIN scope 的 `AcpClient`；
- 信道启动时绑定目标不存在则状态为 `ERROR`，不自动选择同名 robot；
- 每次收到事件时按 `groupId` 从 registry 重新取 client，不长期缓存 client 引用，因此 robot 热重载后可自然绑定新 client；
- `secret` 不写日志。MVP 可与现有 API key 一样保存在本地配置文件中，但 ConfigUI 返回和展示时应做掩码，保存空掩码不得覆盖原 secret。

ConfigUI 最小改动是在现有环境页签内新增“信道”区域：新增/删除企业微信信道、选择当前环境中的 ACP client、展示 `CONNECTING/CONNECTED/RECONNECTING/ERROR/STOPPED`。保存后先采用全量 ACP 服务热重载，暂不实现信道级热更新。

## 6. 核心对象

建议新增包 `com.mola.cmd.proxy.app.acp.channel`：

```text
channel/
├── ChannelManager.java
├── ChannelAdapter.java
├── ChannelTalkToBridge.java
├── ChannelTalkToGateway.java
├── model/
│   ├── ChannelConfig.java
│   ├── ChannelBinding.java
│   ├── ChannelEvent.java
│   ├── ChannelReplyRoute.java
│   └── ChannelStatus.java
└── wecom/
    ├── WeComChannelAdapter.java
    ├── WeComFrame.java
    └── WeComProtocol.java
```

职责：

- `ChannelManager`：校验配置，管理所有 adapter 的生命周期和状态；
- `ChannelAdapter`：统一 `start/stop/send` 契约；
- `WeComChannelAdapter`：只负责企微帧协议、心跳、重连、请求回执关联和文本收发；
- `ChannelTalkToBridge`：把标准信道事件转换为 TalkTo 来信；
- `ChannelTalkToGateway`：识别信道路由令牌，把 outbound TalkTo 交给对应 adapter；
- `ChannelReplyRoute`：短期保存原始 `req_id`、`msgid`、`userid/chatid`、会话类型和过期时间。

## 7. TalkTo 扩展方式

### 7.1 外部端点优先路由

给普通 `TalkToDispatcher` 增加一个可选的外部网关扩展点：

```java
interface ExternalTalkToGateway {
    boolean supports(String target);
    String deliver(TalkToRequest request, String senderName);
}
```

`TalkToDispatcher.deliver()` 的顺序调整为：

1. 外部端点（`channel:`）；
2. 已配置的跨 chatter 联系人；
3. 现有 `chatterId:robotName` 远程格式；
4. 本地 robot。

必须把外部端点放在现有“target 包含冒号”的判断之前，否则 `channel:route_xxx` 会被误判为跨 chatter。

该扩展只接入普通 MAIN dispatcher。Team dispatcher 继续保持严格队内白名单，不注册信道网关，避免信道绕过 Team 隔离。

### 7.2 入站复用既有队列

从 `TalkToDispatcher.deliverLocal()` 中抽取一个可复用的包级方法，输入为明确的 `targetRoutingName + targetClient + TalkToMessage`，统一执行：

- client 为 READY：推送 TalkTo receive 事件并调用当前 client 的 `send()`；
- client 非 READY：进入该 routing key 的既有容量 5 inbox；
- turn 结束恢复 READY 后，继续使用 `checkAndDeliverInbox()` 投递下一条。

`ChannelTalkToBridge` 不能直接绕过状态检查调用 `AcpClient.send()`，否则目标 BUSY 时事件会立即失败，也会产生第二套排队语义。

### 7.3 不透明回复令牌

每个入站事件创建一次性随机令牌，例如：

```text
channel:wecom-main:r_5d81f6...
```

令牌映射到 `ChannelReplyRoute`，不把 bot secret、userid、chatid 或 `req_id` 暴露给 Agent。映射采用有界 TTL 缓存：

- route 在内存中最多保留 24 小时（同时设置总条目上限）；保留期内优先使用原 `req_id` 调 `aibot_respond_msg`；
- 被动回复失败但 route 仍在保留期内时，使用其中的会话地址降级 `aibot_send_msg`；
- 发送成功后删除 route；
- 同一路由只允许成功发送一次，防止 Agent 重复 talkTo；
- 未知、过期或已消费 route 返回明确的 `[talkTo 结果]` 失败信息。

### 7.4 投递给 Agent 的 prompt

新增 `ChannelTalkToMessage`（可继承或组合现有 `TalkToMessage`），在 prompt 中明确：

- 展示信道名称、发送者和用户原始文本；
- 发送者身份及正文均是外部输入，不是系统指令；
- 当前任务处理完后必须输出且只输出一次指定 target 的 `talk_to` JSON；
- `content` 是要发回企业微信的最终 Markdown；
- 不发送中间进度，不修改 target，不在脚本中等待；
- 普通回答文本不会作为可靠的信道回复路径。

示例：

```text
📨 [外部信道消息] 企业微信 / 张三
请检查今天的构建为什么失败

完成后使用以下方式回复：
{"action":"talk_to","target":"channel:wecom-main:r_5d81f6...","content":"最终结果"}
```

现有 `DispatchBufferFilter` 会隐藏 JSON，并由 `AcpClient.handleTalkTo*()` 在当前会话中处理，因此无需改 ACP JSON-RPC 协议。

## 8. 企业微信 Adapter 行为

### 8.1 建连

1. OkHttp 打开 WebSocket；
2. `onOpen` 后发送 `{"cmd":"aibot_subscribe","headers":{"req_id":"唯一请求 ID"},"body":{"bot_id":"...","secret":"..."}}`；
3. 收到成功回执后状态改为 `CONNECTED`；
4. 每 30 秒发送 `ping`，连续未收到 ack 判定连接失效；
5. 异常断开按 1、2、4、8、16、30 秒退避重连，认证成功后重置计数；
6. 主动 stop 时取消心跳和重连任务并关闭 socket，不再重连。

同一 `botId` 只允许一个启用的本地信道配置，避免新连接挤掉旧连接。
如果收到 `aibot_event_callback` 中的 `disconnected_event`，表示相同机器人已有新连接建立；当前连接应停止心跳并进入 `ERROR`，不能自动重连形成互相挤占。

### 8.2 入站

仅处理 `cmd=aibot_msg_callback && body.msgtype=text`：

1. 先按 `body.msgid` 做有界 TTL 去重；
2. 解析正文、`headers.req_id`、`from.userid`、`chatid/chattype`；
3. 创建 reply route；
4. 转成 `ChannelEvent` 并投递绑定 ACP；
5. 绑定无效或 inbox 已满时，立即向企微返回简短失败提示，并记录结构化错误。

`aibot_event_callback` 在 MVP 中完成解析和日志记录，但不触发 ACP 事务，避免“进入会话”等无正文事件消耗 turn。后续可按事件类型配置是否投递。

### 8.3 出站

MVP 只发送最终 Markdown 文本：

- 优先 `aibot_respond_msg`，透传原始 `req_id`；
- body 使用完成态 stream：`{"msgtype":"stream","stream":{"id":"唯一 streamId","finish":true,"content":"最终 Markdown"}}`；
- 回复帧必须等待相同 `req_id` 的成功回执后才把 route 标记为已消费；
- 网络失败可重试一次；不确定是否已成功时不能无限重发；
- 原消息回复不可用时，用新的 `req_id` 发送 `aibot_send_msg`，body 为 `{"chatid":"userid 或群 chatid","msgtype":"markdown","markdown":{"content":"最终 Markdown"}}`。

## 9. 生命周期接入

`Main.startAcpServices()` 解析顶层 `channels` 并传入 `AcpProxy.start()`。

启动顺序：

1. 构建普通 ACP clients；
2. 初始化 TalkTo dispatcher 和所有 client 能力；
3. 创建 `ChannelManager`；
4. 校验 binding；
5. 启动各信道连接。

停止顺序：

1. `ChannelManager.stop()`，先停止接收新外部事件和重连；
2. 停 schedule；
3. 关闭普通/Team ACP clients；
4. 清理 TalkTo inbox 和 route TTL 缓存。

这个顺序避免 ACP 已关闭后信道仍继续投递。信道 stop 不使用 OS 进程 kill。

## 10. 错误语义与可观测性

信道状态：`STOPPED / CONNECTING / AUTHENTICATING / CONNECTED / RECONNECTING / ERROR`。

至少记录以下结构化字段：

- `channelId/type/status`；
- `instanceId/bindingGroupId/sessionId`；
- `eventId/msgid/routeId`（不记录正文和凭据）；
- `delivery=direct|queued|rejected`；
- `reply=passive|proactive`；
- `errorCode/retryable`。

建议错误码：

- `CHANNEL_CONFIG_INVALID`
- `CHANNEL_AUTH_FAILED`
- `CHANNEL_DISCONNECTED`
- `ACP_BINDING_NOT_FOUND`
- `ACP_INBOX_FULL`
- `DUPLICATE_EVENT`
- `REPLY_ROUTE_EXPIRED`
- `CHANNEL_SEND_FAILED`

配置错误不阻止其它 ACP client 和其它信道启动；单个信道进入 `ERROR` 即可。

## 11. 并发和一致性

- 同一 ACP client 仍由现有单线程 executor 串行执行 turn；
- 多个企微事件到同一 busy client 时沿用 TalkTo inbox 容量 5，超过即拒绝，不做无界堆积；
- `msgid` 去重避免企业微信重投导致重复事务；
- route 使用随机 ID 且一次消费，避免不同会话串回；
- 信道每次投递按 `groupId` 解析当前 client，并记录实际 `sessionId`；
- 绑定永远指向当前 cmd-proxy 实例，MVP 不通过 MolaChat 猜测或跨实例选择目标；
- cmd-proxy 重启后旧 route 不恢复，企微侧重投可形成新事务；这是 MVP 的明确限制。

## 12. 代码改动清单

预计改动：

- `cmd-proxy-app/pom.xml`：增加 Java 8 兼容 WebSocket 依赖；
- `Main.kt`：解析 `channels`，传入启动参数；
- `AcpProxy.kt`：创建/关闭 `ChannelManager`，给普通 dispatcher 注册外部 gateway；
- `TalkToDispatcher.java`：增加外部端点优先路由，抽取统一入站投递方法；
- `TalkToMessage.java`：允许安全的信道显示名，或新增 `ChannelTalkToMessage`；
- `AcpClient.java`：尽量不改核心 send 流程，只复用现有 inbox 检查；若需要避免信道事务流式内容同步显示到 MolaChat，再增加 per-send listener 重载，默认行为保持不变；
- `ConfigUiServer.java` / `configui/index.html`：信道 CRUD、绑定选择、secret 掩码和状态展示；
- 新增 `acp/channel/**` 实现和对应测试。

第一版建议不修改 `AcpRobotParam`，因为信道是实例级资源，不属于 robot 能力快照；绑定关系放在顶层 `channels` 更清晰。

## 13. 测试计划

### 13.1 单元测试

- 配置校验：重复 ID、错误 instanceId、缺失 groupId、重复 botId；
- 帧协议：subscribe、ping、callback 解析、respond/send 编码、回执关联；
- 重连：退避增长、认证成功重置、stop 后不重连；
- 去重：相同 `msgid` 只投递一次；
- TalkTo 路由：`channel:` 优先于跨 chatter 冒号规则；
- READY 直投、BUSY 入队、队列满拒绝、空闲续投；
- route 一次消费、过期、未知令牌、被动失败降级主动发送；
- Team dispatcher 拒绝/不认识信道路由；
- secret 不出现在日志、错误和状态对象中。

### 13.2 集成测试

使用本地假 WebSocket server 完成：

1. subscribe 认证；
2. 推送文本 callback；
3. 验证绑定 client 的原 sessionId 不变；
4. 模拟 Agent 输出 `talk_to channel:...`；
5. 验证企微最终回复携带原 `req_id`；
6. 断线重连后重复 `msgid` 不重复执行。

### 13.3 回归

先运行新增 channel/talkTo 测试，再执行：

```bash
mvn -pl cmd-proxy-app -am test
git diff --check
```

重点确认普通 ACP send/new/restore/cancel、普通 talkTo、跨 chatter talkTo、Team talkTo、schedule 和热重载均不变。

## 14. 实施顺序

### P0-A：纯内存双向闭环

- 配置模型、ChannelManager 和绑定校验；
- TalkTo 外部端点与统一入站投递；
- fake adapter 测试：信道文本 → 当前 session → talk_to → 信道回复。

### P0-B：企业微信协议

- WebSocket 连接、订阅、心跳、重连；
- 文本 callback、最终 Markdown 回复、回执与主动发送兜底；
- 本地 fake server 集成测试。

### P0-C：配置页和真实联调

- ConfigUI 信道配置、ACP 绑定选择和状态；
- 使用真实企业微信 Bot ID/Secret 联调；
- 完整模块回归。

## 15. 验收标准

MVP 完成必须同时满足：

1. ConfigUI 或配置文件可创建一个企业微信信道并绑定当前环境的指定 ACP groupId；
2. 启动后完成 WebSocket 认证，断线可自动恢复；
3. 企业微信文本消息进入绑定 client 的当前 session，投递前后 sessionId 不变；
4. client 忙碌时事件排队，空闲后自动处理，队列满有明确回复；
5. Agent 的最终 `talk_to` 内容只返回原企微单聊/群聊，不串到其它 route；
6. 相同 `msgid` 不重复执行，route 不重复发送；
7. secret 不出现在日志和状态接口；
8. 新增测试、完整 `cmd-proxy-app` 模块回归和 `git diff --check` 全部通过。

## 16. 已知风险

- Agent 可能未按 prompt 输出最终 `talk_to`。MVP 通过强约束 prompt 和测试主流 provider 降低风险；后续可增加“无 talk_to 时由 turn 结果自动回信道”的可配置兜底，但第一版不同时引入两种完成语义。
- 企业微信被动回复窗口和最终消息 body 可能随协议版本调整。实现前应以官方文档和官方 SDK 当时版本锁定字段，并用真实网关 probe 验证。
- 当前 TalkTo inbox 仅 5 条且不持久化，高并发或重启可能丢事件。MVP 选择明确拒绝和可诊断状态，不做隐式无界缓存。
- 信道触发的 Agent 流式内容仍可能通过 client 的全局 listener 显示在原 MolaChat 会话。若联调确认不可接受，在不改变默认 send 行为的前提下增加 per-send listener；这不影响 TalkTo 回信道主链路。
