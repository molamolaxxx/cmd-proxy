# ACP 定时任务系统设计方案

## 1. 背景与目标

在 Agent 日常使用中，用户经常有周期性或延迟执行的需求，例如：
- 每天早上生成 AI 早报
- 每周五下午汇总本周开发进展
- 30 分钟后提醒我做某件事

当前 cmd-proxy 没有定时任务能力，所有任务都依赖用户主动发起对话触发。

### 设计目标

1. Agent 能识别用户的定时意图，通过约定 JSON 设置定时任务
2. 定时任务持久化到磁盘（robot 维度），重启不丢失
3. 内置调度执行器定时扫描，到时间自动触发
4. 触发时由主 Agent 自己执行（不走 subagent 派发）
5. Agent 能查询、取消、修改已有的定时任务

### 与 Sub-Agent 派发的关系

定时任务系统参考 Sub-Agent 派发的交互机制（约定 JSON + 输出检测拦截 + UI 推送），但执行方式不同：

| 维度     | Sub-Agent 派发                | 定时任务                         |
|----------|-------------------------------|----------------------------------|
| 执行者   | 子 Agent（独立 AcpClient）    | 主 Agent 自己                    |
| 触发方式 | LLM 输出 JSON 后立即执行      | LLM 输出 JSON 后存储，调度器延迟触发 |
| 交互机制 | 约定 JSON + 检测拦截（复用）  | 约定 JSON + 检测拦截（复用）     |

## 2. 整体流程

```
用户对话 → LLM 输出 schedule_task JSON → DispatchBufferFilter 拦截解析
                                                    ↓
                                          ScheduleTaskManager 持久化到磁盘 (robot 维度)
                                                    ↓
                                          调度线程每分钟扫描 → nextRunAt <= now?
                                                    ↓ 是
                                          构造 prompt → AcpClient.sendPrompt() → 主 Agent 执行
                                                    ↓
                                          结果通过现有 listener 推送给用户
```

## 3. 任务状态机

```
                    ┌─────────────────────────────────┐
                    │                                 │
                    ▼                                 │
  [创建] ──→ WAITING ──→ RUNNING ──→ (执行完成)      │
                │              │         │            │
                │              │         ├─ cron 任务: 重算 nextRunAt，回到 WAITING
                │              │         └─ once 任务: 从 tasks.json 删除
                │              │
                │              └─→ FAILED ──→ 从 tasks.json 删除
                │
                ├─→ MISSED (启动时发现 nextRunAt < now)
                │     ├─ cron 任务: 重算 nextRunAt，回到 WAITING
                │     └─ once 任务: 删除
                │
                └─→ CANCELLED (用户取消) ──→ 从 tasks.json 删除
```

### 状态说明

| 状态        | 含义                                   | 后续                                       |
|-------------|----------------------------------------|--------------------------------------------|
| `WAITING`   | 等待触发                               | 调度器扫描到 nextRunAt <= now 时转 RUNNING  |
| `RUNNING`   | 正在执行                               | 执行完成后 cron 回 WAITING，once 删除       |
| `FAILED`    | 执行失败                               | 删除                                       |
| `MISSED`    | 进程不在期间错过了执行时间（瞬态状态） | cron 重算回 WAITING，once 删除              |
| `CANCELLED` | 用户主动取消（瞬态状态）               | 删除                                       |

核心原则：**tasks.json 里只保留 `WAITING` 和 `RUNNING` 状态的任务**，其他状态处理完立即删除。

### update 操作

update 不是独立状态，是 WAITING 状态下的一个操作。更新字段后任务保持 WAITING，如果改了 schedule 则重算 `nextRunAt`。

## 4. Robot 配置

在 robot 配置中新增 `scheduleEnabled` 字段，控制该 robot 是否启用定时任务能力：

```json
{
  "name": "主Agent",
  "workDir": "/project-a",
  "scheduleEnabled": true
}
```

- 默认值：`true`（不配置时默认启用）
- 设为 `false` 时，`ScheduleContextInjector` 不注入任何定时任务相关内容，Agent 完全不感知定时任务能力

## 5. Prompt 注入（ScheduleContextInjector）

`ScheduleContextInjector` 在以下两种情况下**不注入**定时任务上下文：

1. robot 配置 `scheduleEnabled = false`
2. 当前对话是定时任务触发的执行场景（防止套娃：定时任务执行时 Agent 又设置新的定时任务）

判断是否为定时任务执行场景：`ScheduleTaskManager` 触发执行时，在 sendPrompt 调用中携带一个标记（如 `isScheduleExecution = true`），`ScheduleContextInjector` 检查该标记决定是否注入。

正常用户对话时，`ScheduleContextInjector` 向主 Agent prompt 注入定时任务的能力描述和操作格式。**不默认列出所有任务**，Agent 需要时通过 `list` 操作主动查询。

注入内容：

```
[定时任务]
你可以为用户设置和管理定时任务。

设置任务：
{"action":"schedule_task","tasks":[{"title":"任务标题","prompt":"执行内容","schedule":{"type":"cron|once","expr":"cron表达式或ISO时间戳"}}]}

查询任务列表：
{"action":"manage_schedule","operation":"list"}

按ID操作任务（取消或更新）：
{"action":"manage_schedule","operation":"cancel|update","taskId":"任务ID","updates":{"title":"...","prompt":"...","schedule":{...}}}
```

## 5. JSON 指令定义

### 5.1 创建定时任务（schedule_task）

```json
{
  "action": "schedule_task",
  "tasks": [
    {
      "title": "每日TODO检查",
      "prompt": "检查项目中未处理的 TODO 注释，汇总报告",
      "schedule": {
        "type": "cron",
        "expr": "0 9 * * *"
      }
    }
  ]
}
```

`schedule.type` 支持两种：
- `cron`：标准 cron 表达式，周期性任务
- `once`：一次性任务，`expr` 为 ISO 时间戳（如 `2026-05-06T09:00:00`）或相对时间（如 `+30m`）

### 5.2 管理定时任务（manage_schedule）

**查询列表：**

```json
{
  "action": "manage_schedule",
  "operation": "list"
}
```

**取消任务：**

```json
{
  "action": "manage_schedule",
  "operation": "cancel",
  "taskId": "daily-todo-check"
}
```

**更新任务（只放需要改的字段）：**

```json
{
  "action": "manage_schedule",
  "operation": "update",
  "taskId": "daily-todo-check",
  "updates": {
    "title": "每日代码审查",
    "prompt": "检查最近的 git commit，找出潜在问题",
    "schedule": { "type": "cron", "expr": "0 10 * * *" }
  }
}
```

### 5.3 list 操作的返回格式

`ScheduleTaskManager` 处理 `list` 后，构造文本作为 follow-up 注入给 Agent：

```
[定时任务列表]
共 2 个任务：

1. ID: daily-todo-check
   标题: 每日TODO检查
   内容: 检查项目中未处理的 TODO 注释，汇总报告
   调度: cron 0 9 * * * (每天 09:00)
   状态: WAITING
   下次执行: 2026-05-06 09:00

2. ID: weekly-report
   标题: 周报生成
   内容: 汇总本周的开发进展，生成周报
   调度: cron 0 18 * * 5 (每周五 18:00)
   状态: WAITING
   下次执行: 2026-05-09 18:00
```

Agent 拿到结果后自然语言转述给用户。用户说"取消第一个"，Agent 输出对应的 cancel JSON。

## 6. 持久化

### 存储路径

```
~/.cmd-proxy/schedules/{robotName}/tasks.json
```

每个 robot 独立一个文件。

### 数据模型

```json
[
  {
    "id": "t1746374400_daily-todo-check",
    "title": "每日TODO检查",
    "prompt": "检查项目中未处理的 TODO 注释，汇总报告",
    "schedule": { "type": "cron", "expr": "0 9 * * *" },
    "status": "WAITING",
    "createdAt": 1746374400000,
    "lastRunAt": null,
    "nextRunAt": 1746406800000
  }
]
```

### taskId 生成策略

由 `ScheduleTaskManager` 自动生成，格式为 `t{时间戳秒数}_{title-slug}`。LLM 输出的 JSON 中不需要带 id 字段。

- 时间戳前缀保证唯一性
- title slug 保证可读性（取 title 的拼音/英文小写，特殊字符替换为 `-`）

## 7. 调度执行器（ScheduleTaskManager）

### 启动时

1. 扫描 `~/.cmd-proxy/schedules/` 下所有 robot 的 tasks.json，加载到内存
2. 检查 MISSED 任务：`nextRunAt < now` 且状态为 `WAITING`
   - cron 任务：重算 nextRunAt，回到 WAITING
   - once 任务：删除

### MISSED 判定

MISSED 仅在**进程启动时**检测，用于处理进程不在期间错过的任务。正常运行中扫描发现 `nextRunAt <= now` 的任务直接触发执行，不算 MISSED。

### 调度线程

`ScheduledExecutorService` 每 60 秒扫描一次（初始延迟 30 秒）：
- `nextRunAt <= now` 且状态为 `WAITING` → 转 `RUNNING`，触发执行
- 最大延迟不超过 1 分钟，对定时任务场景可接受

### 并发控制

- 同一个 robot 的定时任务**串行执行**（一个执行完才触发下一个）
- 不同 robot 的定时任务**可并行执行**
- 无需设置并发数上限

串行机制：调度器扫描时，如果该 robot 已有 RUNNING 状态的任务，则跳过本轮不触发其他任务，保持 WAITING。下一分钟再次扫描时重新检查。不维护显式队列，扫描机制本身就是隐式重试。

对于 cron 任务自身的周期冲突（执行时间超过了下一个周期）：因为任务处于 RUNNING 状态不会重复触发，执行完成后重算 nextRunAt 到下一个未来时间点，不补执行错过的周期。

### 执行超时

无超时限制。定时任务和普通对话一样，Agent 执行完为止。

### 触发执行

`ScheduleTaskManager.triggerExecution()` 异步调用 `ScheduleExecutionCallback.execute()`，回调实现在 `AcpProxy.startScheduler()` 中：

1. 通过 `groupRobotMap` 找到 robot 对应的 groupId
2. 获取主 client，检查 `client.state`：
   - **非 READY**（忙碌）：返回 false，任务回退 WAITING，下轮重试
   - **READY**（空闲）：继续执行
3. 在主 client 上调用 `registry.createSession()` 新建 session
4. 重新初始化各能力：`initMemoryForClient`、`initAbilityReflection`、`initSubAgentDispatcher`、`initScheduleSupport`
5. 调用 `newClient.send(prompt, null, PromptOptions.forScheduleExecution())` 发送执行

构造的 prompt 格式：

```
[定时任务触发] 任务: 每日TODO检查

检查项目中未处理的 TODO 注释，汇总报告
```

**关键设计决策：**

- **在主 client 上新建 session**，而非创建独立临时 client。执行完成后主 client 的当前 session 即为定时任务的 session。
- **流式推送**：执行过程复用主 client 的 `DefaultAcpResponseListener`，结果流式推送到前端，无独立的静默 buffer 机制。
- **防套娃**：`PromptOptions.forScheduleExecution()` 标记 → `ScheduleContextInjector` 检测到后不注入定时任务能力。
- 所有能力（MCP 工具、子 Agent 派发、记忆系统、Skills）均可用。

执行完成后按状态机流转：cron 重算 nextRunAt 回 WAITING，once 删除，failed 删除。

### 用户不在线时

不在 cmd-proxy 层处理离线消息。定时任务执行结果走正常的 `DefaultAcpResponseListener` 推送，离线送达由下游（MolaChat）负责。

## 8. 拦截机制

复用 `DispatchBufferFilter` 的状态机检测模式，扩展对 `schedule_task` 和 `manage_schedule` 两个 action 的识别。检测到后交给 `ScheduleTaskManager` 处理，不推送原始 JSON 给用户。

扩展方式：在现有 `DispatchBufferFilter` 中加 switch 分支，根据 action 字段路由到不同处理方法，不做过度抽象：

```java
switch (action) {
    case "dispatch_subagent" -> handleSubagentDispatch(json);
    case "schedule_task" -> handleScheduleTask(json);
    case "manage_schedule" -> handleManageSchedule(json);
}
```

### manage_schedule 操作结果注入

`list`、`cancel`、`update` 操作执行完后，结果通过递归调用 `sendPrompt()` 注入回主 Agent（复用 subagent 结果回传的模式），触发主 Agent 第二轮推理，自然语言转述给用户。

## 9. UI 展示

| 场景                         | 默认展开 | 说明                                           |
|------------------------------|----------|------------------------------------------------|
| `schedule_task` 创建任务     | 展开明细 | 和 subagent 派发一致，用户需确认任务内容和调度规则 |
| `manage_schedule` list       | 收起明细 | 结果由 Agent 自然语言转述，原始数据不需要展示   |
| `manage_schedule` cancel     | 收起明细 | 简单操作，Agent 回复"已取消"即可                |
| `manage_schedule` update     | 收起明细 | 简单操作，Agent 回复更新结果即可                |
| 定时任务触发执行             | 无专门UI | 就是一次普通 ACP 对话，走现有消息流             |

## 10. 新增/修改的类

```
新增：
├── schedule/
│   ├── ScheduleContextInjector.java    // prompt 注入定时任务能力
│   ├── ScheduleTaskManager.java        // 持久化 + 调度线程 + 触发执行 + 任务管理
│   └── model/
│       ├── ScheduledTask.java          // 任务数据模型
│       └── ScheduleConfig.java         // 调度配置（type + expr）

修改：
├── DispatchBufferFilter.java           // 扩展检测 schedule_task / manage_schedule
├── AcpClient.java                      // turn 结束后新增 handleScheduleTask() 分支
├── AcpProxy.kt                         // 初始化 ScheduleTaskManager，启动调度线程
```

## 11. 已确认决策

| 事项           | 决策                                                                 |
|----------------|----------------------------------------------------------------------|
| 执行时的会话   | 在主 client 上 `registry.createSession()` 新建 session，非独立临时 client |
| 执行结果推送   | 复用主 client 的 `DefaultAcpResponseListener` 流式推送，无静默 buffer |
| cron 解析      | 引入 `cron-utils` 库                                                 |
| 并发控制       | 同 robot 串行（`runningByRobot` ConcurrentHashMap），不同 robot 并行  |
| 串行机制       | 扫描时该 robot 有 RUNNING 任务则跳过，下次扫描重试，不维护显式队列   |
| client 忙碌处理 | 返回 false，任务回退 WAITING 并清除 running 标记，下轮重试           |
| 执行超时       | 无超时限制，和普通对话一样执行完为止                                 |
| 扫描间隔       | 60 秒（初始延迟 30 秒），最大延迟不超过 1 分钟                       |
| 任务生命周期   | 完成/失败/取消后直接从 tasks.json 删除，不归档                       |
| 防套娃         | `PromptOptions.forScheduleExecution()` + `ScheduleContextInjector` 检测跳过 |
| robot 配置     | `scheduleEnabled` 默认 true，设为 false 时不注入定时任务能力         |
| taskId 生成    | 由 ScheduleTaskManager 生成，格式 `t{时间戳秒数}_{title-slug}`，LLM 不提供 id |
| 操作结果注入   | list/cancel/update 结果通过递归 sendPrompt() 注入，复用 subagent 模式 |
| 离线消息       | 不在 cmd-proxy 层处理，由下游 MolaChat 负责离线送达                  |
| 执行失败定义   | 仅 ACP session 非正常中断算 FAILED（连接失败、网络断开、进程崩溃），Agent 正常回复即为成功 |
| Filter 扩展    | 在 DispatchBufferFilter 中加 switch 分支路由，不做注册框架抽象       |
| 进程存活       | 无需额外处理                                                         |
| 能力重初始化   | 新建 session 后重新 init memory/ability/subagent/schedule 各能力     |
