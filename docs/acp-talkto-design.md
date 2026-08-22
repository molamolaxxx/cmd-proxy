# ACP TalkTo 通讯录与异步通信设计方案

## 1. 背景与目标

当前 cmd-proxy 的 Agent 间通信只有 SubAgent 派发模式（Orchestrator-Workers）：主 Agent 创建临时子进程执行任务，阻塞等待结果后汇总。这种模式适合"需要结果才能继续"的场景，但无法满足以下需求：

- Agent 之间的**异步通知**：A 通知 B 做某事，A 继续自己的工作
- **双向对话**：B 完成后可以主动回复 A，A 也可以回复 B
- **复用目标 Agent 的完整上下文**：利用 B 的主 Client 会话历史、记忆、工具链
- **松耦合协作**：不需要预先配置 subAgents 白名单，任意 robot 之间可通信

### 设计目标

1. Robot 维度可配置通讯录（contacts），作为 LLM 的参考信息
2. Agent 在 ACP 会话中可通过 `talk_to` 指令向其他 robot 发送消息
3. 发送后主 Agent 不阻塞，立即继续当前 turn
4. 目标 robot 忙碌时直接失败，不排队不重试
5. 接收方知道消息来源，完成后可选择回复

## 2. 与 SubAgent 的关系：并存互补

| 维度 | SubAgent 派发 | TalkTo |
|------|---------------|--------|
| 目标 Client | 临时创建，用完即毁 | 复用目标 robot 的**主 Client** |
| 阻塞性 | 主 Agent 阻塞等待结果 | **不阻塞**，fire-and-forget |
| 通信方向 | 单向（主→子→主） | 双向（A→B，B 可回复 A） |
| 目标限制 | 白名单（subAgents 配置） | 通讯录仅做参考，可发给任意 robot |
| 目标状态 | 无要求（新建进程） | 必须 READY，否则失败 |
| 上下文 | 无历史上下文（全新 session） | 保留目标 robot 的完整会话上下文 |
| 适用场景 | 需要结果才能继续的子任务 | 异步通知、接力协作、跨 Agent 沟通 |

**选择策略**（注入 prompt 让 LLM 自主判断）：
- 需要等结果 → SubAgent
- 通知/请求，不关心何时完成 → TalkTo
- 目标 robot 有自己的上下文和记忆，需要在其上下文中执行 → TalkTo

## 3. 配置层

### 3.1 通讯录配置（AcpRobotParam 新增字段）

```json
{
  "name": "Code-Cmd-Dev",
  "workDir": "/home/mola/IdeaProjects/cmd-proxy",
  "contacts": [
    {
      "name": "Code Chat Dev",
      "remark": "一般让他解决一些 MolaChat 相关的问题"
    },
    {
      "name": "Open Code Skill",
      "remark": "处理文档生成、早报、MBTI 分析等杂项任务"
    }
  ]
}
```

字段说明：
- `contacts[].name`：目标 robot 名称，必须在同一 `robots` 数组中存在
- `contacts[].remark`：对该联系人的备注说明，帮助 LLM 判断何时联系

### 3.2 与 subAgents 的配置关系

```json
{
  "name": "Code-Cmd-Dev",
  "subAgents": [
    { "name": "Code Chat Dev", "description": "..." }
  ],
  "contacts": [
    { "name": "Code Chat Dev", "remark": "..." },
    { "name": "Open Code Skill", "remark": "..." }
  ]
}
```

- 同一个 robot 可以同时出现在 `subAgents` 和 `contacts` 中
- `subAgents` 用于同步派发（创建临时进程）
- `contacts` 用于异步通信（发消息到主 Client）
- 两者独立配置，互不影响

## 4. JSON 指令定义

### 4.1 发送消息（talk_to）

```json
{
  "action": "talk_to",
  "target": "Code Chat Dev",
  "content": "帮我检查一下 MolaChat 的 WebSocket 重连逻辑有没有处理 token 过期的情况，查完告诉我结果"
}
```

字段说明：
- `action`：固定为 `talk_to`
- `target`：目标 robot 名称（不受通讯录限制，可以是任意已注册的 robot）
- `content`：要发送的消息内容

### 4.2 指令检测

复用现有的 `DispatchBufferFilter` 机制，新增 `talk_to` 关键词拦截：

```
TALK_TO_TRIGGER = "talk_to"
```

在 `AcpClient.sendPrompt()` 的 turn 结束后，新增 `handleTalkTo()` 处理分支。

## 5. 消息投递与 Inbox 机制

### 5.1 核心设计：统一排队

目标 robot 忙碌时，消息不丢弃，而是放入目标的 inbox 队列。目标 turn 结束变回 READY 后，自动投递 inbox 中的消息。

这解决了一个关键问题：A talkTo B，B 快速处理完回复 A，但 A 还在 BUSY——如果直接失败，B 的工作成果就丢了。

**Inbox 规则：**
- 每个 robot 维护一个 inbox（内存队列，容量上限 5 条）
- 目标 READY 时直接投递，不经过 inbox
- 目标非 READY 时进入 inbox 排队
- inbox 满时，新消息投递失败（告知发送方"对方消息队列已满"）
- 目标 turn 结束（state 从 BUSY → READY）时，自动取出 inbox 第一条消息投递

### 5.2 发送流程

```
A 的 LLM 输出 talk_to JSON
  → DispatchBufferFilter 拦截（不推送给用户）
  → turn 结束后 handleTalkTo() 检测到指令
  → 解析 target 和 content
  → 通过 globalRobotRegistry 校验 target 存在
  → 通过 groupRobotMap 找到 target 对应的 groupId
  → registry.getClient(groupId) 获取目标 Client
  → 检查 state == READY？
    → READY → 构造带来源标识的 prompt，调用 targetClient.send()
    → 非 READY → 放入目标的 inbox 队列
      → inbox 未满 → 入队成功，告知发送方"已排队，对方空闲后会处理"
      → inbox 已满 → 投递失败，告知发送方"对方消息队列已满"
  → 将执行结果作为 follow-up prompt 发回 A
  → A 的 turn 正常结束
```

### 5.3 Inbox 自动投递

在 `AcpClient.send()` 方法中，当 turn 完成 `state.set(State.READY)` 之后，检查 inbox：

```java
state.set(State.READY);
// turn 结束后检查 inbox
TalkToMessage pending = inbox.poll();
if (pending != null) {
    // 直接投递（此时 state 刚变为 READY）
    send(pending.buildPrompt(), null);
}
```

这样不需要额外的轮询线程，利用现有的 turn 结束时机自然触发。

### 5.4 接收方视角

B 收到的 prompt 格式（自包含，包含完整调用格式）：

```
[Incoming Message]
来自: Code-Cmd-Dev
内容: 帮我检查一下 MolaChat 的 WebSocket 重连逻辑有没有处理 token 过期的情况，查完告诉我结果

如果你完成任务后需要回复对方，在回复中输出以下 JSON：
{"action":"talk_to","target":"Code-Cmd-Dev","content":"你的回复内容"}
如果不需要回复，正常完成任务即可。
```

关键点：
- prompt 自包含，B 即使没有配置通讯录也能正确回复
- target 直接写死发送方名字，B 不需要知道系统中还有谁
- B 的 LLM 会在自己的完整上下文中处理这条消息，可以使用自己的工具、记忆等

### 5.5 回复流程

B 完成任务后，如果需要回复 A：

```json
{
  "action": "talk_to",
  "target": "Code-Cmd-Dev",
  "content": "检查完了，WebSocket 重连逻辑中没有处理 token 过期的情况。具体在 reconnect() 方法中..."
}
```

此时 A 可能还在 BUSY（处理其他事情），消息进入 A 的 inbox。A 当前 turn 结束后自动收到 B 的回复。

### 5.6 发送结果反馈

talkTo 执行后，A 会收到一个 follow-up prompt 告知结果：

**成功时（直接投递）：**
```
[talkTo 结果]
已成功将消息发送给 Code Chat Dev。对方会在空闲时处理，你可以继续当前工作。
```

**成功时（进入 inbox）：**
```
[talkTo 结果]
Code Chat Dev 当前正忙，消息已放入对方的待处理队列（第 2/5 条）。对方空闲后会自动收到。
```

**失败时（inbox 已满）：**
```
[talkTo 结果]
发送失败：Code Chat Dev 的消息队列已满（5/5），无法接收新消息。你可以稍后再试，或使用 dispatch_subagent 创建独立子进程执行。
```

**失败时（目标不存在）：**
```
[talkTo 结果]
发送失败：robot 'xxx' 不存在。请检查名称是否正确。
```

## 6. Prompt 注入（TalkToContextInjector）

### 6.1 注入时机

在 `AcpClient.sendPrompt()` 中，与 subAgentContext、scheduleContext 同级注入。

### 6.2 注入条件

- robot 配置了 `contacts`（非空）时注入通讯录 + talkTo 能力描述
- **没有配置 contacts 时不注入任何 talkTo 相关内容**（LLM 不知道该联系谁，注入无意义）
- 收到 talkTo 消息时，回复指引随消息 prompt 一起带入（临时性，不是全局注入）

### 6.3 注入内容模板

```
[通讯录]
你可以通过 talk_to 指令向其他 robot 发送异步消息。消息发送后你不需要等待回复，可以继续当前工作。
目标 robot 必须处于空闲状态才能接收消息，忙碌时发送会失败。

通讯录（仅供参考，你也可以向未列出的 robot 发送消息）：
- Code Chat Dev: 一般让他解决一些 MolaChat 相关的问题
- Open Code Skill: 处理文档生成、早报、MBTI 分析等杂项任务

发送消息格式：
{"action":"talk_to","target":"robot名称","content":"消息内容"}

与 dispatch_subagent 的区别：
- talk_to: 异步发送，不等待结果，目标在自己的上下文中处理
- dispatch_subagent: 同步等待结果，创建临时进程执行
```

### 6.4 防循环机制

在 prompt 注入中加入提示：

```
注意：避免对同一条消息反复回复形成循环。如果你收到的是对方的回复确认，通常不需要再次回复。
```

同时在代码层面增加简单的防护：
- 记录最近 N 条 talkTo 消息的 hash（发送方+接收方+内容摘要）
- 如果短时间内出现相同 hash，拒绝发送并告知 LLM

## 7. 核心类设计

### 7.1 新增类

```
com.mola.cmd.proxy.app.acp.talkto/
├── TalkToDispatcher.java          // 核心：消息投递逻辑
├── TalkToContextInjector.java     // Prompt 注入
└── model/
    └── ContactRef.java            // 通讯录条目模型
```

### 7.2 TalkToDispatcher

```java
public class TalkToDispatcher {

    private final Map<String, AcpRobotParam> robotRegistry;
    private final AcpClientRegistry clientRegistry;
    private final Map<String, String> robotToGroupId;  // robotName → groupId 反向索引

    /** 每个 robot 的 inbox 队列，key 为 robotName */
    private final ConcurrentHashMap<String, LinkedBlockingQueue<TalkToMessage>> inboxes;

    private static final int INBOX_CAPACITY = 5;

    /**
     * 从 LLM 输出中检测 talk_to 指令。
     * @return 解析出的 target 和 content，未检测到时返回 null
     */
    public TalkToRequest detectTalkTo(String fullResponse);

    /**
     * 执行消息投递。
     * 目标 READY 时直接投递，非 READY 时放入 inbox。
     * @return 执行结果文本（成功/排队/失败描述），作为 follow-up prompt 回注
     */
    public String deliver(TalkToRequest request, String senderName);

    /**
     * 从目标 robot 的 inbox 中取出下一条待处理消息。
     * 在 AcpClient turn 结束后调用。
     * @return 待投递的消息，inbox 为空时返回 null
     */
    public TalkToMessage pollInbox(String robotName);
}
```

### 7.3 TalkToContextInjector

```java
public class TalkToContextInjector {

    /**
     * 构建通讯录上下文，注入到 prompt 中。
     * @param contacts     当前 robot 的通讯录配置（可为 null）
     * @param robotRegistry 全局 robot 注册表
     * @param selfName     当前 robot 名称
     * @return 格式化的上下文文本
     */
    public String buildContext(List<ContactRef> contacts,
                               Map<String, AcpRobotParam> robotRegistry,
                               String selfName);
}
```

### 7.4 ContactRef（通讯录条目）

```java
public class ContactRef {
    private String name;    // 目标 robot 名称
    private String remark;  // 备注说明
}
```

### 7.5 AcpRobotParam 扩展

```java
public class AcpRobotParam {
    // ... 现有字段 ...
    private List<ContactRef> contacts;  // 新增：通讯录

    public boolean hasContacts() {
        return contacts != null && !contacts.isEmpty();
    }
}
```

## 8. 与现有系统的集成点

### 8.1 DispatchBufferFilter 扩展

新增 `talk_to` 关键词拦截：

```java
private static final String TALK_TO_TRIGGER = "talk_to";
```

构造函数新增 `talkToEnabled` 参数，或复用现有的 enabled 逻辑（只要系统中有多个 robot 就启用）。

### 8.2 AcpClient.sendPrompt() 扩展

在 turn 结束后的处理链中新增：

```java
// 检测顺序：定时任务 → 子 Agent 派发 → talkTo
if (handleScheduleAction(fullResponse, listener)) return;
if (handleSubAgentDispatch(fullResponse, listener)) return;
if (handleTalkTo(fullResponse, listener)) return;
listener.onComplete(fullResponse);
```

### 8.3 AcpProxy 初始化扩展

新增 `initTa)` 方法：

```kotlin
private fun initTalkToSupport(groupId: String, client: AcpClient, robot: AcpRobotParam?) {
    val dispatcher = TalkToDispatcher(globalRobotRegistry, registry, robotToGroupIdMap)
    val injector = TalkToContextInjector()
    client.setTalkToSupport(dispatcher, injector)
}
```

### 8.4 robotName → groupId 反向索引

当前 groupId 是 `chatterId + robotName` 的笛卡尔积。需要维护一个反向映射：

```kotlin
/** robotName → 对应的 groupId（取第一个可用的） */
private val robotToGroupIdMap = ConcurrentHashMap<String, String>()
```

在 `start()` 中构建：

```kotlin
for ((groupId, robot) in groupRobotMap) {
    if (robot != null && robot.name.isNotBlank()) {
        robotToGroupIdMap.putIfAbsent(robot.name, groupId)
    }
}
```

多 chatter 场景下，同一个 robot 可能有多个 groupId。策略：**取第一个 READY 的 client**。

## 9. 多 Chatter 场景处理

当同一个 robot 有多个 chatter 对应的 client 时：

```kotlin
fun findReadyGroupId(robotName: String): String? {
    return groupRobotMap.entries
        .filter { it.value?.name == robotName }
        .map { it.key }
        .firstOrNull { groupId ->
            registry.getClient(groupId)?.state == AbstractAcpClient.State.READY
        }
}
```

如果所有 client 都不是 READY，消息进入该 robot 的 inbox 排队。

## 10. 防循环机制

### 10.1 简单深度计数

在 talkTo 消息中携带一个不可见的 depth 字段：

```json
{
  "action": "talk_to",
  "target": "Code Chat Dev",
  "content": "...",
  "_depth": 3
}
```

- 用户发起的对话 depth = 0
- 每次 talkTo 转发时 depth + 1
- 当 depth >= MAX_DEPTH（默认 5）时，拒绝发送

### 10.2 短时间重复检测

```java
// 最近 60 秒内的 talkTo 记录
private final Map<String, Long> recentMessages = new ConcurrentHashMap<>();

private String messageKey(String sender, String target, String content) {
    return sender + "→" + target + ":" + content.hashCode();
}
```

相同 key 在 60 秒内出现第二次时，拒绝发送。

## 11. UI 事件推送

通过现有的 `AcpResponseListener` 推送 talkTo 相关事件：

```java
// 发送方收到的事件
listener.onTalkToEvent("SEND_SUCCESS", "Code Chat Dev", "消息已发送");
listener.onTalkToEvent("SEND_FAILED", "Code Chat Dev", "对方忙碌");

// 接收方收到的事件（可选，通过接收方的 listener 推送）
listener.onTalkToEvent("MESSAGE_RECEIVED", "Code-Cmd-Dev", "收到来自 Code-Cmd-Dev 的消息");
```

前端可据此展示通知卡片或消息气泡。

## 12. 完整时序图

```
┌──────────┐          ┌──────────────┐          ┌──────────┐
│ Robot A  │          │  cmd-proxy   │          │ Robot B  │
│ (BUSY)   │          │              │          │ (READY)  │
└────┬─────┘          └──────┬───────┘          └────┬─────┘
     │                       │                       │
     │ LLM 输出 talk_to JSON │                       │
     │──────────────────────>│                       │
     │                       │                       │
     │  DispatchBufferFilter │                       │
     │  拦截，不推送给用户    │                       │
     │                       │                       │
     │  turn 结束            │                       │
     │  handleTalkTo()       │                       │
     │                       │                       │
     │                       │ 查找 B 的 groupId     │
     │                       │ 检查 B.state == READY │
     │                       │                       │
     │                       │ 构造带来源的 prompt    │
     │                       │──────────────────────>│
     │                       │ B.send(prompt)        │
     │                       │                       │
     │ follow-up prompt      │                       │
     │<──────────────────────│                       │
     │ "[talkTo 结果]        │                       │
     │  已成功发送给 B"      │                       │
     │                       │                       │
     │ A 继续当前工作        │                       │
     │                       │                       │
     │                       │       B 处理消息...    │
     │                       │                       │
     │                       │ B 完成，输出 talk_to   │
     │                       │<──────────────────────│
     │                       │                       │
     │                       │ 检查 A.state          │
     │ A.send(回复 prompt)   │                       │
     │<──────────────────────│                       │
     │                       │                       │
     │                       │ follow-up prompt      │
     │                       │──────────────────────>│
     │                       │ "[talkTo 结果]        │
     │                       │  已成功发送给 A"      │
     │                       │                       │
```

## 13. 风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| 消息循环（A→B→A→B...） | 无限消耗 token 和资源 | depth 计数 + 短时间重复检测 + prompt 提示 |
| 目标 robot 长期 BUSY | talkTo 持续失败 | inbox 排队（容量上限 5），满时才失败 |
| 上下文污染 | B 正在做别的事，突然收到 A 的消息 | inbox 机制保证 B 当前 turn 结束后才投递 |
| 多 chatter 路由歧义 | 同一 robot 多个 client，发给哪个 | 取第一个 READY 的 client |
| talkTo 与 subAgent 混淆 | LLM 选错通信方式 | prompt 中明确说明两者区别和适用场景 |

## 14. 实现优先级

1. **P0 - 核心投递**：TalkToDispatcher + handleTalkTo() + DispatchBufferFilter 扩展 + Inbox 机制
2. **P0 - 上下文注入**：TalkToContextInjector + AcpRobotParam.contacts
3. **P1 - 防循环**：depth 计数 + 重复检测
4. **P1 - UI 事件**：listener 扩展 + 前端通知
5. **P2 - 多 chatter 路由**：智能选择 READY client
