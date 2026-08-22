# ACP 记忆系统技术方案

## 1. 设计目标

基于 [ACP 记忆系统设计文档](./acp-memory-system-design.md)，本方案聚焦以下工程目标：

- 高内聚低耦合：记忆模块独立成包 `memory`，不侵入现有 `acpclient` 包
- AcpClient 抽象提炼：主 Client 和记忆 Client 继承同一抽象基类，复用通用协议逻辑
- 交互简单：主 Client 与记忆模块仅通过 `MemoryManager` 一个门面交互

---

## 2. AcpClient 抽象重构

### 2.1 问题分析

当前 `AcpClient` 是一个 400+ 行的大类，包含：
- 子进程管理（startProcess）
- ACP 协议通信（initialize, createSession, sendRequest, sendJson）
- MCP 配置加载（loadMcpServersFromConfigs）
- 业务逻辑（sendPrompt 流式读取、图片处理、会话历史管理）

记忆子 Client 需要复用协议通信层，但不需要 MCP 加载、图片处理、流式回调等能力。

### 2.2 抽象类设计

```
                    ┌─────────────────────────┐
                    │   AbstractAcpClient      │
                    │─────────────────────────│
                    │ # process, writer, reader│
                    │ # sessionId, state       │
                    │ # command, args, groupId  │
                    │ # workspacePath           │
                    │─────────────────────────│
                    │ + start()                │
                    │ + close()                │
                    │ # startProcess()         │
                    │ # initialize()           │
                    │ # sendRequest()          │
                    │ # buildRequest()         │
                    │ # sendJson()             │
                    │ # createSession()  ←abstract│
                    │ # onSessionCreated()     │
                    └────────────┬────────────┘
                                 │
                    ┌────────────┴────────────┐
                    │                         │
          ┌─────────▼──────────┐   ┌──────────▼──────────┐
          │    AcpClient       │   │  MemoryAcpClient    │
          │   (主 Client)       │   │  (记忆提取专用)      │
          │────────────────────│   │─────────────────────│
          │ MCP 配置加载        │   │ 无 MCP              │
          │ 流式 prompt 读取    │   │ 同步 prompt 读取     │
          │ 图片处理            │   │ 纯文本交互           │
          │ 会话历史管理        │   │ 无历史管理           │
          │ AcpResponseListener│   │ 返回 String 结果     │
          └────────────────────┘   └─────────────────────┘
```

### 2.3 AbstractAcpClient 提炼的方法

从现有 `AcpClient` 中提取到抽象基类的通用方法：

| 方法 | 可见性 | 说明 |
|------|--------|------|
| `start()` | public | 模板方法：startProcess → initialize → createSession → onSessionCreated |
| `close()` | public | 关闭子进程、writer、reader，子类可 override 扩展 |
| `startProcess()` | protected | 启动子进程，配置 PATH 环境变量 |
| `initialize()` | protected | ACP initialize 协议握手 |
| `sendRequest(method, params)` | protected | 同步发送 JSON-RPC request 并等待 response |
| `buildRequest(method, params)` | protected | 构建 JSON-RPC request 对象 |
| `sendJson(json)` | protected | 底层 JSON 写入 |
| `createSession()` | protected abstract | 子类实现各自的 session/new 逻辑 |
| `getState() / getSessionId()` 等 | public | getter 方法 |

### 2.4 子类职责划分

**AcpClient（主 Client）**：
- `createSession()`: 加载 MCP 配置，传入 mcpServers 参数
- `sendPrompt()`: 流式读取 session/update，回调 AcpResponseListener
- `send()`: 异步提交到 executor，管理图片和会话历史
- `cancel()`: 发送 session/cancel notification
- 持有 `ConversationHistoryManager`、`AcpResponseListener`

**MemoryAcpClient（记忆 Client）**：
- `createSession()`: 不传 mcpServers（空数组），快速创建 session
- `sendPromptSync(prompt)`: 同步发送 prompt，阻塞等待完整响应，返回 `String`
- 无图片处理、无会话历史、无 listener 回调
- 内置超时控制（默认 30s）

---

## 3. 记忆模块包结构

新建独立包 `com.mola.cmd.proxy.app.memory`，与 `acpclient` 包平级：

```
com.mola.cmd.proxy.app/
├── acpclient/                          # 现有包（重构）
│   ├── AbstractAcpClient.java          # 新增：抽象基类
│   ├── AcpClient.java                  # 修改：继承 AbstractAcpClient
│   ├── AcpClientRegistry.java          # 小改：支持获取 workspacePath
│   ├── AcpResponseListener.java        # 不变
│   ├── ContextMessage.java             # 不变
│   ├── ConversationHistoryManager.java  # 小改：增加 turn 计数回调
│   └── DefaultAcpResponseListener.java  # 不变
│
├── memory/                             # 新增：记忆模块独立包
│   ├── MemoryManager.java              # 门面类，主 Client 唯一交互入口
│   ├── MemoryAcpClient.java            # 记忆提取专用子 Client
│   ├── MemoryExtractor.java            # 封装子 Client 调用 + prompt 构建
│   ├── MemoryLoader.java               # 读取索引，构建注入 prompt 的概要文本
│   ├── MemoryFileStore.java            # 文件读写层（索引 + 明细 + 归档）
│   ├── model/
│   │   ├── MemoryIndex.java            # 索引文件 POJO
│   │   ├── MemoryEntry.java            # 单条记忆 POJO
│   │   └── MemoryConfig.java           # 配置 POJO
│   └── prompt/
│       └── MemoryPromptTemplate.java   # 提取 prompt 模板（给子 Client 的指令）
```

### 3.1 包间依赖关系

```
memory 包 ──依赖──▶ acpclient 包（使用 AbstractAcpClient、ConversationHistoryManager）
acpclient 包 ──依赖──▶ memory 包（仅 AcpClient 依赖 MemoryManager）

具体依赖点：
  AcpClient → MemoryManager（注入概要 + 触发提取）
  MemoryManager → MemoryExtractor → MemoryAcpClient（继承 AbstractAcpClient）
  MemoryManager → MemoryLoader → MemoryFileStore
  MemoryManager → MemoryFileStore
```

为避免循环依赖，`AcpClient` 仅通过 `MemoryManager` 的接口交互，不直接引用 memory 包内部类。

---

## 4. 核心类设计

### 4.1 MemoryManager — 门面类

主 Client 与记忆模块的唯一交互入口。对外暴露 3 个方法，保持交互极简：

```java
public class MemoryManager {

    private final MemoryConfig config;
    private final MemoryExtractor extractor;
    private final MemoryLoader loader;
    private final MemoryFileStore fileStore;

    public MemoryManager(MemoryConfig config, String command, String[] args) {
        this.config = config;
        this.fileStore = new MemoryFileStore(config.getBaseDir());
        this.loader = new MemoryLoader(fileStore, config);
        this.extractor = new MemoryExtractor(config, command, args, fileStore);
    }

    /**
     * 【读取】构建记忆概要文本，注入到 prompt 前面。
     * 主 Client 在 sendPrompt() 前调用。
     *
     * @param workspacePath 当前工作目录，用于定位项目记忆
     * @return 概要文本，无记忆时返回空字符串
     */
    public String buildMemoryPrompt(String workspacePath) {
        if (!config.isEnabled()) return "";
        return loader.buildMemoryPrompt(workspacePath);
    }

    /**
     * 【写入】异步触发记忆提取。
     * 主 Client 在 session 结束或每 N 轮时调用。
     *
     * @param workspacePath 当前工作目录
     * @param history       当前 session 的完整对话历史
     */
    public void extractAsync(String workspacePath, List<ContextMessage> history) {
        if (!config.isEnabled() || history.isEmpty()) return;
        CompletableFuture.runAsync(() -> {
            try {
                extractor.extract(workspacePath, history);
            } catch (Exception e) {
                logger.error("记忆提取失败", e);
            }
        });
    }

    /**
     * 【管理】删除指定记忆。
     * 用户通过命令"忘记xxx"时调用。
     *
     * @param workspacePath 当前工作目录
     * @param memoryId      记忆 ID
     */
    public boolean deleteMemory(String workspacePath, String memoryId) {
        return fileStore.deleteMemory(workspacePath, memoryId);
    }

    public void shutdown() {
        extractor.shutdown();
    }
}
```

### 4.2 MemoryAcpClient — 记忆提取专用子 Client

继承 `AbstractAcpClient`，专为记忆提取场景设计：

```java
public class MemoryAcpClient extends AbstractAcpClient {

    private final int timeoutSeconds;

    public MemoryAcpClient(String command, String[] args,
                           String workspacePath, String groupId, int timeoutSeconds) {
        super(command, args, workspacePath, groupId);
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    protected void createSession() throws IOException {
        // 不加载任何 MCP Server，加速启动
        JsonObject params = new JsonObject();
        params.addProperty("cwd", getWorkspacePath());
        params.add("mcpServers", new JsonArray());  // 空数组

        JsonObject response = sendRequest("session/new", params);
        JsonObject result = response.getAsJsonObject("result");
        setSessionId(result.get("sessionId").getAsString());
    }

    /**
     * 同步发送 prompt，阻塞等待完整响应文本。
     * 内部处理 session/update 流，拼接 agent_message_chunk，
     * 遇到 prompt response 时返回完整文本。
     *
     * @param promptText 发送给子 Client 的完整 prompt
     * @return agent 的完整回答文本
     * @throws IOException 通信失败或超时
     */
    public String sendPromptSync(String promptText) throws IOException {
        // 构建 session/prompt request
        // 流式读取 session/update，拼接文本
        // 遇到 prompt response 返回
        // 超时控制通过 Future.get(timeout) 实现
    }
}
```

与主 `AcpClient` 的关键差异：

| 维度 | AcpClient（主） | MemoryAcpClient（记忆） |
|------|-----------------|------------------------|
| MCP 加载 | 加载用户配置的 MCP servers | 不加载任何 MCP |
| 交互模式 | 异步 + listener 回调 | 同步阻塞，返回 String |
| 会话历史 | ConversationHistoryManager 管理 | 无历史管理 |
| 图片支持 | 支持 base64 图片 | 纯文本 |
| 生命周期 | 长期存活，跟随 session | 用完即关，每次提取创建/复用 |
| 超时 | 无（等待用户交互） | 可配置（默认 30s） |

### 4.3 MemoryExtractor — 提取逻辑封装

```java
public class MemoryExtractor {

    private final MemoryConfig config;
    private final String command;
    private final String[] args;
    private final MemoryFileStore fileStore;

    /**
     * 执行一次记忆提取流程：
     * 1. 序列化对话历史为文本
     * 2. 读取已有索引（用于去重判断）
     * 3. 构建提取 prompt
     * 4. 创建 MemoryAcpClient，发送 prompt，获取 JSON 结果
     * 5. 解析结果，执行 ADD/UPDATE/DELETE 操作
     * 6. 更新索引文件和明细文件
     */
    public void extract(String workspacePath, List<ContextMessage> history) {
        // 1. 序列化历史
        String historyText = serializeHistory(history);

        // 2. 读取已有索引
        MemoryIndex existingIndex = fileStore.loadIndex(workspacePath);

        // 3. 构建 prompt
        String prompt = MemoryPromptTemplate.build(historyText, existingIndex);

        // 4. 调用子 Client
        String groupId = computeMemoryGroupId(workspacePath);
        try (MemoryAcpClient client = new MemoryAcpClient(
                command, args, workspacePath, groupId, config.getSubClientTimeout())) {
            client.start();
            String response = client.sendPromptSync(prompt);

            // 5. 解析 JSON 结果
            List<MemoryAction> actions = parseActions(response);

            // 6. 执行写入
            fileStore.applyActions(workspacePath, actions, existingIndex);
        }
    }

    private String serializeHistory(List<ContextMessage> history) {
        // 将 ContextMessage 列表序列化为可读文本
        // USER: xxx / ASSISTANT: xxx / TOOL: [toolName] input→output
    }
}
```

### 4.4 MemoryLoader — 概要构建

```java
public class MemoryLoader {

    private final MemoryFileStore fileStore;
    private final MemoryConfig config;

    /**
     * 构建注入 prompt 的记忆概要文本。
     * 按 updatedAt 降序排列，超出 indexMaxLines 限制时截断。
     */
    public String buildMemoryPrompt(String workspacePath) {
        MemoryIndex index = fileStore.loadIndex(workspacePath);
        if (index == null || index.getMemories().isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[记忆上下文]\n");
        sb.append("你有以下跨 session 的长期记忆。每条包含概要和明细文件的绝对路径。\n");
        sb.append("概要信息可直接参考；当你需要某条记忆的完整细节时，直接读取对应路径的文件即可。\n\n");

        List<MemoryEntry> sorted = index.getMemories().stream()
                .sorted(Comparator.comparing(MemoryEntry::getUpdatedAt).reversed())
                .collect(Collectors.toList());

        int lineCount = 4; // 已用行数（头部）
        int maxLines = config.getIndexMaxLines();
        int shown = 0;

        for (MemoryEntry entry : sorted) {
            if (lineCount + 3 > maxLines) break;
            shown++;
            sb.append(String.format("%d. [%s] %s：%s\n   📄 %s\n\n",
                    shown, entry.getType(), entry.getTitle(),
                    entry.getSummary(), entry.getFile()));
            lineCount += 3;
        }

        if (shown < sorted.size()) {
            sb.append(String.format("... 还有 %d 条较早的记忆未列出。如需查看完整列表，请读取索引文件：\n📄 %s\n",
                    sorted.size() - shown,
                    fileStore.getIndexPath(workspacePath)));
        }

        return sb.toString();
    }
}
```

### 4.5 MemoryFileStore — 文件存储层

```java
public class MemoryFileStore {

    private final String baseDir;  // ~/.cmd-proxy/memory

    /** 加载项目的记忆索引 */
    public MemoryIndex loadIndex(String workspacePath) { ... }

    /** 保存索引文件 */
    public void saveIndex(String workspacePath, MemoryIndex index) { ... }

    /** 写入/更新明细文件（Markdown + Frontmatter） */
    public void writeDetail(String workspacePath, MemoryEntry entry) { ... }

    /** 删除记忆（移到 archive 目录） */
    public boolean deleteMemory(String workspacePath, String memoryId) { ... }

    /** 执行批量操作（ADD/UPDATE/DELETE） */
    public void applyActions(String workspacePath, List<MemoryAction> actions,
                             MemoryIndex existingIndex) { ... }

    /** 获取索引文件绝对路径 */
    public String getIndexPath(String workspacePath) { ... }

    /** workspacePath → hash 目录名 */
    private String hashWorkspacePath(String workspacePath) { ... }
}
```

### 4.6 Model 类

```java
// MemoryConfig.java
public class MemoryConfig {
    private boolean enabled = false;
    private String baseDir = System.getProperty("user.home") + "/.cmd-proxy/memory";
    private int extractIntervalTurns = 5;
    private int indexMaxLines = 200;
    private int maxEntriesPerProject = 50;
    private int maxEntriesGlobal = 20;
    private int projectExpireDays = 30;
    private int subClientTimeout = 30;
    // getters/setters...
}

// MemoryEntry.java
public class MemoryEntry {
    private String id;
    private String type;       // user | feedback | project | reference
    private String title;
    private String summary;
    private String file;       // 明细文件绝对路径
    private List<String> tags;
    private String createdAt;
    private String updatedAt;
    // getters/setters...
}

// MemoryIndex.java
public class MemoryIndex {
    private int version = 1;
    private String lastUpdated;
    private List<MemoryEntry> memories = new ArrayList<>();
    // getters/setters...
}
```

---

## 5. 主 Client 集成方式

### 5.1 AcpClient 改动点（最小化）

主 Client 仅新增 2 个交互点，改动极小：

```java
public class AcpClient extends AbstractAcpClient {

    // 新增字段：记忆管理器（可为 null，未启用时不影响任何逻辑）
    private MemoryManager memoryManager;

    // 新增 setter，由外部（AcpProxy）注入
    public void setMemoryManager(MemoryManager memoryManager) {
        this.memoryManager = memoryManager;
    }

    // ===== 改动点 1：sendPrompt() 中注入记忆概要 =====
    private void sendPrompt(String userInput, ...) throws IOException {
        // 在构建 prompt 前，拼接记忆上下文
        String enrichedInput = userInput;
        if (memoryManager != null) {
            String memoryContext = memoryManager.buildMemoryPrompt(workspacePath);
            if (!memoryContext.isEmpty()) {
                enrichedInput = memoryContext + "\n" + userInput;
            }
        }
        // ... 后续逻辑不变，使用 enrichedInput 替代 userInput ...
    }

    // ===== 改动点 2：close() 中触发记忆提取 =====
    @Override
    public void close() throws IOException {
        // 触发最终记忆提取
        if (memoryManager != null && sessionId != null) {
            memoryManager.extractAsync(workspacePath,
                    historyManager.getFullHistory(sessionId));
        }
        super.close();
    }
}
```

### 5.2 ConversationHistoryManager 改动点

新增 turn 计数回调，支持每 N 轮触发记忆提取：

```java
public class ConversationHistoryManager {

    // 新增：turn 完成回调
    private Runnable onTurnFlushed;

    public void setOnTurnFlushed(Runnable callback) {
        this.onTurnFlushed = callback;
    }

    public void flushTurn(String sessionId) {
        // ... 现有落盘逻辑不变 ...

        // 新增：通知回调
        if (onTurnFlushed != null) {
            onTurnFlushed.run();
        }
    }
}
```

主 Client 中注册回调：

```java
// AcpClient 构造函数或 start() 中
if (memoryManager != null && config.getExtractIntervalTurns() > 0) {
    AtomicInteger turnCount = new AtomicInteger(0);
    historyManager.setOnTurnFlushed(() -> {
        if (turnCount.incrementAndGet() % config.getExtractIntervalTurns() == 0) {
            memoryManager.extractAsync(workspacePath,
                    historyManager.getFullHistory(sessionId));
        }
    });
}
```

### 5.3 AcpProxy 改动点

在 `start()` 中初始化 MemoryManager 并注入：

```kotlin
object AcpProxy {

    private var memoryManager: MemoryManager? = null

    fun start(cmdGroupList: List<String>, ..., memoryConfig: MemoryConfig) {
        // 初始化记忆管理器
        if (memoryConfig.isEnabled) {
            memoryManager = MemoryManager(memoryConfig, defaultCommand, defaultArgs)
        }

        // 冷加载时注入
        for (groupId in cmdGroupList) {
            val client = registry.getClient(groupId)
            if (client != null && memoryManager != null) {
                client.setMemoryManager(memoryManager)
            }
        }

        // acpClearContext 中触发记忆提取（session 结束）
        // ... 在现有 clearContext 逻辑中，close() 已自动触发 ...

        // 新增命令：acpMemoryList / acpMemoryDelete
        registerMemoryCommands(cmdGroupList)
    }
}
```

### 5.4 Main.kt 改动点

解析 `acpConfig.json` 中的 `memory` 字段：

```kotlin
private fun startAcp() {
    // ... 现有解析逻辑 ...

    // 新增：解析 memory 配置
    val memoryConfig = if (config.containsKey("memory")) {
        config.getJSONObject("memory").toJavaObject(MemoryConfig::class.java)
    } else {
        MemoryConfig()  // 默认值，enabled=false
    }

    AcpProxy.start(groupIdList, robotsJsonStr, chatterIdsJsonStr, groupWorkDirMap, memoryConfig)
}
```

---

## 6. 完整数据流

```
用户发送消息
    │
    ▼
AcpProxy.acpSendMessage()
    │
    ▼
AcpClient.send(userInput)
    │
    ▼
AcpClient.sendPrompt()
    │
    ├──① MemoryManager.buildMemoryPrompt(workspacePath)
    │       │
    │       ▼
    │   MemoryLoader → MemoryFileStore.loadIndex()
    │       │
    │       ▼
    │   返回概要文本（或空字符串）
    │
    ├──② enrichedInput = memoryContext + timeContext + userInput
    │
    ├──③ 发送 session/prompt，流式读取响应
    │
    ├──④ historyManager.flushTurn()
    │       │
    │       ▼ (每 N 轮触发回调)
    │   MemoryManager.extractAsync()
    │       │
    │       ▼ (异步线程)
    │   MemoryExtractor.extract()
    │       ├── 创建 MemoryAcpClient
    │       ├── 发送提取 prompt
    │       ├── 解析 JSON 结果
    │       └── MemoryFileStore.applyActions()
    │
    ▼
Session 结束 → AcpClient.close()
    │
    ├──⑤ MemoryManager.extractAsync()  ← 最终一次提取
    │
    └──⑥ super.close()  ← 关闭子进程
```

---

## 7. 实现优先级

### P0（必须实现）

| 任务 | 涉及文件 | 说明 |
|------|---------|------|
| 提炼 AbstractAcpClient | 新增 AbstractAcpClient.java，修改 AcpClient.java | 抽取通用协议方法 |
| MemoryConfig / MemoryEntry / MemoryIndex | 新增 model/*.java | POJO 模型 |
| MemoryFileStore | 新增 MemoryFileStore.java | 索引和明细文件的 CRUD |
| MemoryAcpClient | 新增 MemoryAcpClient.java | 同步 prompt 子 Client |
| MemoryExtractor | 新增 MemoryExtractor.java | 子 Client 调用 + 结果解析 |
| MemoryLoader | 新增 MemoryLoader.java | 概要文本构建 |
| MemoryManager | 新增 MemoryManager.java | 门面类 |
| AcpClient 集成 | 修改 AcpClient.java | 2 个改动点 |
| Main.kt 配置解析 | 修改 Main.kt | 解析 memory 字段 |

### P1（推荐实现）

| 任务 | 说明 |
|------|------|
| 每 N 轮触发 | ConversationHistoryManager 增加回调 |
| 用户显式指令 | AcpProxy 增加 acpMemoryList / acpMemoryDelete 命令 |
| 记忆过期清理 | MemoryFileStore 中按 projectExpireDays 归档 |

### P2（后续迭代）

| 任务 | 说明 |
|------|------|
| 全局记忆 | 跨项目的 user 类型记忆提升到 global 目录 |
| 记忆容量自动归档 | 超出 maxEntries 时自动归档最不活跃的记忆 |
| 用户手动编辑记忆 | 支持用户直接编辑 Markdown 明细文件后自动同步索引 |
