# ACP 记忆整理（Auto Dream）技术方案

## 1. 问题背景

当前记忆系统已实现"提取"和"存储"，但缺少"整理"能力。随着 session 累积，记忆会出现以下退化问题：

| 问题 | 示例 | 影响 |
|------|------|------|
| 矛盾条目 | 一条说"项目用 MyBatis"，另一条说"已迁移到 JPA" | agent 收到冲突信号，行为不确定 |
| 重复条目 | 三个 session 分别记了"用户偏好中文回复" | 浪费索引行数，挤占 200 行上限 |
| 过期条目 | "v1 API 将在 2026-04-30 下线"（已过期） | 误导 agent 基于过时信息决策 |
| 相对日期腐烂 | detail 中写"昨天决定用 Redis" | 跨 session 后完全失去时间参照 |
| 碎片化 | 同一主题（如"测试偏好"）被拆成多条小记忆 | 概要冗长，agent 需读多个明细文件才能拼出完整画面 |

Claude Code 的 Auto Dream 机制正是解决这类问题的：一个后台子 agent 定期扫描、整合、裁剪记忆文件。

---

## 2. 设计目标

- 复用现有 `MemoryAcpClient` + `MemoryFileStore` 基础设施，不引入新的外部依赖
- 与现有提取流程（`MemoryExtractor`）解耦，独立调度
- 整理过程对主 Client 完全透明，不阻塞对话
- 支持自动触发 + 手动触发两种模式

---

## 3. 触发机制

### 3.1 自动触发：双门控

参考 Claude Code Auto Dream 的设计，同时满足两个条件才触发：

| 条件 | 阈值 | 说明 |
|------|------|------|
| 距上次整理的时间 | ≥ `dreamMinHours`（默认 24h） | 避免频繁整理浪费资源 |
| 累积 session 数 | ≥ `dreamMinSessions`（默认 5） | 确保有足够新素材值得整理 |

检查时机：每次 `MemoryManager.buildMemoryPrompt()` 被调用时（即每次新对话开始时），顺便检查是否满足整理条件。满足则异步触发，不影响当前 prompt 构建。

### 3.2 手动触发

通过 `acpMemoryDream` 命令手动触发，跳过双门控条件，立即执行整理。

### 3.3 状态追踪

在记忆存储目录下新增 `DREAM_STATE.json` 文件，记录整理状态：

```json
{
  "lastDreamTime": "2026-04-06T10:00:00+08:00",
  "sessionsSinceLastDream": 3,
  "lastDreamResult": {
    "merged": 2,
    "removed": 1,
    "dateFixed": 4,
    "durationMs": 8500
  }
}
```

session 计数的递增点：`AcpClient.close()` 被调用时（即 `acpClearContext` 或进程退出），在记忆提取之后递增 `sessionsSinceLastDream`。

---

## 4. 整理流程

### 4.1 四阶段流程（对齐 Claude Code Auto Dream）

```
Phase 1: Orientation（定位）
    │  读取 MEMORY_INDEX.json + 所有明细文件
    │  构建当前记忆全景
    ▼
Phase 2: Analysis（分析）
    │  将全部记忆内容 + 当前日期发给子 Client
    │  子 Client 识别：矛盾、重复、过期、碎片、相对日期
    ▼
Phase 3: Consolidation（整合）
    │  子 Client 返回整理操作列表（JSON）
    │  操作类型：MERGE / UPDATE / DELETE / NOOP
    ▼
Phase 4: Apply（执行）
    │  MemoryFileStore 执行操作
    │  更新索引、明细文件、归档被删除的条目
    │  更新 DREAM_STATE.json
    ▼
Done
```

### 4.2 子 Client Prompt 设计

整理和提取使用同一个 `MemoryAcpClient`，但 prompt 完全不同。整理 prompt 的输入不是对话历史，而是全部记忆内容。

```
你是记忆整理子系统（Memory Dream）。你的职责是审查和整合已有记忆，提升记忆质量。

## 当前日期
{currentDate}

## 全部记忆
以下是当前项目的所有记忆条目，包含索引信息和明细内容：

### 记忆 1
- id: memory_001
- type: user
- title: 用户技术背景
- summary: 资深 Java/Kotlin 开发者，熟悉 Spring 生态
- tags: [java, kotlin, spring]
- createdAt: 2026-03-20T14:00:00+08:00
- updatedAt: 2026-04-01T10:30:00+08:00
- detail:
用户是资深 Java/Kotlin 开发者，主要负责 ACP 模块开发。
**Why:** 用户在多次对话中展示了对 Spring Boot、Kotlin 协程的深入理解。
**How to apply:** 代码建议优先使用 Kotlin 惯用写法，Spring 生态内的方案。

### 记忆 2
...（所有记忆逐条列出）

## 整理规则

请执行以下整理操作：

### 1. 矛盾消除
识别语义上矛盾的记忆对。保留更新时间更晚的那条（通常反映最新状态），
删除或更新过时的那条。
示例：「API 用 Express」vs「已迁移到 Fastify」→ 删除前者或更新为迁移历史。

### 2. 重复合并
识别语义高度重叠的记忆。合并为一条，保留最完整的信息，
合并后的 detail 应包含所有来源的有价值内容。
合并时使用被合并条目中 updatedAt 最新的那个作为合并后的时间。

### 3. 过期清理
基于当前日期判断：
- 包含明确截止日期且已过期的记忆（如「v1 API 将在 2026-04-30 下线」，当前已过期）→ DELETE
- project 类型记忆超过 30 天未更新 → 标记建议删除（action=DELETE）
- 引用了不太可能仍然存在的临时资源的记忆 → DELETE

### 4. 日期规范化
将 detail 中的相对日期表述转换为绝对日期。
根据记忆的 createdAt/updatedAt 推算：
- 「昨天」→ 基于 updatedAt 推算具体日期
- 「上周」→ 推算为具体日期范围
- 「最近」→ 替换为 updatedAt 对应的日期

### 5. 碎片整合
识别同一主题被拆分成多条的情况，合并为一条完整的记忆。
判断标准：type 相同 + tags 高度重叠 + title/summary 语义相近。

## 输出格式

请以 JSON 数组返回操作列表：
```json
[
  {
    "action": "MERGE",
    "sourceIds": ["memory_001", "memory_003"],
    "result": {
      "type": "feedback",
      "title": "合并后的标题",
      "summary": "合并后的一句话概要",
      "detail": "合并后的完整内容",
      "tags": ["tag1", "tag2"]
    }
  },
  {
    "action": "UPDATE",
    "id": "memory_002",
    "fields": {
      "detail": "日期规范化后的 detail 内容"
    }
  },
  {
    "action": "DELETE",
    "id": "memory_005",
    "reason": "与 memory_002 矛盾，且 memory_005 更新时间更早"
  },
  {
    "action": "NOOP",
    "id": "memory_004",
    "reason": "无需整理"
  }
]
```

规则：
- MERGE：将 sourceIds 中的多条记忆合并为一条新记忆。sourceIds 中的旧条目会被删除。
- UPDATE：更新指定记忆的部分字段（通常用于日期规范化）。fields 中只包含需要修改的字段。
- DELETE：删除指定记忆。必须提供 reason。
- NOOP：无需操作。可省略不写。
- 只输出 JSON 数组，不要输出其他内容。
- 如果所有记忆都无需整理，返回空数组 []。
- JSON 字符串值中严禁使用中文引号（" " ' '），如需引用请用「」代替。
```

### 4.3 与现有提取流程的关键差异

| 维度 | 记忆提取（MemoryExtractor） | 记忆整理（MemoryDreamer） |
|------|---------------------------|--------------------------|
| 输入 | 对话历史（ContextMessage） | 全部已有记忆（MemoryIndex + 明细文件） |
| 目的 | 从对话中发现新记忆 | 整合、去重、清理已有记忆 |
| 操作类型 | ADD / UPDATE / DELETE / NOOP | MERGE / UPDATE / DELETE / NOOP |
| 触发频率 | 每 N 轮 + session 结束 | 每 24h + 5 session（或手动） |
| 新增操作 | MERGE（提取没有这个） | 将多条合并为一条，是整理的核心操作 |

---

## 5. 核心类设计

### 5.1 新增类

```
com.mola.cmd.proxy.app.acp.memory/
├── MemoryDreamer.java              # 整理器，核心逻辑
├── model/
│   ├── DreamAction.java            # 整理操作模型（MERGE/UPDATE/DELETE/NOOP）
│   └── DreamState.java             # 整理状态模型（DREAM_STATE.json）
└── prompt/
    └── DreamPromptTemplate.java    # 整理 prompt 模板
```

### 5.2 MemoryDreamer

```java
public class MemoryDreamer {

    private static final Logger logger = LoggerFactory.getLogger(MemoryDreamer.class);

    private final MemoryConfig config;
    private final MemoryFileStore fileStore;

    /** 复用 MemoryExtractor 的单线程队列模式，保证与提取任务不并发 */
    private final ExecutorService dreamQueue = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(2),
            r -> { Thread t = new Thread(r, "memory-dream-queue"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.DiscardOldestPolicy()
    );

    public MemoryDreamer(MemoryConfig config, MemoryFileStore fileStore) {
        this.config = config;
        this.fileStore = fileStore;
    }

    /**
     * 检查是否满足自动整理条件。
     * 在 buildMemoryPrompt() 中调用，满足则异步触发。
     */
    public boolean shouldDream(String workspacePath) {
        DreamState state = fileStore.loadDreamState(workspacePath);
        if (state == null) return false;

        // 双门控
        boolean timeOk = state.getHoursSinceLastDream() >= config.getDreamMinHours();
        boolean sessionsOk = state.getSessionsSinceLastDream() >= config.getDreamMinSessions();

        // 额外条件：至少有 3 条记忆才值得整理
        MemoryIndex index = fileStore.loadIndex(workspacePath);
        boolean hasEnough = index.getMemories().size() >= 3;

        return timeOk && sessionsOk && hasEnough;
    }

    /**
     * 异步提交整理任务。
     */
    public void submitDream(String workspacePath) {
        try {
            dreamQueue.submit(() -> doDream(workspacePath));
        } catch (RejectedExecutionException e) {
            logger.warn("整理队列已满，跳过本次整理");
        }
    }

    /**
     * 执行整理流程。
     */
    private void doDream(String workspacePath) {
        long startTime = System.currentTimeMillis();
        logger.info("开始记忆整理, workspacePath={}", workspacePath);

        try {
            // Phase 1: Orientation — 读取全部记忆
            MemoryIndex index = fileStore.loadIndex(workspacePath);
            if (index.getMemories().isEmpty()) {
                logger.info("无记忆可整理");
                return;
            }
            Map<String, String> details = fileStore.loadAllDetails(workspacePath, index);

            // Phase 2: Analysis — 构建 prompt 并调用子 Client
            String prompt = DreamPromptTemplate.build(index, details);
            String groupId = "memory_dreamer__" + workspacePath.hashCode();

            String response;
            try (MemoryAcpClient client = new MemoryAcpClient(
                    workspacePath, groupId,
                    config.getSubClientTimeout() * 2,  // 整理比提取更耗时，超时翻倍
                    config.getAgentProvider())) {
                client.start();
                response = client.sendPromptSync(prompt);
            }

            // Phase 3: 解析操作列表
            List<DreamAction> actions = parseDreamActions(response);
            if (actions.isEmpty()) {
                logger.info("记忆无需整理");
                updateDreamState(workspacePath, 0, 0, 0, System.currentTimeMillis() - startTime);
                return;
            }

            // Phase 4: Apply — 执行操作
            DreamResult result = applyDreamActions(workspacePath, actions, index);

            // 更新整理状态
            updateDreamState(workspacePath, result.merged, result.removed,
                    result.dateFixed, System.currentTimeMillis() - startTime);

            logger.info("记忆整理完成: merged={}, removed={}, dateFixed={}, 耗时={}ms",
                    result.merged, result.removed, result.dateFixed,
                    System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            logger.error("记忆整理失败, workspacePath={}", workspacePath, e);
        }
    }

    /**
     * 执行整理操作。
     */
    private DreamResult applyDreamActions(String workspacePath,
                                          List<DreamAction> actions,
                                          MemoryIndex index) {
        int merged = 0, removed = 0, dateFixed = 0;
        String now = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        for (DreamAction action : actions) {
            switch (action.getAction()) {
                case MERGE:
                    // 1. 删除 sourceIds 中的所有旧条目
                    for (String sourceId : action.getSourceIds()) {
                        fileStore.removeAndArchive(workspacePath, index, sourceId);
                    }
                    // 2. 创建合并后的新条目
                    MemoryEntry mergedEntry = new MemoryEntry();
                    mergedEntry.setId("memory_" + UUID.randomUUID().toString().substring(0, 8));
                    mergedEntry.setType(action.getResult().getType());
                    mergedEntry.setTitle(action.getResult().getTitle());
                    mergedEntry.setSummary(action.getResult().getSummary());
                    mergedEntry.setDetail(action.getResult().getDetail());
                    mergedEntry.setTags(action.getResult().getTags());
                    mergedEntry.setCreatedAt(now);
                    mergedEntry.setUpdatedAt(now);
                    fileStore.writeDetail(workspacePath, mergedEntry);
                    index.getMemories().add(mergedEntry);
                    merged++;
                    break;

                case UPDATE:
                    for (MemoryEntry entry : index.getMemories()) {
                        if (entry.getId().equals(action.getId())) {
                            if (action.getFields().containsKey("detail")) {
                                entry.setDetail(action.getFields().get("detail"));
                            }
                            if (action.getFields().containsKey("summary")) {
                                entry.setSummary(action.getFields().get("summary"));
                            }
                            if (action.getFields().containsKey("title")) {
                                entry.setTitle(action.getFields().get("title"));
                            }
                            entry.setUpdatedAt(now);
                            fileStore.writeDetail(workspacePath, entry);
                            dateFixed++;
                            break;
                        }
                    }
                    break;

                case DELETE:
                    fileStore.removeAndArchive(workspacePath, index, action.getId());
                    removed++;
                    break;
            }
        }

        fileStore.saveIndex(workspacePath, index);
        return new DreamResult(merged, removed, dateFixed);
    }

    // ... parseDreamActions(), updateDreamState(), shutdown() 省略
}
```

### 5.3 DreamAction 模型

```java
public class DreamAction {

    public enum ActionType {
        MERGE, UPDATE, DELETE, NOOP
    }

    private ActionType action;

    // MERGE 专用
    private List<String> sourceIds;     // 被合并的记忆 ID 列表
    private MergeResult result;         // 合并后的新记忆内容

    // UPDATE 专用
    private String id;                  // 要更新的记忆 ID
    private Map<String, String> fields; // 要更新的字段 (key=字段名, value=新值)

    // DELETE 专用（id 复用上面的）
    private String reason;              // 删除原因（用于日志）

    // getters/setters...

    public static class MergeResult {
        private String type;
        private String title;
        private String summary;
        private String detail;
        private List<String> tags;
        // getters/setters...
    }
}
```

### 5.4 DreamState 模型

```java
public class DreamState {

    private String lastDreamTime;           // 上次整理时间 (ISO 8601)
    private int sessionsSinceLastDream;     // 上次整理后累积的 session 数

    // 上次整理结果（用于日志和诊断）
    private int lastMerged;
    private int lastRemoved;
    private int lastDateFixed;
    private long lastDurationMs;

    /**
     * 计算距上次整理的小时数。
     * 如果从未整理过，返回 Integer.MAX_VALUE（确保首次一定满足时间条件）。
     */
    public int getHoursSinceLastDream() {
        if (lastDreamTime == null) return Integer.MAX_VALUE;
        try {
            ZonedDateTime last = ZonedDateTime.parse(lastDreamTime);
            return (int) java.time.Duration.between(last, ZonedDateTime.now()).toHours();
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    // getters/setters...
}
```

### 5.5 DreamPromptTemplate

```java
public class DreamPromptTemplate {

    /**
     * 构建整理 prompt。
     *
     * @param index   当前记忆索引
     * @param details 所有明细文件内容 (key=memoryId, value=明细文本)
     * @return 完整 prompt
     */
    public static String build(MemoryIndex index, Map<String, String> details) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是记忆整理子系统（Memory Dream）。...");  // 完整 prompt 见 4.2 节
        // 遍历 index.getMemories()，逐条拼接索引信息 + details 中的明细内容
        // 附加当前日期、整理规则、输出格式
        return sb.toString();
    }
}
```

---

## 6. 与现有系统的集成

### 6.1 MemoryManager 改动

新增 `MemoryDreamer` 字段和两个方法：

```java
public class MemoryManager implements MemoryManagerBridge {

    private final MemoryDreamer dreamer;  // 新增

    public MemoryManager(MemoryConfig config) {
        // ... 现有初始化 ...
        this.dreamer = new MemoryDreamer(config, fileStore);  // 新增
    }

    @Override
    public String buildMemoryPrompt(String workspacePath) {
        if (!config.isEnabled()) return "";
        try {
            // 新增：检查是否需要自动整理
            checkAndTriggerDream(workspacePath);
            return loader.buildMemoryPrompt(workspacePath);
        } catch (Exception e) {
            logger.error("构建记忆概要失败", e);
            return "";
        }
    }

    /**
     * 新增：检查并触发自动整理。
     */
    private void checkAndTriggerDream(String workspacePath) {
        try {
            if (dreamer.shouldDream(workspacePath)) {
                logger.info("满足自动整理条件，触发 Memory Dream");
                dreamer.submitDream(workspacePath);
            }
        } catch (Exception e) {
            logger.warn("检查整理条件失败", e);
        }
    }

    /**
     * 新增：手动触发整理（供 acpMemoryDream 命令调用）。
     */
    public void triggerDream(String workspacePath) {
        dreamer.submitDream(workspacePath);
    }

    /**
     * 新增：递增 session 计数（AcpClient.close() 时调用）。
     */
    public void incrementSessionCount(String workspacePath) {
        fileStore.incrementDreamSessionCount(workspacePath);
    }

    public void shutdown() {
        extractor.shutdown();
        dreamer.shutdown();  // 新增
    }
}
```

### 6.2 MemoryManagerBridge 改动

新增 `incrementSessionCount` 方法：

```java
public interface MemoryManagerBridge {
    String buildMemoryPrompt(String workspacePath);
    void submitExtract(String workspacePath, List<ContextMessage> history);
    void submitExtractFull(String workspacePath, List<ContextMessage> history);
    void incrementSessionCount(String workspacePath);  // 新增
}
```

### 6.3 AcpClient 改动

在 `close()` 中，记忆提取之后递增 session 计数：

```java
@Override
public void close() throws IOException {
    if (memoryManager != null && sessionId != null) {
        memoryManager.submitExtractFull(workspacePath, getConversationHistory());
        memoryManager.incrementSessionCount(workspacePath);  // 新增
    }
    super.close();
}
```

### 6.4 MemoryFileStore 改动

新增 DreamState 相关的文件操作：

```java
public class MemoryFileStore {

    private static final String DREAM_STATE_FILE = "DREAM_STATE.json";

    /** 加载整理状态 */
    public DreamState loadDreamState(String workspacePath) {
        Path path = getProjectDir(workspacePath).resolve(DREAM_STATE_FILE);
        if (!Files.exists(path)) {
            // 首次：返回默认状态（lastDreamTime=null, sessions=0）
            return new DreamState();
        }
        // 读取 JSON 反序列化
    }

    /** 保存整理状态 */
    public void saveDreamState(String workspacePath, DreamState state) {
        // 序列化为 JSON 写入
    }

    /** 递增 session 计数 */
    public void incrementDreamSessionCount(String workspacePath) {
        DreamState state = loadDreamState(workspacePath);
        state.setSessionsSinceLastDream(state.getSessionsSinceLastDream() + 1);
        saveDreamState(workspacePath, state);
    }

    /** 读取所有明细文件内容 */
    public Map<String, String> loadAllDetails(String workspacePath, MemoryIndex index) {
        Map<String, String> details = new HashMap<>();
        for (MemoryEntry entry : index.getMemories()) {
            if (entry.getFile() != null) {
                try {
                    String content = new String(Files.readAllBytes(Paths.get(entry.getFile())),
                            StandardCharsets.UTF_8);
                    details.put(entry.getId(), content);
                } catch (IOException e) {
                    logger.warn("读取明细文件失败: {}", entry.getFile());
                }
            }
        }
        return details;
    }

    /**
     * 公开 removeAndArchive 方法，供 MemoryDreamer 调用。
     * （当前是 private，需改为 package-private 或 public）
     */
    public boolean removeAndArchive(String workspacePath, MemoryIndex index, String memoryId) {
        // 现有逻辑不变
    }
}
```

### 6.5 AcpProxy 改动

新增 `acpMemoryDream` 命令：

```kotlin
CmdReceiver.register("acpMemoryDream", cmdGroupList, "手动触发记忆整理，groupId必填") { params ->
    val resultMap = mutableMapOf<String, String>()
    try {
        val param: JSONObject = JSON.parse(params.cmdArgs[0]) as JSONObject
        val groupId = param.getString("groupId")
        if (groupId.isNullOrBlank()) {
            resultMap["result"] = "groupId不能为空"
            return@register resultMap
        }
        val mgr = memoryManagers[groupId]
        if (mgr == null) {
            resultMap["result"] = "该 robot 未启用记忆系统"
            return@register resultMap
        }
        val client = registry.getClient(groupId)
        if (client == null) {
            resultMap["result"] = "会话不存在"
            return@register resultMap
        }
        mgr.triggerDream(client.workspacePath)
        resultMap["result"] = "记忆整理已触发，将在后台执行"
    } catch (e: Exception) {
        log.error("acpMemoryDream 失败", e)
        resultMap["result"] = "触发记忆整理失败: ${e.message}"
    }
    resultMap
}
```

---

## 7. MemoryConfig 新增字段

```java
public class MemoryConfig {
    // ... 现有字段 ...

    // Dream 相关配置
    private int dreamMinHours = 24;       // 自动整理最小间隔（小时）
    private int dreamMinSessions = 5;     // 自动整理最小累积 session 数
    private boolean dreamEnabled = true;  // 整理功能开关（独立于 memory enabled）

    // getters/setters...
}
```

对应 `acpConfig.json` 中的配置：

```json
{
  "memory": {
    "enabled": true,
    "dreamEnabled": true,
    "dreamMinHours": 24,
    "dreamMinSessions": 5
  }
}
```

---

## 8. 并发安全

整理和提取共享同一个 `MemoryFileStore`，可能并发写入索引文件。解决方案：

### 方案：文件锁

在 `MemoryFileStore.saveIndex()` 中使用 `FileLock`：

```java
public void saveIndex(String workspacePath, MemoryIndex index) {
    Path indexPath = getIndexFilePath(workspacePath);
    Path lockPath = indexPath.resolveSibling("MEMORY_INDEX.lock");
    try (FileChannel channel = FileChannel.open(lockPath,
            StandardOpenOption.CREATE, StandardOpenOption.WRITE);
         FileLock lock = channel.lock()) {

        // 在锁内执行写入
        index.setLastUpdated(ZonedDateTime.now().format(ISO_FORMATTER));
        Files.write(indexPath, INDEX_GSON.toJson(index).getBytes(StandardCharsets.UTF_8));

    } catch (IOException e) {
        logger.error("保存记忆索引失败: {}", indexPath, e);
    }
}
```

同时，`MemoryDreamer.doDream()` 开始时也获取锁，确保整理期间提取不会并发修改索引。

---

## 9. 完整数据流

```
Session 1 结束 → AcpClient.close()
    ├── submitExtractFull()          ← 提取记忆
    └── incrementSessionCount()      ← sessionsSinceLastDream: 0→1

Session 2 结束 → sessionsSinceLastDream: 1→2
Session 3 结束 → sessionsSinceLastDream: 2→3
Session 4 结束 → sessionsSinceLastDream: 3→4
Session 5 结束 → sessionsSinceLastDream: 4→5

Session 6 开始 → AcpClient.sendPrompt()
    └── MemoryManager.buildMemoryPrompt()
        └── checkAndTriggerDream()
            ├── shouldDream()? → hours>=24 ✓ && sessions>=5 ✓ → true
            └── dreamer.submitDream()  ← 异步触发整理
                │
                ▼ (后台线程)
            doDream()
                ├── Phase 1: loadIndex + loadAllDetails
                ├── Phase 2: DreamPromptTemplate.build() → MemoryAcpClient.sendPromptSync()
                ├── Phase 3: parseDreamActions()
                ├── Phase 4: applyDreamActions()
                │   ├── MERGE: 删除旧条目 + 创建合并条目
                │   ├── UPDATE: 更新 detail（日期规范化）
                │   └── DELETE: 归档过期/矛盾条目
                └── updateDreamState(sessionsSinceLastDream=0, lastDreamTime=now)
```

---

## 10. 实现优先级

### P0（核心）

| 任务 | 涉及文件 |
|------|---------|
| DreamAction 模型 | 新增 `model/DreamAction.java` |
| DreamState 模型 | 新增 `model/DreamState.java` |
| DreamPromptTemplate | 新增 `prompt/DreamPromptTemplate.java` |
| MemoryDreamer 核心逻辑 | 新增 `MemoryDreamer.java` |
| MemoryFileStore 扩展 | 修改：新增 `loadDreamState/saveDreamState/loadAllDetails`，公开 `removeAndArchive` |
| MemoryManager 集成 | 修改：新增 dreamer 字段、`checkAndTriggerDream`、`triggerDream`、`incrementSessionCount` |
| MemoryManagerBridge 扩展 | 修改：新增 `incrementSessionCount` |
| AcpClient.close() 改动 | 修改：新增 `incrementSessionCount` 调用 |
| MemoryConfig 新增字段 | 修改：新增 dream 相关配置 |

### P1（完善）

| 任务 | 说明 |
|------|------|
| acpMemoryDream 命令 | AcpProxy 中注册手动触发命令 |
| 文件锁并发安全 | MemoryFileStore 中 saveIndex 加文件锁 |
| 整理结果通知 | 整理完成后通过日志或回调通知用户 |

### P2（后续迭代）

| 任务 | 说明 |
|------|------|
| 整理预览 | 整理前先展示操作列表，用户确认后再执行 |
| 整理历史 | 记录每次整理的操作日志，支持回溯 |
| 跨项目全局记忆整理 | global 目录下的记忆也纳入整理范围 |
