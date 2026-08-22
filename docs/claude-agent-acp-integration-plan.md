# Claude Agent ACP 接入技术方案

> 日期：2026-05-30
> 版本：v1.0
> 目标：通过 `@agentclientprotocol/claude-agent-acp` 适配器接入 Claude Code

## 1. 方案概述

使用 Zed Industries 官方维护的 `@agentclientprotocol/claude-agent-acp`（npm 包，v0.39.0），将 Claude Agent SDK 包装为标准 ACP 协议（JSON-RPC 2.0 over stdio），直接复用现有的 `AbstractAcpClient` 体系，仅需新增一个 `AgentProvider` 实现。

**架构位置**：

```
cmd-proxy (Java)
  └── AbstractAcpClient (JSON-RPC 2.0 over stdin/stdout)
        └── ClaudeAgentAcpProvider (新增)
              └── npx @agentclientprotocol/claude-agent-acp (Node.js 子进程)
                    └── @anthropic-ai/claude-agent-sdk
                          └── Anthropic API
```

## 2. API 配置详解

### 2.1 认证方式

claude-agent-acp 适配器支持 **两种认证方式**，按推荐度排序：

#### 方式一：API Key（推荐，最简）

通过 `ANTHROPIC_API_KEY` 环境变量传入。但大多数情况下**不需要手动配置**——适配器与 `claude` CLI 共享同一套凭证体系，自动复用已有登录态。

**自动复用现有凭证**

`claude-agent-acp` 和 `claude` CLI 使用**同一个 SDK**（`@anthropic-ai/claude-agent-sdk`），共享同一套 `resolveSettings()` 凭证解析机制：

```
claude-agent-acp 适配器
    → resolveSettings({ settingSources: ["user", "project", "local"] })
        → 读取 ~/.claude/settings.json          （用户级）
        → 读取 <cwd>/.claude/settings.json       （项目级）
        → 读取 <cwd>/.claude/settings.local.json （本地覆盖）
        → 读取 /etc/claude-code/managed-settings.json （企业管控）
    → 自动提取 ANTHROPIC_API_KEY / OAuth Token
```

| 你当前的 Claude Code 认证方式                        | cmd-proxy 是否需要额外配置 | 原因                                                    |
| ---------------------------------------------------- | -------------------------- | ------------------------------------------------------- |
| `claude auth login`（Claude 订阅 OAuth）               | **不需要**                     | SDK 内置 OAuth token 管理，自动刷新                        |
| `claude auth login --console`（Anthropic Console）     | **不需要**                     | API Key 存在 `~/.claude/settings.json` 或系统 keychain 中 |
| `export ANTHROPIC_API_KEY=sk-ant-...`（环境变量）      | **不需要**                     | 子进程自动继承父进程环境变量                               |
| `~/.claude/settings.json` 中手动配置 `env.ANTHROPIC_API_KEY` | **不需要**                 | SDK 的 `resolveSettings()` 自动读取                        |
| 无任何认证配置                                        | **需要**                     | 运行 `claude auth login` 或设置 `ANTHROPIC_API_KEY`        |

**结论**：如果你的 `claude` 命令行已经能正常工作，则 `claude-agent-acp` **零认证配置**即可使用。

**需要显式设置环境变量的场景**：
- cmd-proxy 运行在无桌面环境的服务器上（OAuth 浏览器流程不可用），且未预配 `~/.claude/settings.json`
- 使用不同于当前用户的 API Key
- CI/CD 容器环境

在这些场景下，通过 `ANTHROPIC_API_KEY` 环境变量显式指定。

**完整调用链路**：

```
┌────────────────────────────────────────────────────────────────────┐
│ 步骤 1: 用户设置环境变量                                          │
│                                                                   │
│   $ export ANTHROPIC_API_KEY="sk-ant-api03-xxxx"                  │
│   $ java -jar cmd-proxy.jar                                       │
│                                                                   │
│   cmd-proxy 进程继承此环境变量                                     │
└────────────────────────────────────────────────────────────────────┘
    │
    ▼
┌────────────────────────────────────────────────────────────────────┐
│ 步骤 2: ClaudeAgentAcpProvider.getExtraEnv()                      │
│                                                                   │
│   String apiKey = System.getenv("ANTHROPIC_API_KEY");             │
│   env.put("ANTHROPIC_API_KEY", apiKey);                           │
│                                                                   │
│   从 cmd-proxy JVM 环境变量中读出，写入子进程的环境变量 map        │
└────────────────────────────────────────────────────────────────────┘
    │
    ▼
┌────────────────────────────────────────────────────────────────────┐
│ 步骤 3: AbstractAcpClient.startProcess()                          │
│                                                                   │
│   pb.environment().putAll(agentProvider.getExtraEnv());           │
│   // 同时注入 HTTP_PROXY, HTTPS_PROXY, NO_PROXY（如配置）        │
│                                                                   │
│   Process process = pb.start();  // 子进程继承所有 env vars        │
└────────────────────────────────────────────────────────────────────┘
    │
    ▼
┌────────────────────────────────────────────────────────────────────┐
│ 步骤 4: Node.js 子进程 (acp-agent.js)                             │
│                                                                   │
│   // index.js 入口：同步托管策略中的环境变量                       │
│   const policy = await resolveSettings({ settingSources: [] });   │
│   for (const [key, value] of Object.entries(policy.effective.env))│
│       process.env[key] = value;                                   │
│                                                                   │
│   // createSession() 中构建 query 参数                             │
│   const options = {                                               │
│       env: {                                                      │
│           ...process.env,           // 包含 ANTHROPIC_API_KEY     │
│           ...userProvidedOptions?.env,                             │
│           ...createEnvForGateway(...),  // gateway 模式才附加      │
│       },                                                          │
│       settingSources: ["user", "project", "local"],               │
│   };                                                              │
│                                                                   │
│   const q = query({ prompt: input, options });                    │
└────────────────────────────────────────────────────────────────────┘
    │
    ▼
┌────────────────────────────────────────────────────────────────────┐
│ 步骤 5: @anthropic-ai/claude-agent-sdk                            │
│                                                                   │
│   // SDK 内部从 options.env 中读取 ANTHROPIC_API_KEY              │
│   // 构造 HTTP 请求头                                             │
│                                                                   │
│   POST https://api.anthropic.com/v1/messages                      │
│   Authorization: x-api-key: sk-ant-api03-xxxx                     │
│   Anthropic-Version: 2023-06-01                                   │
│   Content-Type: application/json                                  │
└────────────────────────────────────────────────────────────────────┘
```

**为什么不需要调用 `authenticate`？**

查看适配器源码 (`acp-agent.js`) 中 `authenticate` 方法的实现：

```javascript
async authenticate(_params) {
    if (_params.methodId === "gateway" || _params.methodId === "gateway-bedrock") {
        this.gatewayAuthRequest = _params;  // 仅处理 gateway 模式
        return;
    }
    throw new Error("Method not implemented.");  // API Key 模式不用 authenticate
}
```

`authenticate` 只处理 gateway 认证。API Key 模式下：

- `initialize` 返回 `authMethods: []`（空数组，无可用认证方法）
- ACP 协议规定：`authMethods` 为空时客户端**不必调用** `authenticate`
- 适配器在 `createSession` 时就已将 `process.env` 完整传递给 SDK，SDK 自动使用其中

**如果未设置 `ANTHROPIC_API_KEY` 会怎样？**

SDK 内部会检测到缺失凭证，抛出的错误被适配器捕获并转为 ACP 错误响应：

```json
{"jsonrpc":"2.0","id":3,"error":{"code":-32603,"message":"Internal error: API Error: ..."}}
```

不会导致进程崩溃，错误会被 `AbstractAcpClient` 现有的异常处理机制捕获。

#### 方式二：Custom Gateway（需自定义中转服务）

适用于不直连 Anthropic API 的场景（如自建代理、企业网关）：

```
AbstractAcpClient.startProcess()
  → pb.environment().put("ANTHROPIC_BASE_URL", "https://your-gateway.com/v1")
  → pb.environment().put("ANTHROPIC_AUTH_TOKEN", "your-custom-token")

AbstractAcpClient.initialize()
  → clientCapabilities: { auth: { _meta: { gateway: true } } }
    → 适配器返回 authMethods: ["gateway", "gateway-bedrock"]
      → 调用 authenticate({ methodId:"gateway", _meta:{ gateway:{ baseUrl, headers } } })
        → 适配器注入: ANTHROPIC_BASE_URL, ANTHROPIC_AUTH_TOKEN, ANTHROPIC_CUSTOM_HEADERS
```

Gateway 模式下可以完全绕开 `ANTHROPIC_API_KEY`，由中转服务负责认证。

### 2.2 环境变量一览

| 环境变量                    | 认证方式     | 用途                                       | 示例值                                      |
| --------------------------- | ------------ | ------------------------------------------ | ------------------------------------------- |
| `ANTHROPIC_API_KEY`           | API Key      | Anthropic API 密钥（Console → API Keys）     | `sk-ant-api03-xxx...`                         |
| `ANTHROPIC_BASE_URL`          | Gateway      | 自定义 API 端点基础 URL                      | `https://your-proxy.com/v1`                  |
| `ANTHROPIC_AUTH_TOKEN`        | Gateway      | 自定义认证 Token                             | `your-bearer-token`                          |
| `ANTHROPIC_CUSTOM_HEADERS`    | Gateway      | 自定义 HTTP 头（`Key: Value` 逐行分隔）       | `X-Custom: foo\nX-Other: bar`                |
| `ANTHROPIC_BEDROCK_BASE_URL`  | Gateway      | Bedrock 协议网关 URL                         | `https://your-bedrock-proxy.com`             |
| `CLAUDE_CODE_USE_BEDROCK`     | Gateway      | 启用 Bedrock 模式                           | `1`                                            |
| `CLAUDE_MODEL_CONFIG`         | 通用         | 模型 ID 覆盖 JSON（如 Bedrock 自定义模型名）   | `{"modelOverrides":{...}}`                   |
| `MAX_THINKING_TOKENS`         | 通用         | 最大 thinking token 数                       | `32000`                                       |
| `CLAUDE_CODE_EXECUTABLE`      | 通用         | 指定 Claude CLI 二进制路径（可选）             | `/path/to/claude`                             |

### 2.3 模型指定

适配器 **默认使用 Claude Opus 4.8（1M context）**。运行时可选模型：

| Model ID        | 名称              | 价格 (input/output, $/Mtok) | 场景             |
| --------------- | ----------------- | --------------------------- | ---------------- |
| `default`         | Opus 4.8 (1M ctx) | $5 / $25                    | 复杂推理         |
| `sonnet`          | Sonnet 4.6        | $3 / $15                    | 日常开发         |
| `sonnet[1m]`      | Sonnet 4.6 (1M)   | $3 / $15                    | 长上下文开发     |
| `haiku`           | Haiku 4.5         | $1 / $5                     | 快速简单任务     |

切换方式有三种（按优先级）：

1. **ACP 运行时切换**：`session/new` 返回 `configOptions`，通过 `session/update` → `config_update` 修改 `model` 字段
2. **`_meta.claudeCode.options` 参数**：在 `session/new` 请求中传入：
   ```json
   {
     "params": {
       "_meta": {
         "claudeCode": {
           "options": {
             "model": "claude-sonnet-4-6"
           }
         }
       }
     }
   }
   ```
3. **`CLAUDE_MODEL_CONFIG` 环境变量**（用于 Bedrock 等特殊模式）：
   ```json
   {"modelOverrides": {"sonnet": "us.anthropic.claude-sonnet-4-6-v1:0"}}
   ```

### 2.4 HTTP 代理

cmd-proxy 已有的 `AbstractAcpClient.startProcess()` 代理注入机制自动生效：

```java
// 不需要额外处理：AbstractAcpClient 已注入
pb.environment().put("HTTP_PROXY", url);
pb.environment().put("HTTPS_PROXY", url);
pb.environment().put("NO_PROXY", noProxy);
```

适配器将 `process.env` 完整透传给 Claude Agent SDK，HTTP 代理自然生效。

### 2.5 完整凭证流

```
┌────────────────────────────────────────────────────────────────┐
│ 用户配置                                                       │
│   ~/.cmd-proxy/acpConfig.json                                 │
│   { "agentProvider": "CLAUDE_AGENT_ACP", "model": "..." }      │
└────────────────────────────────────────────────────────────────┘
        │
        ▼
┌────────────────────────────────────────────────────────────────┐
│ ClaudeAgentAcpProvider.getExtraEnv(robotParam)                 │
│   → ANTHROPIC_API_KEY = System.getenv("ANTHROPIC_API_KEY")     │
│   → (可选) CLAUDE_MODEL_CONFIG                                 │
└────────────────────────────────────────────────────────────────┘
        │
        ▼
┌────────────────────────────────────────────────────────────────┐
│ AbstractAcpClient.startProcess()                               │
│   → ProcessBuilder + env vars                                  │
│   → 子进程: npx @agentclientprotocol/claude-agent-acp          │
└────────────────────────────────────────────────────────────────┘
        │
        ▼
┌────────────────────────────────────────────────────────────────┐
│ acp-agent.js (Node.js 子进程)                                  │
│   → resolveSettings() — 读取 ~/.claude/settings.json          │
│   → query({ env: { ...process.env, ... } })                    │
│   → @anthropic-ai/claude-agent-sdk 自动使用 ANTHROPIC_API_KEY  │
│   → 向 api.anthropic.com 发起 HTTPS 请求                        │
└────────────────────────────────────────────────────────────────┘
```

## 3. 验证结论

| 测试项                     | 结果  | 关键发现                                                     |
| -------------------------- | ----- | ------------------------------------------------------------ |
| `initialize`                 | ✅    | 标准 ACP，返回 `loadSession: true`、`sessionCapabilities: {resume, close, delete, fork, list}` |
| `session/new`                | ✅    | 返回 `sessionId`、模型列表（default/sonnet/haiku）、配置项（model/mode/effort） |
| `session/prompt`             | ✅    | 流式 `agent_message_chunk`（`content: {type:text, text:...}`），与 `extractAgentMessageText` 格式完全一致 |
| `session/update`             | ✅    | `usage_update`（`{used, size}`）、`available_commands_update`、`tool_call` |
| `session/cancel`             | ✅    | 通过 `query.interrupt()` 实现，进程不退出                     |
| `session/load` / `resume`    | ✅    | `agentCapabilities.loadSession: true`                        |
| MCP Servers                | ✅    | http + sse，通过 `session/new` 的 `mcpServers` 参数注入        |
| Error Handling             | ✅    | 标准 `{code: -32603, message:..., data: {errorKind:...}}`     |
| API Key                    | ✅    | 通过 `ANTHROPIC_API_KEY` 环境变量                             |

## 4. 实施计划

### 4.1 新增 `AgentProviderType.CLAUDE_AGENT_ACP`

**文件**：`cmd-proxy-app/src/main/java/com/mola/cmd/proxy/app/acp/acpclient/agent/AgentProviderType.java`

```java
public enum AgentProviderType {
    KIRO_CLI,
    OPENCODE,
    CLAUDE_AGENT_ACP;  // 新增
}
```

### 4.2 新增 `ClaudeAgentAcpProvider`

**文件**：`cmd-proxy-app/src/main/java/com/mola/cmd/proxy/app/acp/acpclient/agent/ClaudeAgentAcpProvider.java`

```java
public class ClaudeAgentAcpProvider implements AgentProvider {

    private static final String HOME = System.getProperty("user.home");

    @Override
    public String getCommand() {
        return "npx";
    }

    @Override
    public String[] getArgs() {
        return new String[]{"@agentclientprotocol/claude-agent-acp"};
    }

    @Override
    public List<Path> getMcpConfigPaths(String workspacePath) {
        List<Path> paths = new ArrayList<>();
        paths.add(Paths.get(HOME, ".claude", "mcp.json"));
        if (workspacePath != null && !workspacePath.trim().isEmpty()) {
            paths.add(Paths.get(workspacePath, ".claude", "mcp.json"));
        }
        return paths;
    }

    @Override
    public String getName() {
        return "claude-agent-acp";
    }

    @Override
    public String getSkillsRelativePath() {
        return ".claude/skills";
    }

    @Override
    public Map<String, String> getExtraEnv(AcpRobotParam robotParam) {
        Map<String, String> env = new HashMap<>();

        // 大多数场景无需任何配置：适配器通过 resolveSettings()
        // 自动从 ~/.claude/settings.json / OAuth 获取凭证。

        // 以下为可选显式覆盖，仅在需要绕过本地 Claude Code 配置时启用：
        // String apiKey = System.getenv("ANTHROPIC_API_KEY");
        // if (apiKey != null && !apiKey.isEmpty()) {
        //     env.put("ANTHROPIC_API_KEY", apiKey);
        // }

        return env;
    }
}
```

### 4.3 注册到 `AgentProviderRouter`

**文件**：`cmd-proxy-app/src/main/java/com/mola/cmd/proxy/app/acp/acpclient/agent/AgentProviderRouter.java`

```java
private AgentProviderRouter() {
    providers.put(AgentProviderType.KIRO_CLI, new KiroCliAgentProvider());
    providers.put(AgentProviderType.OPENCODE, new OpenCodeAgentProvider());
    providers.put(AgentProviderType.CLAUDE_AGENT_ACP, new ClaudeAgentAcpProvider());  // 新增
}
```

### 4.4 默认关闭 cmd-proxy 记忆模块

Claude Code 自带原生记忆系统（`memory_recall` + `~/.claude/memory/`），不应叠加 cmd-proxy 的 `MemoryManager`。

**文件**：`cmd-proxy-app/src/main/java/com/mola/cmd/proxy/app/acp/AcpRobotParam.java`

```java
public boolean isMemoryEnabled() {
    // Claude Code 自带原生记忆，不启用 cmd-proxy 记忆模块
    if ("CLAUDE_AGENT_ACP".equalsIgnoreCase(agentProvider)) {
        return false;
    }
    return memory != null && memory.isEnabled();
}
```

影响范围：`AcpProxy.kt` 中所有 `robot.isMemoryEnabled` 调用均自动生效：
- `initMemoryForClient` — 跳过创建 MemoryManager
- `AcpClient.setMemoryManager` — 不会被调用
- `SubAgentDispatcher.setMemoryManagers` — 不注入记忆上下文

### 4.5 适配 avatar

**文件**：`cmd-proxy-app/src/main/java/com/mola/cmd/proxy/app/acp/AcpRobotParam.java` → `getAvatar()`

```java
public String getAvatar() {
    if (avatar != null && !avatar.isEmpty()) {
        return avatar;
    }
    if ("OPENCODE".equalsIgnoreCase(agentProvider)) {
        return "img/opencode.png";
    }
    if ("CLAUDE_AGENT_ACP".equalsIgnoreCase(agentProvider)) {
        return "img/claude.png";
    }
    return "img/kiro.png";
}
```

### 4.6 上下文使用量提取

`claude-agent-acp` 的 `session/update` 中 `usage_update` 格式：

```json
{
  "sessionUpdate": "usage_update",
  "used": 19613,
  "size": 200000
}
```

当前 `AbstractAcpClient` 中 `extractContextUsage` 的调用在 `AcpClient` 的 `send` 方法中处理，`usage_update` 的通知会被捕获。需要在 `AbstractAcpClient` 层新增通用提取逻辑，或继承 `ClaudeAgentAcpProvider.extractContextUsage()` 处理此格式。

**方案**：在 `AbstractAcpClient` 中拦截 `usage_update` 通知，提取 `used/size` 计算百分比：

```java
// 在 AbstractAcpClient 的消息处理循环中
if ("usage_update".equals(updateType)) {
    long used = update.get("used").getAsLong();
    long size = update.get("size").getAsLong();
    if (size > 0) {
        contextUsagePercentage = (double) used / size * 100.0;
    }
}
```

由于 `usage_update` 格式为 ACP 规范通用格式（非 kiro 特有的 `_kiro.dev/metadata`），应将提取逻辑提升到 `AbstractAcpClient`，使所有 provider 受益。

### 4.7 配置示例

**`~/.cmd-proxy/acpConfig.json`**：

```json
{
  "robots": [{
    "name": "Claude",
    "signature": "Anthropic Claude via ACP",
    "workDir": "/home/user/projects/my-app",
    "agentProvider": "CLAUDE_AGENT_ACP",
    "model": "claude-sonnet-4-6",
    "proxyEnabled": false,
    "subAgents": []
    // 注意：不要配置 "memory" 字段，Claude Code 自带原生记忆，
    // 且代码层面已强制关闭 cmd-proxy 记忆模块（见 §4.4）
  }]
}
```

**启动 cmd-proxy 前设置环境变量**：

```bash
# 方式一（推荐）：API Key 直连
export ANTHROPIC_API_KEY="sk-ant-api03-xxxxxxxxxxxxx"

# 如需指定默认模型（覆盖配置中的 "model" 字段）
# export CLAUDE_MODEL_CONFIG='{"modelOverrides":{"sonnet":"claude-sonnet-4-6-20250514"}}'

# 启动 cmd-proxy
java -jar cmd-proxy.jar
```

**方式二（可选）：Gateway 代理模式**：

```bash
export ANTHROPIC_BASE_URL="https://your-gateway.company.com/v1"
export ANTHROPIC_AUTH_TOKEN="your-bearer-token"

# 同时在 initialize 时声明 gateway capability（需修改 AbstractAcpClient）
# clientCapabilities: { "auth": { "_meta": { "gateway": true } } }
```

## 5. 可选增强

### 5.1 npx 首次启动加速

npx 首次执行会下载包（约 30s），之后有缓存。可改为全局安装：

```bash
npm i -g @agentclientprotocol/claude-agent-acp
```

然后将 `getCommand()` 改为 `"claude-agent-acp"`，`getArgs()` 改为 `new String[]{}`。

### 5.2 Custom Gateway Auth

如需走自定义代理网关（替代直连 Anthropic API），利用 adapter 的 gateway auth：

1. `AbstractAcpClient.initialize()` 中 `clientCapabilities` 加入 `"auth": {"_meta": {"gateway": true}}`
2. `ClaudeAgentAcpProvider.getExtraEnv()` 中注入 `ANTHROPIC_BASE_URL` 和 `ANTHROPIC_AUTH_TOKEN`

### 5.3 session/update 动态切模型

adapter 在 `session/new` 返回的 `configOptions` 中包含 model 和 mode 选项，可通过 `session/update` 的 `config_update` 在运行中切换，无需重启进程。

## 6. 风险点

| 风险                          | 等级 | 应对                                                         |
| ----------------------------- | ---- | ------------------------------------------------------------ |
| npx 首次启动慢（~30s）        | 低   | 全局安装 `claude-agent-acp` 命令，或调整 AbstractAcpClient 启动超时 |
| Claude Agent SDK 版本不兼容    | 低   | 指定 npm 包版本（`@agentclientprotocol/claude-agent-acp@0.39.0`） |
| Node.js 环境缺失               | 中   | 部署文档明确要求 Node.js >= 18，或提供 Docker 镜像           |
| usage_update 非 ACP 强制字段    | 低   | `contextUsagePercentage` 默认 -1，解析失败不影响功能          |
| ESM 模块可能在旧 Node 上失败    | 低   | 已验证 Node.js 18 可用，Windows 建议 20+                     |

## 7. 修改清单

| 文件                                                                                      | 修改类型 | 行数 |
| ----------------------------------------------------------------------------------------- | -------- | ---- |
| `agent/AgentProviderType.java`                                                              | 新增枚举 | +2   |
| `agent/ClaudeAgentAcpProvider.java`                                                         | 新增文件 | ~45  |
| `agent/AgentProviderRouter.java`                                                            | 新增注册 | +1   |
| `AcpRobotParam.java` → `getAvatar()`                                                         | 新增分支 | +3   |
| `AcpRobotParam.java` → `isMemoryEnabled()`                                                   | 新增分支 | +4   |
| `configui/index.html` → `<select id="dProvider">`                                             | 新增选项 | +1   |
| `AbstractAcpClient.java` → `usage_update` 解析                                               | 新增逻辑 | ~5   |

**总计约 60 行代码，无新依赖。**

## 8. 测试步骤

1. 设置 `ANTHROPIC_API_KEY` 环境变量
2. 在 `acpConfig.json` 中配置一个 `CLAUDE_AGENT_ACP` robot
3. 启动 cmd-proxy，发送消息验证：
   - `initialize` 握手成功
   - `session/new` 创建成功
   - `session/prompt` 收到流式 `agent_message_chunk`
   - `session/load` 恢复历史会话
   - `session/cancel` 中断执行
4. 验证 `usage_update` 上下文使用量正确推送
5. 验证 MCP Server 配置正确注入
