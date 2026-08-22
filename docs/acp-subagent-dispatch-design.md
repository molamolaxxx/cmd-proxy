# ACP Sub-Agent 派发系统设计方案

## 1. 背景与目标

在单 Agent 调用过程中，经常出现跨工作区、跨技能、跨记忆的需求。例如：
- 主 Agent 在项目 A 工作时，需要调用项目 B 的专家 Agent 查询 API 文档
- 主 Agent 需要同时派发多个子 Agent 并行处理不同子任务（如同时搜索 + 写代码 + 查数据库）
- 主 Agent 需要将复杂任务分解，委托给具有不同 skills/MCP 工具/记忆的子 Agent

### 设计目标
1. 在 `acpConfig.json` 的 robot 配置中声明 subAgent 列表
2. 主 Agent 上下文中注入子 Agent 的 `ability.md`，让 LLM 自主判断何时调用
3. 支持并行派发多个子 Agent，异步收集结果后同步回主 Agent
4. 通过 Listener 机制向用户实时反馈子 Agent 执行状态

## 2. 行业调研总结

### 2.1 主流多 Agent 编排模式

基于对 OpenAI Agents SDK、Claude Multi-Agent Workflows、LangGraph 等框架的调研，
当前业界主要有两种多 Agent 协作模式：

| 模式 | 描述 | 适用场景 |
|------|------|----------|
| **Agents-as-Tools（编排器-子代理）** | 主 Agent 将子 Agent 包装为 tool，通过 tool_call 触发 | 主 Agent 需要保持控制权，子任务结果需要汇总 |
| **Handoff（控制权转移）** | 主 Agent 将完整控制权交给子 Agent | 子 Agent 需要直接与用户交互 |

**我们选择 Agents-as-Tools 模式**，原因：
- 主 Agent 始终保持编排控制权，符合"主 Agent 派发"的语义
- 子 Agent 结果自然回流到主 Agent 上下文，便于汇总
- 支持并行 fan-out：多个子 Agent 可同时执行
- 与现有 AcpClient 架构（子进程 + JSON-RPC）天然契合

### 2.2 并行执行与结果同步方案

参考 Claude Multi-Agent Workflows 的 Parallel Fan-Out 模式和 Java CompletableFuture：

- **并行派发**：使用 `CompletableFuture.supplyAsync()` 为每个子 Agent 创建独立异步任务
- **结果聚合**：使用 `CompletableFuture.allOf()` 等待所有子 Agent 完成，或 `anyOf()` 取最快结果
- **超时控制**：每个子 Agent 独立超时，整体任务也有总超时
- **错误隔离**：单个子 Agent 失败不影响其他子 Agent，失败结果标记为 error

### 2.3 子 Agent 唤起方式

在现有架构中，子 Agent 的唤起本质上是创建一个临时的 `AbstractAcpClient` 子类实例（类似 `MemoryAcpClient`、`AbilityReflectionAcpClient`），
通过独立子进程与 kiro-cli 通信。这个模式已经在记忆提取和能力反思中验证过。

子 Agent 的关键差异在于：
- 需要加载目标 robot 的 MCP Server 配置（而非空配置）
- 需要注入目标 robot 的 skills 上下文（通过 workspacePath 自动加载）
- 需要支持流式输出（向用户实时反馈），而非仅同步阻塞

## 3. 整体架构

```
┌─────────────────────────────────────────────────────────────┐
│                        acpConfig.json                        │
│  {                                                           │
│    "robots": [                                               │
│      {                                                       │
│        "name": "主Agent",                                    │
│        "workDir": "/project-a",                              │
│        "subAgents": [                                        │
│          { "name": "搜索专家", "description": "..." },       │
│          { "name": "代码专家", "description": "..." }        │
│        ]                                                     │
│      },                                                      │
│      { "name": "搜索专家", "workDir": "/project-b", ... },   │
│      { "name": "代码专家", "workDir": "/project-c", ... }    │
│    ]                                                         │
│  }                                                           │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                     AcpClient (主 Agent)                     │
│                                                              │
│  sendPrompt() 时注入:                                        │
│  ┌──────────────────────────────────────────────────┐       │
│  │ [Sub-Agent Abilities]                             │       │
│  │ ## 搜索专家                                       │       │
│  │ 核心能力: Web搜索、文档检索...                     │       │
│  │ ## 代码专家                                       │       │
│  │ 核心能力: 代码生成、重构...                        │       │
│  │                                                    │       │
│  │ 当你需要以上能力时，使用 dispatch_subagent 工具     │       │
│  └──────────────────────────────────────────────────┘       │
│                              │                               │
│              LLM 决策: 需要调用子 Agent                       │
│                              │                               │
│                              ▼                               │
│  ┌──────────────────────────────────────────────────┐       │
│  │ tool_call: dispatch_subagent                      │       │
│  │ {                                                  │       │
│  │   "tasks": [                                       │       │
│  │     {"agent": "搜索专家", "prompt": "查找..."},    │       │
│  │     {"agent": "代码专家", "prompt": "实现..."}     │       │
│  │   ]                                                │       │
│  │ }                                                  │       │
│  └──────────────────────────────────────────────────┘       │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                  SubAgentDispatcher                           │
│                                                              │
│  CompletableFuture.supplyAsync() × N                         │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐         │
│  │ SubAgent-1   │  │ SubAgent-2   │  │ SubAgent-N   │         │
│  │ (搜索专家)   │  │ (代码专家)   │  │ (...)        │         │
│  │              │  │              │  │              │         │
│  │ SubAgentAcp  │  │ SubAgentAcp  │  │ SubAgentAcp  │         │
│  │ Client       │  │ Client       │  │ Client       │         │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘         │
│         │                 │                 │                │
│         ▼                 ▼                 ▼                │
│  ┌─────────────────────────────────────────────────┐        │
│  │         CompletableFuture.allOf() 聚合           │        │
│  └─────────────────────────────────────────────────┘        │
│                              │                               │
│                              ▼                               │
│                    SubAgentResult[]                           │
│                    回注主 Agent 上下文                         │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                  AcpResponseListener                         │
│                                                              │
│  实时向用户推送:                                              │
│  📋 正在派发 2 个子 Agent...                                  │
│  🔍 [搜索专家] 正在执行...                                    │
│  💻 [代码专家] 正在执行...                                    │
│  ✅ [搜索专家] 完成 (耗时 3.2s)                               │
│  ✅ [代码专家] 完成 (耗时 5.1s)                               │
│  📊 所有子 Agent 已完成，正在汇总结果...                       │
└─────────────────────────────────────────────────────────────┘
```

## 4. 详细设计

### 4.1 配置层：acpConfig.json 扩展

在每个 robot 配置下新增 `subAgents` 字段：

```json
{
  "robots": [
    {
      "name": "全栈助手",
      "signature": "全栈开发助手，可协调多个专家",
      "workDir": "/home/user/project-main",
      "agentProvider": "KIRO_CLI",
      "memory": { "enabled": true },
      "subAgents": [
        {
          "name": "搜索专家",
          "description": "可选的简短描述，覆盖 ability.md 的摘要"
        },
        {
          "name": "代码审查专家",
          "description": null
        }
      ]
    },
    {
      "name": "搜索专家",
      "signature": "专注于 Web 搜索和文档检索",
      "workDir": "/home/user/project-search",
      "agentProvider": "KIRO_CLI"
    },
    {
      "name": "代码审查专家",
      "signature": "专注于代码审查和重构建议",
      "workDir": "/home/user/project-review",
      "agentProvider": "KIRO_CLI"
    }
  ],
  "chatterIds": ["user1"]
}
```

**设计要点**：
- `subAgents[].name` 必须引用同一 `robots` 数组中已定义的 robot 名称
- `subAgents[].description` 可选，若为空则使用目标 robot 的 `ability.md` 摘要
- 不允许循环引用（A 的 subAgent 是 B，B 的 subAgent 是 A）
- 子 Agent 本身也可以有自己的 subAgents（支持多级委托，但建议不超过 2 层）

### 4.2 模型层

#### 4.2.1 SubAgentRef — 子 Agent 引用配置

```java
package com.mola.cmd.proxy.app.acp.subagent.model;

/**
 * 子 Agent 引用配置，对应 acpConfig.json 中 robot.subAgents[] 的单个元素。
 */
public class SubAgentRef {
    /** 引用的 robot 名称，必须在 robots 数组中存在 */
    private String name;
    /** 可选描述，覆盖 ability.md 的摘要 */
    private String description;

    // getters/setters
}
```

#### 4.2.2 SubAgentTask — 单次派发任务

```java
package com.mola.cmd.proxy.app.acp.subagent.model;

/**
 * 主 Agent LLM 通过 tool_call 发出的单个子任务。
 */
public class SubAgentTask {
    /** 目标子 Agent 名称 */
    private String agent;
    /** 发送给子 Agent 的 prompt */
    private String prompt;
    /** 可选：期望的输出格式提示 */
    private String outputHint;

    // getters/setters
}
```

#### 4.2.3 SubAgentResult — 子 Agent 执行结果

```java
package com.mola.cmd.proxy.app.acp.subagent.model;

/**
 * 单个子 Agent 的执行结果。
 */
public class SubAgentResult {
    public enum Status { SUCCESS, ERROR, TIMEOUT, CANCELLED }

    private String agent;
    private Status status;
    private String response;
    private String errorMessage;
    private long durationMs;

    // getters/setters
}
```

### 4.3 AcpRobotParam 扩展

```java
// 在 AcpRobotParam 中新增字段
private List<SubAgentRef> subAgents;

public List<SubAgentRef> getSubAgents() { return subAgents; }
public void setSubAgents(List<SubAgentRef> subAgents) { this.subAgents = subAgents; }

public boolean hasSubAgents() {
    return subAgents != null && !subAgents.isEmpty();
}
```

### 4.4 核心组件：SubAgentDispatcher

这是整个子 Agent 派发系统的核心，负责并行创建子 Agent 进程、执行任务、收集结果。

```java
package com.mola.cmd.proxy.app.acp.subagent;

/**
 * 子 Agent 派发器，负责并行执行子 Agent 任务并聚合结果。
 *
 * 设计参考：
 * - Claude Multi-Agent Workflows 的 Parallel Fan-Out 模式
 * - OpenAI Agents SDK 的 Agents-as-Tools 模式
 *
 * 执行流程：
 * 1. 接收主 Agent 的 dispatch_subagent tool_call
 * 2. 解析 tasks 列表，校验目标 Agent 是否在 subAgents 配置中
 * 3. 为每个 task 创建 CompletableFuture，启动 SubAgentAcpClient
 * 4. 通过 listener 实时向用户推送执行状态
 * 5. allOf() 等待所有任务完成（或超时）
 * 6. 聚合结果，格式化后返回给主 Agent
 */
public class SubAgentDispatcher {

    private final Map<String, AcpRobotParam> robotRegistry;  // name -> robot 配置
    private final AcpResponseListener listener;               // 用于向用户推送状态
    private final int defaultTimeoutSeconds;
    private final ExecutorService dispatchPool;

    public SubAgentDispatcher(Map<String, AcpRobotParam> robotRegistry,
                              AcpResponseListener listener,
                              int defaultTimeoutSeconds) {
        this.robotRegistry = robotRegistry;
        this.listener = listener;
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
        this.dispatchPool = new ThreadPoolExecutor(
            2, 5, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(10),
            r -> { Thread t = new Thread(r, "subagent-dispatch"); t.setDaemon(true); return t; }
        );
    }

    /**
     * 并行派发子 Agent 任务。
     *
     * @param tasks          主 Agent LLM 发出的任务列表
     * @param allowedAgents  当前 robot 配置的 subAgents 名称集合（白名单）
     * @param callerWorkspace 主 Agent 的 workspacePath（用于上下文传递）
     * @return 聚合后的结果列表
     */
    public List<SubAgentResult> dispatch(List<SubAgentTask> tasks,
                                         Set<String> allowedAgents,
                                         String callerWorkspace) {
        // 1. 校验：只允许调用已配置的子 Agent
        for (SubAgentTask task : tasks) {
            if (!allowedAgents.contains(task.getAgent())) {
                throw new IllegalArgumentException(
                    "子 Agent '" + task.getAgent() + "' 未在 subAgents 配置中");
            }
        }

        // 2. 通知用户
        listener.onMessage(String.format(
            "\n📋 正在派发 %d 个子 Agent 任务...\n", tasks.size()));

        // 3. 并行执行
        List<CompletableFuture<SubAgentResult>> futures = new ArrayList<>();
        for (SubAgentTask task : tasks) {
            CompletableFuture<SubAgentResult> future = CompletableFuture.supplyAsync(
                () -> executeSubAgent(task, callerWorkspace), dispatchPool);
            futures.add(future);
        }

        // 4. 等待所有完成（带总超时）
        CompletableFuture<Void> allDone = CompletableFuture.allOf(
            futures.toArray(new CompletableFuture[0]));
        try {
            allDone.get(defaultTimeoutSeconds * 2L, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            listener.onMessage("\n⏰ 部分子 Agent 超时，返回已完成的结果\n");
        } catch (Exception e) {
            listener.onMessage("\n⚠️ 子 Agent 执行异常: " + e.getMessage() + "\n");
        }

        // 5. 收集结果
        List<SubAgentResult> results = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            CompletableFuture<SubAgentResult> f = futures.get(i);
            SubAgentTask task = tasks.get(i);
            if (f.isDone() && !f.isCompletedExceptionally()) {
                results.add(f.join());
            } else {
                SubAgentResult errorResult = new SubAgentResult();
                errorResult.setAgent(task.getAgent());
                errorResult.setStatus(SubAgentResult.Status.TIMEOUT);
                errorResult.setErrorMessage("执行超时或异常");
                results.add(errorResult);
            }
        }

        listener.onMessage(String.format(
            "\n📊 所有子 Agent 已完成，共 %d 个结果\n", results.size()));
        return results;
    }

    /**
     * 执行单个子 Agent 任务（在独立线程中运行）。
     */
    private SubAgentResult executeSubAgent(SubAgentTask task, String callerWorkspace) {
        long startTime = System.currentTimeMillis();
        SubAgentResult result = new SubAgentResult();
        result.setAgent(task.getAgent());

        AcpRobotParam targetRobot = robotRegistry.get(task.getAgent());
        if (targetRobot == null) {
            result.setStatus(SubAgentResult.Status.ERROR);
            result.setErrorMessage("目标 robot 配置不存在: " + task.getAgent());
            return result;
        }

        // 确定子 Agent 的工作目录：优先用目标 robot 的 workDir，否则用主 Agent 的
        String workDir = (targetRobot.getWorkDir() != null && !targetRobot.getWorkDir().isEmpty())
            ? targetRobot.getWorkDir() : callerWorkspace;

        listener.onMessage(String.format("🚀 [%s] 开始执行...\n", task.getAgent()));

        String groupId = "subagent__" + task.getAgent().hashCode()
            + "__" + System.currentTimeMillis();

        try (SubAgentAcpClient client = new SubAgentAcpClient(
                workDir, groupId, defaultTimeoutSeconds,
                targetRobot.getAgentProvider())) {
            client.start();
            String response = client.sendPromptSync(task.getPrompt());

            result.setStatus(SubAgentResult.Status.SUCCESS);
            result.setResponse(response);
            result.setDurationMs(System.currentTimeMillis() - startTime);

            listener.onMessage(String.format(
                "✅ [%s] 完成 (耗时 %.1fs)\n",
                task.getAgent(), result.getDurationMs() / 1000.0));

        } catch (Exception e) {
            result.setStatus(SubAgentResult.Status.ERROR);
            result.setErrorMessage(e.getMessage());
            result.setDurationMs(System.currentTimeMillis() - startTime);

            listener.onMessage(String.format(
                "❌ [%s] 失败: %s\n", task.getAgent(), e.getMessage()));
        }
        return result;
    }
}
```

### 4.5 SubAgentAcpClient — 子 Agent 专用 Client

继承 `AbstractAcpClient`，与 `MemoryAcpClient` 类似但有关键差异：
- **加载目标 robot 的 MCP Server**（而非空配置）
- 同步阻塞模式，返回完整响应
- 自动处理 permission 请求

```java
package com.mola.cmd.proxy.app.acp.subagent;

/**
 * 子 Agent 专用 ACP Client。
 *
 * 与 MemoryAcpClient 的差异：
 * - 可选加载 MCP Server（子 Agent 可能需要工具）
 * - workspacePath 指向目标 robot 的工作目录（kiro-cli 自动加载该目录的 skills）
 */
public class SubAgentAcpClient extends AbstractAcpClient {

    private final int timeoutSeconds;
    private final List<Path> mcpConfigPaths;
    private final ExecutorService executor;

    public SubAgentAcpClient(String workspacePath, String groupId,
                             int timeoutSeconds, String agentProviderType) {
        super(AgentProviderRouter.getInstance().resolve(agentProviderType),
              workspacePath, groupId);
        this.timeoutSeconds = timeoutSeconds;
        this.mcpConfigPaths = agentProvider.getMcpConfigPaths(workspacePath);
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "subagent-worker-" + groupId);
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    protected void createSession() throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("cwd", workspacePath);

        // 加载目标 robot 的 MCP Server 配置
        JsonArray mcpServers = loadMcpServers();
        params.add("mcpServers", mcpServers);

        JsonObject response = sendRequest("session/new", params);
        JsonObject result = response.getAsJsonObject("result");
        setSessionId(result.get("sessionId").getAsString());
    }

    /**
     * 同步发送 prompt，阻塞等待完整响应。
     * 流式读取逻辑与 MemoryAcpClient.doSendPrompt() 一致。
     */
    public String sendPromptSync(String promptText) throws IOException {
        // 实现与 MemoryAcpClient.sendPromptSync() 相同
        // 使用 Future + timeout 控制
    }

    private JsonArray loadMcpServers() {
        // 复用 AcpClient 的 MCP 配置加载逻辑
        // 从 mcpConfigPaths 读取并合并
    }
}
```

### 4.6 上下文注入：SubAgentContextInjector

负责在主 Agent 的 prompt 中注入子 Agent 的能力描述，让 LLM 知道何时该调用子 Agent。

```java
package com.mola.cmd.proxy.app.acp.subagent;

/**
 * 子 Agent 上下文注入器。
 *
 * 在主 Agent 的 sendPrompt() 中，将所有可用子 Agent 的能力描述
 * 注入到 prompt 前缀中，让 LLM 自主判断何时需要委托子 Agent。
 *
 * 能力描述来源（优先级从高到低）：
 * 1. subAgents[].description（配置中的手动描述）
 * 2. 目标 robot 的 ability.md（AbilityReflectionService 生成）
 * 3. 目标 robot 的 signature（兜底）
 */
public class SubAgentContextInjector {

    private static final String ABILITY_BASE_DIR =
        System.getProperty("user.home") + "/.cmd-proxy/ability";

    /**
     * 构建子 Agent 能力描述文本，注入到主 Agent prompt 中。
     *
     * @param subAgentRefs  当前 robot 配置的 subAgents 列表
     * @param robotRegistry 全局 robot 注册表 (name -> AcpRobotParam)
     * @return 格式化的能力描述文本，为空时返回 ""
     */
    public String buildSubAgentContext(List<SubAgentRef> subAgentRefs,
                                       Map<String, AcpRobotParam> robotRegistry) {
        if (subAgentRefs == null || subAgentRefs.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("\n[Available Sub-Agents]\n");
        sb.append("你可以通过 dispatch_subagent 指令将任务委托给以下子 Agent。\n");
        sb.append("当你判断某个子任务更适合由专业子 Agent 处理时，请使用此能力。\n");
        sb.append("你可以同时派发多个子 Agent 并行执行。\n\n");

        for (SubAgentRef ref : subAgentRefs) {
            AcpRobotParam targetRobot = robotRegistry.get(ref.getName());
            if (targetRobot == null) continue;

            sb.append("### ").append(ref.getName()).append("\n");

            // 优先使用配置中的 description
            String desc = ref.getDescription();
            if (desc == null || desc.isEmpty()) {
                // 尝试读取 ability.md
                desc = loadAbilityMd(ref.getName());
            }
            if (desc == null || desc.isEmpty()) {
                // 兜底使用 signature
                desc = targetRobot.getSignature();
            }

            sb.append(desc).append("\n\n");
        }

        sb.append("### 调用格式\n");
        sb.append("当你需要调用子 Agent 时，请在回复中使用以下 JSON 格式：\n");
        sb.append("```json\n");
        sb.append("{\n");
        sb.append("  \"action\": \"dispatch_subagent\",\n");
        sb.append("  \"tasks\": [\n");
        sb.append("    {\"agent\": \"子Agent名称\", \"prompt\": \"具体任务描述\"},\n");
        sb.append("    {\"agent\": \"另一个子Agent\", \"prompt\": \"另一个任务\"}\n");
        sb.append("  ]\n");
        sb.append("}\n");
        sb.append("```\n");

        return sb.toString();
    }

    /**
     * 读取目标 robot 的 ability.md 文件。
     * 路径: ~/.cmd-proxy/ability/{robot-name-hash}/ability.md
     */
    private String loadAbilityMd(String robotName) {
        // 复用 AbilityReflectionService 的路径计算逻辑
        // 读取 ability.md 内容，截取前 500 字符作为摘要
    }
}
```

**注入时机**：在 `AcpClient.sendPrompt()` 中，与 memoryContext 和 timeContext 一起注入：

```java
// AcpClient.sendPrompt() 中的改动
String subAgentContext = "";
if (robotParam != null && robotParam.hasSubAgents()) {
    subAgentContext = subAgentContextInjector.buildSubAgentContext(
        robotParam.getSubAgents(), globalRobotRegistry);
}

String fullText = subAgentContext + memoryContext + timeContext + userInput;
```

### 4.7 主 Agent 如何唤起子 Agent

主 Agent 唤起子 Agent 有两种可行方案，我们选择**方案 B**：

#### 方案 A：MCP Tool 方式（不推荐）
将 `dispatch_subagent` 注册为 MCP Tool，LLM 通过标准 tool_call 调用。
- 优点：与 MCP 生态一致
- 缺点：需要额外部署 MCP Server，增加复杂度；子 Agent 本身不是外部工具

#### 方案 B：Prompt 约定 + 输出解析（推荐）✅
在 prompt 中约定 JSON 格式，主 Agent 在解析 LLM 输出时检测 `dispatch_subagent` 指令。

**执行流程**：

```
用户输入 → 主 Agent LLM 思考 → 输出包含 dispatch_subagent JSON
                                        │
                                        ▼
                              AcpClient 检测到指令
                                        │
                                        ▼
                              SubAgentDispatcher.dispatch()
                              (并行创建 SubAgentAcpClient)
                                        │
                                        ▼
                              收集所有子 Agent 结果
                                        │
                                        ▼
                              将结果注入上下文，触发主 Agent 第二轮思考
                                        │
                                        ▼
                              主 Agent 汇总输出最终回答
```

**在 AcpClient 的流式读取循环中拦截**：

```java
// AcpClient.sendPrompt() 的 session/update 处理中
if ("agent_message_chunk".equals(updateType)) {
    String text = content.get("text").getAsString();
    fullResponse.append(text);

    // 检测子 Agent 派发指令
    String pendingDispatch = detectDispatchCommand(fullResponse.toString());
    if (pendingDispatch != null) {
        // 解析任务列表
        List<SubAgentTask> tasks = parseDispatchTasks(pendingDispatch);
        // 并行执行
        List<SubAgentResult> results = dispatcher.dispatch(
            tasks, allowedSubAgentNames, workspacePath);
        // 将结果注入上下文，触发下一轮 prompt
        String resultContext = formatResultsForContext(results);
        // 自动发送第二轮 prompt，让主 Agent 汇总
        sendFollowUpPrompt(resultContext);
    }

    listener.onMessage(text);
}
```

**为什么选方案 B**：
1. 零额外基础设施：不需要部署 MCP Server
2. 与现有架构一致：MemoryExtractor 也是通过 prompt 约定 JSON 格式解析结果
3. 灵活性高：LLM 可以自由决定是否调用、调用几个、如何组合
4. 实现简单：只需在输出解析层增加一个检测逻辑

### 4.8 Listener 交互设计

子 Agent 执行过程中，用户需要实时了解进度。通过现有的 `AcpResponseListener` 机制推送状态。

#### 4.8.1 新增 Listener 回调方法

```java
// AcpResponseListener 接口扩展
public interface AcpResponseListener {
    // ... 现有方法 ...

    /**
     * 子 Agent 派发事件回调。
     *
     * @param event 派发事件类型
     * @param agentName 子 Agent 名称
     * @param detail 事件详情（如进度、结果摘要）
     */
    default void onSubAgentEvent(SubAgentEvent event, String agentName, String detail) {
        // 默认空实现，向后兼容
    }
}
```

#### 4.8.2 SubAgentEvent 枚举

```java
package com.mola.cmd.proxy.app.acp.subagent.model;

public enum SubAgentEvent {
    /** 开始派发（通知用户即将执行子 Agent） */
    DISPATCH_START,
    /** 单个子 Agent 开始执行 */
    AGENT_START,
    /** 单个子 Agent 输出中间消息（可选，用于流式转发） */
    AGENT_MESSAGE,
    /** 单个子 Agent 执行完成 */
    AGENT_COMPLETE,
    /** 单个子 Agent 执行失败 */
    AGENT_ERROR,
    /** 所有子 Agent 执行完成，开始汇总 */
    DISPATCH_COMPLETE
}
```

#### 4.8.3 DefaultAcpResponseListener 扩展

```java
// DefaultAcpResponseListener 中新增
@Override
public void onSubAgentEvent(SubAgentEvent event, String agentName, String detail) {
    String emoji;
    switch (event) {
        case DISPATCH_START:   emoji = "📋"; break;
        case AGENT_START:      emoji = "🚀"; break;
        case AGENT_MESSAGE:    emoji = "💬"; break;
        case AGENT_COMPLETE:   emoji = "✅"; break;
        case AGENT_ERROR:      emoji = "❌"; break;
        case DISPATCH_COMPLETE: emoji = "📊"; break;
        default: emoji = "ℹ️";
    }

    String content;
    switch (event) {
        case DISPATCH_START:
            content = String.format(
                "<details class=\"subagent-dispatch\" open>" +
                "<summary>%s 子 Agent 派发</summary>" +
                "<div class=\"subagent-body\">\n%s\n</div></details>\n",
                emoji, detail);
            break;
        case AGENT_COMPLETE:
        case AGENT_ERROR:
            content = String.format(
                "<details class=\"subagent-result\">" +
                "<summary>%s [%s] %s</summary>" +
                "<div class=\"subagent-body\">\n\n```\n%s\n```\n\n</div></details>\n",
                emoji, agentName,
                event == SubAgentEvent.AGENT_COMPLETE ? "完成" : "失败",
                detail.length() > 500 ? detail.substring(0, 500) + "..." : detail);
            break;
        default:
            content = String.format("%s [%s] %s\n", emoji, agentName, detail);
    }

    sendContent(content, false);
}
```

#### 4.8.4 用户交互流程

```
用户: "帮我搜索 Spring Boot 3.x 的新特性，同时审查一下 UserService.java 的代码质量"

主 Agent (思考): 这个任务涉及两个独立子任务，可以并行派发
  - 搜索任务 → 搜索专家
  - 代码审查 → 代码审查专家

主 Agent (输出):
  我来帮你同时处理这两个任务。

  📋 正在派发 2 个子 Agent 任务...
  🚀 [搜索专家] 开始执行: 搜索 Spring Boot 3.x 新特性
  🚀 [代码审查专家] 开始执行: 审查 UserService.java

  ... (并行执行中，用户可以看到实时状态) ...

  ✅ [搜索专家] 完成 (耗时 3.2s)
  ✅ [代码审查专家] 完成 (耗时 5.1s)
  📊 所有子 Agent 已完成，正在汇总结果...

主 Agent (汇总):
  ## Spring Boot 3.x 新特性
  (搜索专家的结果汇总)

  ## UserService.java 代码审查
  (代码审查专家的结果汇总)

  综合来看，建议你...
```

### 4.9 结果同步回主 Agent

子 Agent 结果需要回注到主 Agent 的上下文中，触发第二轮 LLM 推理进行汇总。

#### 方案：自动追加 Follow-Up Prompt

```java
/**
 * 将子 Agent 结果格式化为 follow-up prompt，注入主 Agent 上下文。
 */
public class SubAgentResultFormatter {

    /**
     * 将结果列表格式化为主 Agent 可理解的上下文文本。
     */
    public static String format(List<SubAgentResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Sub-Agent Results]\n");
        sb.append("以下是子 Agent 的执行结果，请基于这些结果为用户提供综合回答。\n\n");

        for (SubAgentResult r : results) {
            sb.append("### ").append(r.getAgent()).append("\n");
            sb.append("状态: ").append(r.getStatus()).append("\n");
            if (r.getStatus() == SubAgentResult.Status.SUCCESS) {
                sb.append("结果:\n").append(r.getResponse()).append("\n");
            } else {
                sb.append("错误: ").append(r.getErrorMessage()).append("\n");
            }
            sb.append("耗时: ").append(r.getDurationMs()).append("ms\n\n");
        }

        sb.append("请综合以上子 Agent 的结果，为用户提供完整、连贯的回答。\n");
        return sb.toString();
    }
}
```

**在 AcpClient 中的集成**：

```java
// 检测到 dispatch 指令后
List<SubAgentResult> results = dispatcher.dispatch(tasks, allowedNames, workspacePath);
String resultContext = SubAgentResultFormatter.format(results);

// 自动发送 follow-up prompt（复用现有的 sendPrompt 逻辑）
// 这会触发主 Agent 的第二轮推理，汇总子 Agent 结果
sendPrompt(resultContext, null, listener);
```

## 5. 包结构

```
com.mola.cmd.proxy.app.acp.subagent/
├── SubAgentDispatcher.java          // 核心：并行派发与结果聚合
├── SubAgentAcpClient.java           // 子 Agent 专用 ACP Client
├── SubAgentContextInjector.java     // 上下文注入（ability.md 读取）
├── SubAgentResultFormatter.java     // 结果格式化
└── model/
    ├── SubAgentRef.java             // 配置模型：子 Agent 引用
    ├── SubAgentTask.java            // 运行时模型：派发任务
    ├── SubAgentResult.java          // 运行时模型：执行结果
    └── SubAgentEvent.java           // 事件枚举
```

## 6. AcpProxy 初始化改动

```kotlin
// AcpProxy.kt 中新增

/** 全局 robot 注册表，name -> AcpRobotParam */
private val globalRobotRegistry = ConcurrentHashMap<String, AcpRobotParam>()

fun start(...) {
    // 在冷加载循环之前，先构建全局 robot 注册表
    for ((groupId, robot) in groupRobotMap) {
        if (robot != null && robot.name.isNotBlank()) {
            globalRobotRegistry[robot.name] = robot
        }
    }

    // 冷加载循环中，为有 subAgents 的 client 初始化 dispatcher
    for (groupId in cmdGroupList) {
        val robot = groupRobotMap[groupId]
        val client = registry.getClient(groupId) ?: continue

        // ... 现有的 memory 和 ability 初始化 ...

        // 初始化子 Agent 派发器
        initSubAgentDispatcher(groupId, client, robot)
    }
}

private fun initSubAgentDispatcher(groupId: String, client: AcpClient, robot: AcpRobotParam?) {
    if (robot == null || !robot.hasSubAgents()) return

    // 校验子 Agent 引用的有效性
    for (ref in robot.subAgents) {
        if (!globalRobotRegistry.containsKey(ref.name)) {
            log.warn("robot '{}' 引用了不存在的子 Agent '{}'", robot.name, ref.name)
        }
    }

    val dispatcher = SubAgentDispatcher(
        globalRobotRegistry,
        client.globalListener,
        120  // 默认超时
    )
    client.setSubAgentDispatcher(dispatcher)

    // 注入子 Agent 上下文
    val injector = SubAgentContextInjector()
    client.setSubAgentContextInjector(injector)

    log.info("子 Agent 派发器初始化完成, groupId={}, subAgents={}",
        groupId, robot.subAgents.map { it.name })
}
```

## 7. 安全与约束

### 7.1 循环引用检测

在 `AcpProxy.start()` 初始化时进行拓扑排序检测：

```java
/**
 * 检测 subAgent 配置中的循环引用。
 * 使用 DFS 检测有向图中的环。
 */
public static void validateNoCircularRef(Map<String, AcpRobotParam> registry) {
    // DFS 遍历 robot -> subAgents 关系图
    // 发现环时抛出 IllegalStateException
}
```

### 7.2 深度限制

- 子 Agent 派发深度默认限制为 2 层（主 Agent → 子 Agent → 子子 Agent）
- 通过在 SubAgentAcpClient 的 prompt 中不注入 subAgent 上下文来实现
- 或在 SubAgentDispatcher 中传入 depth 参数，depth >= maxDepth 时拒绝派发

### 7.3 并发控制

- `dispatchPool` 线程池大小限制为 5，防止同时启动过多子进程
- 每个子 Agent 独立超时（默认 120s），整体超时为 2 × 单个超时
- 队列满时拒绝新任务，返回错误

### 7.4 资源清理

- SubAgentAcpClient 使用 try-with-resources 确保子进程关闭
- CompletableFuture 超时后，通过 `future.cancel(true)` 中断执行
- SubAgentDispatcher 在 AcpClient.close() 时关闭线程池

## 8. 与现有系统的集成点

| 现有组件 | 改动 | 说明 |
|---------|------|------|
| `AcpRobotParam` | 新增 `subAgents` 字段 | 配置层扩展 |
| `AcpClient` | 新增 `dispatcher`、`contextInjector` 字段 | 运行时注入 |
| `AcpClient.sendPrompt()` | 注入 subAgent 上下文 + 输出解析 | 核心集成点 |
| `AcpResponseListener` | 新增 `onSubAgentEvent()` 默认方法 | 向后兼容 |
| `DefaultAcpResponseListener` | 实现 `onSubAgentEvent()` | UI 展示 |
| `AcpProxy.start()` | 构建 robotRegistry + 初始化 dispatcher | 启动流程 |
| `Main.kt` | 无改动 | acpConfig.json 解析由 fastjson 自动处理新字段 |
| `AbilityReflectionService` | 无改动 | ability.md 被 SubAgentContextInjector 读取 |
| `MemoryManager` | 无改动 | 子 Agent 的记忆独立于主 Agent |

## 9. 实现优先级

| 阶段 | 内容 | 预估工作量 |
|------|------|-----------|
| P0 | 模型层（SubAgentRef/Task/Result）+ AcpRobotParam 扩展 | 0.5 天 |
| P0 | SubAgentAcpClient（复用 MemoryAcpClient 模式） | 1 天 |
| P0 | SubAgentDispatcher（并行执行 + 结果聚合） | 1.5 天 |
| P1 | SubAgentContextInjector（ability.md 读取 + prompt 注入） | 1 天 |
| P1 | AcpClient.sendPrompt() 集成（输出解析 + follow-up） | 1.5 天 |
| P1 | Listener 扩展 + DefaultAcpResponseListener 实现 | 0.5 天 |
| P2 | AcpProxy 初始化 + 循环引用检测 | 0.5 天 |
| P2 | 深度限制 + 并发控制 + 资源清理 | 0.5 天 |

总计约 **7 天**。
