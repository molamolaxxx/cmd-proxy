# ACP Action JSON 迁移到 cmd-proxy MCP 工具

## 目标

将 `dispatch_subagent`、`schedule_task`、`manage_schedule` 和 `talk_to` 从“Agent 输出 Action JSON，cmd-proxy 拦截文本执行”迁移为标准 MCP 工具调用，同时保持以下对外行为不变：

- 子 Agent、定时任务、TalkTo 的业务语义不变。
- MolaChat/Fast Team 现有 Action 专属卡片的样式、顺序和完成边界不变。
- 主 Agent、Team Member、SubAgent、信道、定时任务的 principal 和路由上下文继续正确传播。
- 旧 ACP Session 在迁移期间仍可通过 Action JSON 兼容层工作。

## 设计约束

1. cmd-proxy 运行时状态是权威状态；MCP 请求不得通过工具参数伪造 Robot、principal、owner 或信道身份。
2. MCP 调用通过 `authSessionId` 解析当前 AcpClient 和逻辑 turn 上下文。
3. 不依赖 Provider 的运行中 MCP 热插拔；新 MCP 配置通过 `session/new` 或 `session/load` 生效。
4. 内置 MCP 使用 Streamable HTTP，支持 JSON 和 `text/event-stream` 响应；不引入只有双端点的 Legacy SSE 依赖。
5. MCP 工具只替换“Agent 如何发起操作”；现有 Dispatcher、Manager、队列、权限和卡片事件尽量复用。
6. 新提示词不再包含可被 Action 过滤器误执行的完整 JSON 示例。

## 目标架构

```text
Agent Provider
  │ tools/call
  ▼
CmdProxyControlServer（127.0.0.1，独立线程池）
  │ /mcp
  ▼
CmdProxyMcpHttpHandler
  │ authSessionId
  ▼
ActionRuntimeRegistry
  │ ActionExecutionContext
  ▼
ActionToolService
  ├─ dispatch_subagent
  ├─ schedule_task
  ├─ manage_schedule
  └─ talk_to
       │
       ├─ 现有 Dispatcher / Manager
       ├─ 现有 AcpResponseListener 事件
       └─ MCP tool result
```

## 工作项

### 1. 内置 MCP Server

- [x] 实现 MCP `initialize`、`notifications/initialized`、`tools/list`、`tools/call`。
- [x] 启动独立于 ConfigUI 的常驻控制服务，并在 `127.0.0.1` 上暴露 MCP endpoint。
- [x] MCP 使用独立缓存线程池，不占用 ConfigUI 的短请求线程池。
- [x] 请求体限制为 1 MiB，非 loopback 请求拒绝访问。
- [x] 支持 JSON 响应和 SSE event 格式响应。
- [x] 定义四个工具的 JSON Schema，保持现有 Action 参数语义。
- [x] 统一输出 MCP `content` 和 `isError`。

### 2. 统一业务执行层

- [x] 实现 `ActionToolService`，收口四类 Action 的执行。
- [x] 通过 AcpClient active-turn 引用承载 Robot、workspace、principal、channel turn、schedule owner 和 listener。
- [x] 实现 `ActionRuntimeRegistry`，安全管理 `authSessionId -> active turn context`。
- [x] MCP Server 仅根据 Header 中的 `authSessionId` 执行，缺失、过期或非 active turn 时拒绝。
- [x] 旧 Action JSON handler 改为调用同一 `ActionToolService`，避免双份业务逻辑。

### 3. Provider 和会话注入

- [x] 在用户 MCP 配置后动态追加 cmd-proxy 内置 MCP，不写入 Provider 原生配置文件。
- [x] 为每个具备 Action 能力的 AcpClient（含 Team）注入独立 `authSessionId` Header；不向没有 Action Harness 的分析/临时子 Client 暴露无效工具。
- [x] `session/new` 和 `session/load` 使用同一注入逻辑。
- [x] 主会话、恢复会话、自动轮转、Robot 刷新和 Team 会话均在 `client.start()` 前完成能力装配，保证首次 MCP `tools/list` 可见正确工具。
- [x] 内置 Server 名固定为保留名 `cmd-proxy-runtime`；用户配置同名 Server 时明确报错，不静默覆盖。
- [ ] 明确 Kiro、OpenCode、Claude Agent ACP、Codex ACP 的集成验证结果。
- [x] 配置变化通过 Robot 刷新和 Session 恢复生效，不宣称运行中热插拔。

### 4. 全部运行时提示词迁移

- [x] `AcpClient` 全局 Harness：从“输出 Action JSON”改为“调用 cmd-proxy MCP 工具”。
- [x] `SubAgentContextInjector`：仅保留可用 Agent、工作目录和并行语义。
- [x] `ScheduleContextInjector`：仅保留 schedule 参数语义、cron/once 规则和 owner 语义。
- [x] `TalkToContextInjector`：保留异步投递、通讯录和禁止脚本轮询约束。
- [x] `TeamTalkToContextInjector`：保留不可变 `teamMemberId` 白名单。
- [x] `TalkToMessage` 和 `TeamTalkToDispatcher` 来信：改为引导调用 MCP 回复。
- [x] `ChannelTalkToMessage`：改为引导 `talk_to(target = 回复, ...)`，继续拒绝猜测信道路由。
- [x] `DirectJsonOutputHelper` 已退出所有运行时提示注入路径并删除源码。
- [x] 上下文压缩后重注入的也必须是新 MCP Harness。

### 5. 卡片和流式交互兼容

- [x] 继续通过 `onSubAgentEvent`、`onScheduleEvent`、`onTalkToEvent` 发送专属卡片。
- [x] 对 cmd-proxy 内置 MCP 工具抑制重复的通用 tool card。
- [x] 外部 MCP 工具仍使用现有通用 tool card。
- [ ] 保持卡片后文本、连续卡片、Fast Team 和普通 ACP 的渲染一致。

### 6. Action JSON 兼容和删除路径

- [x] 迁移期保留 `DispatchBufferFilter` 和 JSON parser，但新提示词不再引导使用。
- [x] 兼容路径和 MCP 路径共用业务执行层和卡片事件。
- [x] 记录兼容路径累计命中次数，作为后续删除依据。
- [ ] 四个 Provider 完成真实验证后，再删除 Action Loop 和文本拦截。

### 7. 测试和验收

- [x] MCP 协议聚焦测试：工具列表、调用成功、缺失身份错误、SSE 响应。
- [x] 运行时路由测试：缺失/错误 `authSessionId` 和非 active turn 拒绝；既有 principal/turn 生命周期测试继续覆盖继承与清理。
- [ ] 工具测试：子 Agent 并行、schedule CRUD、TalkTo、Team 白名单、信道“回复”。
- [x] 提示词聚焦测试：包含 MCP 工具语义，不包含 `"action"`、“独占一行”和“输出 JSON 后结束”。
- [x] 卡片单元测试：cmd-proxy 工具通用卡片去重，外部工具不受影响。
- [ ] 对 Kiro、OpenCode、Claude Agent ACP、Codex ACP 分别做真实 MCP 调用。
- [ ] 在当前 MolaChat 对话中做真实 Action 卡片验收。
- [x] 运行聚焦测试、`cmd-proxy-app` 全量测试、打包和 `git diff --check`。

### 8. P0 全局收口

- [x] MCP 和鉴权注册/检查端点不再依赖可选的 ConfigUI 开关或 ConfigUI 启动成功。
- [x] `tools/list` 按 `authSessionId` 返回当前 Client 实际具备的工具，不向无对应能力的会话暴露空壳工具。
- [x] 全局 Harness 仅描述当前 Client 实际可用的工具，避免提示词与工具列表不一致。
- [x] MCP `tools/call` 在 Registry 层再次校验工具是否属于当前 Client。
- [x] 控制服务使用随机 loopback 端口，并把完整 URL 动态注入每次 `session/new` / `session/load`。
- [x] ConfigUI 仅保留权限策略管理页面和查询/修改 API，不再承载运行时 MCP 请求。

## 当前执行状态

- 已完成代码迁移、提示词迁移、协议测试、全量测试和打包。
- P0 收口已完成：独立控制服务、启动前能力装配、动态工具列表、保留名称冲突检查和请求边界均已实现。
- 全量测试结果：295 个测试通过，0 失败、0 错误。
- 打包结果：Reactor 三个模块全部成功，已生成普通 JAR 和依赖聚合 JAR。
- 待真实环境验收：Kiro、OpenCode、Claude Agent ACP、Codex ACP 各跑一次 MCP 调用，并在 MolaChat/Fast Team 核对专属卡片、卡片后文本和连续卡片。
- 旧 Action JSON 只作为迁移兼容入口保留；新提示词不再引导生成 Action JSON。

## 建议执行顺序

1. 内置 MCP Server 和协议测试。
2. `ActionRuntimeRegistry` 与 `ActionToolService`。
3. AcpClient/SubAgentAcpClient 的 MCP 动态注入和 turn 绑定。
4. 四个 MCP 工具接入现有业务组件。
5. 全部运行时提示词迁移。
6. 专属卡片保留和通用卡片去重。
7. 旧 Action JSON 兼容层收口。
8. 四 Provider 真实验证和 MolaChat 卡片验收。

## 完成标准

- Agent 在新 Session 中只被引导调用 MCP，不再被引导输出 Action JSON。
- 四个工具的执行结果直接作为 MCP tool result 返回 Agent。
- 专属 Action 卡片与迁移前一致，不出现重复的通用 MCP 卡片。
- principal、Team、信道、schedule owner 和 SubAgent 身份不丢失、不串话。
- 旧 Session 在兼容期内仍可完成 Action JSON 调用，新 Session 不产生 Action JSON。
- 聚焦测试、模块全量测试、打包和真实卡片验收通过。
