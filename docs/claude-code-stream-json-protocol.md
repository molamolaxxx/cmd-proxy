# Claude Code stream-json 协议调研报告

> 调研日期：2026-05-24
> Claude Code 版本：2.1.128
> 目的：评估 Java 桥接方案接入 Claude Code 的可行性

## 1. 背景

Claude Code CLI 不支持 ACP 协议（`claude acp` 会被当成 prompt 处理）。Anthropic 的产品策略是：
- **kiro-cli**：面向 IDE 集成，提供原生 ACP 端点
- **Claude Code CLI**：面向终端用户，提供 stream-json 协议
- **Agent SDK**：面向编程式集成，作为 library 嵌入进程

要让 cmd-proxy 接入 Claude Code 的能力，需要通过 stream-json 协议做桥接。

## 2. 启动命令

```bash
claude -p \
  --input-format stream-json \
  --output-format stream-json \
  --verbose \
  --dangerously-skip-permissions
```

### 关键参数说明

| 参数 | 必需 | 说明 |
|------|------|------|
| `-p` / `--print` | 是 | 非交互模式 |
| `--input-format stream-json` | 是 | stdin 接收 stream-json 格式输入 |
| `--output-format stream-json` | 是 | stdout 输出 stream-json 格式 |
| `--verbose` | 是 | `output-format=stream-json` 强制要求此参数 |
| `--dangerously-skip-permissions` | 视场景 | 跳过权限确认，适合沙箱环境 |
| `--include-partial-messages` | 可选 | 开启流式增量输出（token 级别） |
| `--session-id <uuid>` | 可选 | 指定会话 ID |
| `--resume <session-id>` | 可选 | 恢复已有会话 |
| `--model <model>` | 可选 | 指定模型（如 `claude-sonnet-4-6`） |
| `--mcp-config <path>` | 可选 | MCP 服务器配置文件路径 |
| `--system-prompt <text>` | 可选 | 自定义系统提示 |
| `--max-turns <n>` | 可选 | 限制 agent loop 最大迭代次数 |
| `--max-budget-usd <amount>` | 可选 | 限制最大花费 |

## 3. 输入协议（stdin）

### 3.1 用户消息格式

```json
{"type":"user","message":{"role":"user","content":[{"type":"text","text":"你的prompt内容"}]}}
```

**注意**：
- type 必须是 `"user"`，不是 `"user_message"`（后者会被静默忽略）
- content 必须是数组格式，每个元素有 `type` 和 `text` 字段
- 每条消息占一行（以 `\n` 结尾）
- 支持图片输入（content 数组中加入 `{"type":"image","source":{...}}` 元素）

### 3.2 中断消息格式

```json
{"type":"interrupt"}
```

> ⚠️ 实测中 interrupt 的效果不够明确，可能需要配合 SIGINT 信号或直接 kill 进程。

### 3.3 多轮对话

stdin 保持打开状态，连续写入多条 user 消息即可实现多轮对话：
- 发送第一条消息后，等待收到 `type:"result"` 表示本轮结束
- 然后可以发送下一条消息
- 整个过程共享同一个 session_id
- 关闭 stdin 后进程正常退出

## 4. 输出协议（stdout）

每行一个完整的 JSON 对象。按出现顺序分为以下类型：

### 4.1 `type: "system"` — 系统事件

#### subtype: "init"（会话初始化）

每轮开始时输出，包含会话元信息：

```json
{
  "type": "system",
  "subtype": "init",
  "cwd": "/path/to/workdir",
  "session_id": "uuid",
  "tools": ["Task", "Bash", "Edit", "Read", ...],
  "mcp_servers": [],
  "model": "claude-sonnet-4-6",
  "permissionMode": "bypassPermissions",
  "claude_code_version": "2.1.128",
  "agents": ["Explore", "general-purpose", "Plan"],
  "skills": ["update-config", "debug", ...],
  "memory_paths": {"auto": "/path/to/memory/"},
  "uuid": "event-uuid"
}
```

#### subtype: "status"（状态变更，需 `--include-partial-messages`）

```json
{
  "type": "system",
  "subtype": "status",
  "status": "requesting",
  "session_id": "uuid"
}
```

### 4.2 `type: "assistant"` — 模型输出

`message.content` 是数组，元素类型有：

#### 文本回复

```json
{
  "type": "assistant",
  "message": {
    "id": "msg-uuid",
    "role": "assistant",
    "model": "claude-sonnet-4-6",
    "content": [{"type": "text", "text": "回复内容"}]
  },
  "parent_tool_use_id": null,
  "session_id": "uuid"
}
```

#### 工具调用请求

```json
{
  "type": "assistant",
  "message": {
    "content": [{
      "type": "tool_use",
      "id": "tooluse_xxx",
      "name": "Bash",
      "input": {"command": "ls /tmp", "description": "List files"}
    }]
  },
  "session_id": "uuid"
}
```

#### 思考过程

```json
{
  "type": "assistant",
  "message": {
    "content": [{"type": "thinking", "thinking": "思考内容...", "signature": ""}]
  },
  "session_id": "uuid"
}
```

### 4.3 `type: "user"` — 工具执行结果

由 claude 进程内部自动执行工具后回填：

```json
{
  "type": "user",
  "message": {
    "role": "user",
    "content": [{
      "tool_use_id": "tooluse_xxx",
      "type": "tool_result",
      "content": "命令输出内容",
      "is_error": false
    }]
  },
  "session_id": "uuid",
  "tool_use_result": {
    "stdout": "命令标准输出",
    "stderr": "",
    "interrupted": false,
    "isImage": false
  }
}
```

### 4.4 `type: "result"` — 本轮结束

```json
{
  "type": "result",
  "subtype": "success",
  "is_error": false,
  "duration_ms": 8981,
  "duration_api_ms": 8870,
  "num_turns": 2,
  "result": "最终文本结果",
  "stop_reason": "end_turn",
  "session_id": "uuid",
  "total_cost_usd": 0.034641,
  "terminal_reason": "completed",
  "usage": {
    "input_tokens": 10292,
    "output_tokens": 251,
    "cache_creation_input_tokens": 0,
    "cache_read_input_tokens": 0
  },
  "modelUsage": {
    "claude-sonnet-4-6": {
      "inputTokens": 10292,
      "outputTokens": 251,
      "costUSD": 0.0346,
      "contextWindow": 200000,
      "maxOutputTokens": 32000
    }
  }
}
```

### 4.5 `type: "stream_event"` — 流式增量（需 `--include-partial-messages`）

开启后输出 token 级别的增量事件：

```json
{"type":"stream_event","event":{"type":"message_start","message":{...}}}
{"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}}
{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"增量文本"}}}
{"type":"stream_event","event":{"type":"content_block_stop","index":0}}
{"type":"stream_event","event":{"type":"message_delta","delta":{"stop_reason":"end_turn"}}}
{"type":"stream_event","event":{"type":"message_stop"}}
```

## 5. 会话管理

### 5.1 新建会话

不指定 `--session-id` 时自动生成 UUID。也可以显式指定：

```bash
claude -p --session-id "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee" ...
```

### 5.2 恢复会话

使用 `--resume` 参数，传入之前的 session_id：

```bash
claude -p --resume "之前的session-id" --input-format stream-json --output-format stream-json --verbose ...
```

恢复后上下文完整保留（验证通过：第一轮告知名字，第二轮 resume 后能正确回忆）。

### 5.3 多轮对话（进程内）

stdin 保持打开，连续发送消息。每轮的事件流：

```
init → assistant(e/text) → [user(tool_result) → assistant(text)] → result
```

收到 `result` 后可以发送下一条消息。

### 5.4 取消执行

- `{"type":"interrupt"}` — 协议层支持，但实测效果不明确
- 发送 SIGINT 信号 — 未测试
- 直接 kill 进程 — 最可靠的方式

## 6. 与 ACP 协议的功能对比

| ACP 功能 | stream-json 对应方式 | 状态 |
|----------|---------------------|------|
| `initialize` | 进程启动时自动完成，输出 `system/init` | ✅ |
| `session/new` | `--session-id <uuid>` | ✅ |
| `session/load` | `--resume <session-id>` | ✅ |
| `session/prompt` | stdin 写入 user 消息 | ✅ |
| `session/cancel` | `{"type":"interrupt"}` 或 kill 进程 | ⚠️ |
| `session/update`（流式通知） | `stream_event` + `content_block_delta` | ✅ |
| `session/request_permission` | `--permission-prompt-tool <mcp-tool>` | ✅（不同机制） |

## 7. Java/Kotlin 桥接方案要点

### 7.1 进程模型

```
cmd-proxy (Java)
    ↕ ProcessBuilder stdin/stdout
claude -p --input-format stream-json --output-format stream-json --verbose
```

### 7.2 实现要点

1. **ProcessBuilder 启动**：设置 `redirectErrorStream(false)`，分别处理 stdout 和 stderr
2. **写入 stdin**：`BufferedWriter` 写入 JSON 行 + flush（不要 close，保持多轮）
3. **读取 stdout**：`BufferedReader.readLine()` 逐行读取，JSON 解析后按 type 分发
4. **会话恢复**：新起进程时加 `--resume <session-id>` 参数
5. **取消**：`Process.destroy()` 或 `Process.destroyForcibly()`
6. **流式推送**：加 `--include-partial-messages`，监听 `stream_event` 中的 `content_block_delta`

### 7.3 与现有架构的关系

- **不需要 ACP 协议翻译层**：直接在 cmd-proxy 内部实现 stream-json Client
- **不复用 AbstractAcpClient**：需要写新的 Provider 实现
- **MCP/Skill**：claude 进程内部自行处理，对桥接层透明
- **工具执行**：claude 进程内部自动完成，Java 侧只需读取结果用于展示

### 7.4 进程生命周期

| 场景 | 策略 |
|------|------|
| 单轮问答 | 写入消息 → 等待 result → 关闭 stdin → 进程退出 |
| 多轮对话 | 保持 stdin 打开 → 连续发消息 → 最后关闭 stdin |
| 恢复会话 | 新起进程 + `--resume` 参数 |
| 超时/取消 | `Process.destroy()` |

## 8. 参考项目

- **overstory** (jayminwest/overstory)：正确的 stdin 消息格式参考
  - `{"type":"user","message":{"role":"user","content":[{"type":"text","text":"..."}]}}`
- **Maestro** (RunMaestro/Maestro)：完整的 Claude Code 集成实现
  - 支持 `--input-format stream-json` 用于图片输入
  - ChildProcessSpawner 中的进程管理逻辑
- **optio** (jonwiggins/optio)：生产环境中的 stream-json 使用
  - 收到 `result` 事件后关闭 stdin 让进程退出
- **hapi** (tiann/hapi)：interrupt 和 permission-prompt-tool 的使用
- **auto-dev** (phodal/auto-dev)：Kotlin 项目中的 Claude Code 配置参考

## 9. 已知限制

1. **一个进程 = 一个 session**：不像 kiro-cli ACP 可以一个进程管理多个 session
2. **interrupt 不可靠**：优雅取消机制不如 ACP 的 `session/cancel` 明确
3. **进程启动开销**：每次恢复会话需要新起进程（但 claude 进程启动较快）
4. **无 session 列表 API**：不能通过 stream-json 协议查询已有 session 列表（需要用 Agent SDK 的 `listSessions()`）
