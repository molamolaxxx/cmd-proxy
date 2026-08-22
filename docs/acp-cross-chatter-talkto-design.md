# 跨 Chatter TalkTo 通讯录调用设计方案（cmd-proxy 侧）

## 1. 背景与目标

当前 TalkTo 通讯录调用仅支持同一 cmd-proxy 实例内的 robot 之间通信（即同一 chatter 下的 robot）。不同 chatter 注册的 robot 无法互相调用。

### 需求

支持跨 chatter 的通讯录调用。配置格式为 `"{chatterId}:{robotName}"`，识别到此格式时走 MolaChat 网关转发，其他行为与本地调用完全一致。

### 设计原则

- 对 LLM 完全透明：LLM 不感知本地/跨 chatter 的区别
- 与本地 talk_to 行为一致：异步投递、inbox 排队、防循环
- MolaChat 作为透传网关：cmd-proxy 提供完整的发送方信息，MolaChat 负责路由和校验
- 接收侧复用现有 inbox 机制：跨 chatter 消息进入本地 TalkToDispatcher 的 inbox

## 2. 整体架构

```
┌─────────────────────┐         ┌──────────────┐         ┌─────────────────────┐
│  Chatter A          │         │   MolaChat   │         │  Chatter B          │
│  cmd-proxy          │         │   网关       │         │  cmd-proxy          │
│                     │         │              │         │                     │
│  Robot X            │         │              │         │  Robot Y            │
│  (发送方)           │         │              │         │  (接收方)           │
│                     │         │              │         │                     │
│  TalkToDispatcher   │         │              │         │  CmdReceiver        │
│  识别跨chatter格式  │─callback>│  crossTalkTo │──send──>│  crossTalkToDeliver │
│  CmdReceiver        │         │  路由+校验   │         │  投递到本地inbox    │
│  .callback()        │         │  算groupId   │         │                     │
└─────────────────────┘         └──────────────┘         └─────────────────────┘
```

### 2.1 RPC 通信方向说明

```
cmd-proxy 侧使用 CmdReceiver（provider），MolaChat 侧使用 CmdSender（consumer）。

通信方向：
- MolaChat → cmd-proxy：CmdSender.send(cmdName, groupId, args) 调用 CmdReceiver
- cmd-proxy → MolaChat：CmdReceiver.callback(cmdName, group, response) 推送给 CmdSender

跨 chatter 消息流：
1. 发送方 cmd-proxy → CmdReceiver.callback("crossTalkTo", "crossTalkTo", response)
2. MolaChat registerCallback("crossTalkTo", "crossTalkTo", lambda) 收到
3. MolaChat 路由校验后 → CmdSender.send("crossTalkToDeliver", targetGroupId, args)
4. 目标 cmd-proxy CmdReceiver 注册的 "crossTalkToDeliver" handler 收到
```

### 2.2 投递模式

火烧即忘（Fire-and-Forget）：发送方 cmd-proxy 通过 callback 推送后立即返回成功，
不等待 MolaChat 的处理结果。LLM 统一收到"消息已提交"的反馈。

## 3. 配置格式扩展

### 3.1 ContactRef 格式

```json
{
  "name": "Code-Cmd-Dev",
  "contacts": [
    {
      "name": "Code Chat Dev",
      "remark": "本地 robot，处理 MolaChat 相关问题"
    },
    {
      "name": "chatter123:Remote Robot",
      "remark": "跨 chatter 的 robot，处理远程任务"
    }
  ]
}
```

格式规则：
- `name` 不含冒号 → 本地调用（现有逻辑不变）
- `name` 含冒号且格式为 `{chatterId}:{robotName}` → 跨 chatter 调用

### 3.2 解析逻辑

```java
public class CrossChatterRef {
    private String chatterId;   // 目标 chatter ID
    private String robotName;   // 目标 robot 名称

    /**
     * 尝试从 contact name 解析跨 chatter 引用。
     * @return 解析成功返回 CrossChatterRef，本地引用返回 null
     */
    public static CrossChatterRef parse(String contactName) {
        if (contactName == null || !contactName.contains(":")) return null;
        int colonIdx = contactName.indexOf(':');
        String chatterId = contactName.substring(0, colonIdx).trim();
        String robotName = contactName.substring(colonIdx + 1).trim();
        if (chatterId.isEmpty() || robotName.isEmpty()) return null;
        return new CrossChatterRef(chatterId, robotName);
    }
}
```

## 4. 发送侧改造

### 4.1 TalkToDispatcher.deliver() 分支

在现有 `deliver()` 方法中，增加跨 chatter 判断分支：

```java
public String deliver(TalkToRequest request, String senderName, String senderChatterId, String senderGroupId) {
    String target = request.getTarget();
    
    // 判断是否为跨 chatter 调用
    CrossChatterRef crossRef = CrossChatterRef.parse(target);
    if (crossRef != null) {
        return deliverCrossChatter(crossRef, request, senderName, senderChatterId);
    }
    
    // 原有本地投递逻辑不变...
}
```

### 4.2 跨 chatter 投递实现（火烧即忘）

```java
private String deliverCrossChatter(CrossChatterRef crossRef, TalkToRequest request,
                                    String senderName, String senderChatterId) {
    String content = request.getContent();
    int depth = request.getDepth();

    // 1. 防循环：深度检查（与本地一致）
    if (depth >= MAX_DEPTH) {
        return "[talkTo 结果]\n发送失败：消息传递深度超过上限（" + MAX_DEPTH + "），可能存在循环。已终止发送。";
    }

    // 2. 防循环：短时间重复检测（与本地一致）
    String dedupKey = senderName + "→" + crossRef.getRobotName() + "@" + crossRef.getChatterId() + ":" + content.hashCode();
    Long lastSent = recentMessages.get(dedupKey);
    long now = System.currentTimeMillis();
    if (lastSent != null && (now - lastSent) < DEDUP_WINDOW_MS) {
        return "[talkTo 结果]\n发送失败：短时间内向 " + crossRef.getRobotName() + " 发送了相同内容，已阻止重复发送。";
    }

    // 3. 记录发送记录
    recentMessages.put(dedupKey, now);
    cleanExpiredDedup();

    // 4. 通过 CmdReceiver.callback 推送到 MolaChat 网关（火烧即忘）
    try {
        Map<String, String> resultMap = new HashMap<>();
        resultMap.put("targetChatterId", crossRef.getChatterId());
        resultMap.put("targetRobotName", crossRef.getRobotName());
        resultMap.put("senderChatterId", senderChatterId);
        resultMap.put("senderRobotName", senderName);
        resultMap.put("content", content);
        resultMap.put("depth", String.valueOf(depth));

        CmdResponseContent response = new CmdResponseContent(
            UUID.randomUUID().toString(), resultMap
        );
        CmdReceiver.callback("crossTalkTo", "crossTalkTo", response);

        return "[talkTo 结果]\n已成功将消息发送给 " + crossRef.getRobotName()
            + "（跨服务器）。对方会处理你的请求，你可以继续当前工作。";
    } catch (Exception e) {
        logger.error("crossTalkTo callback 发送失败", e);
        return "[talkTo 结果]\n发送失败：网关通信异常 - " + e.getMessage();
    }
}
```

### 4.3 发送方上下文信息

TalkToDispatcher 需要知道当前 cmd-proxy 的 chatterId，用于构造 crossTalkTo 参数中的 senderChatterId。

由于 TalkToDispatcher 是全局单例，而 deliver() 是由不同 robot 的 AcpClient 调用的，senderChatterId 需要作为 deliver() 的参数传入。AcpClient 在初始化时从 groupId 中提取 chatterId。

## 5. 接收侧改造

### 5.1 注册命令处理器

在 `AcpProxy.start()` 中注册两个命令：

```kotlin
// 1. 建立 crossTalkTo callback 通道（发送跨 chatter 消息到 MolaChat）
CmdReceiver.register("crossTalkTo", "crossTalkTo") { params ->
    // dummy handler，实际不会被 MolaChat 调用（MolaChat 不会 send crossTalkTo 到 cmd-proxy）
    // 注册的目的是建立 RPC 连接，使 CmdReceiver.callback() 有可用的 consumer
    mutableMapOf<String, String>("result" to "ok")
}

// 2. 接收 MolaChat 转发的跨 chatter 消息
CmdReceiver.register("crossTalkToDeliver", cmdGroupList, "接收跨chatter的talkTo消息") { params ->
    val resultMap = mutableMapOf<String, String>()
    try {
        val args = params.cmdArgs
        val senderChatterId = args[0]
        val senderRobotName = args[1]
        val content = args[2]
        val depth = args[3].toIntOrNull() ?: 0

        // MolaChat 已路由到正确的 group，通过 routeTag 确定目标 robot
        val targetClient = registry.getClientByRouteTag(params)
        val targetRobot = targetClient?.robotParam

        if (targetClient == null || targetRobot == null) {
            resultMap["result"] = "目标 robot 不存在或未启动"
            resultMap["success"] = "false"
            return@register resultMap
        }

        // 构造 TalkToMessage（sender 带上 chatterId 前缀，方便回复时路由）
        val senderFullName = "$senderChatterId:$senderRobotName"
        val message = TalkToMessage(senderFullName, content, depth + 1)

        // 投递到本地 inbox 或直接发送
        if (targetClient.state == AbstractAcpClient.State.READY) {
            talkToDispatcher.pushIncomingMessageCard(targetClient, senderRobotName, content)
            targetClient.send(message.buildPrompt(), null)
            resultMap["result"] = "已直接投递"
            resultMap["success"] = "true"
        } else {
            val delivered = talkToDispatcher.offerToInbox(targetRobot.name, message)
            if (delivered) {
                resultMap["result"] = "目标忙碌，已放入 inbox"
                resultMap["success"] = "true"
            } else {
                resultMap["result"] = "目标 inbox 已满"
                resultMap["success"] = "false"
            }
        }
    } catch (e: Exception) {
        log.error("crossTalkToDeliver 处理失败", e)
        resultMap["result"] 常: ${e.message}"
        resultMap["success"] = "false"
    }
    resultMap
}
```

### 5.2 TalkToMessage 适配

接收到跨 chatter 消息时，sender 格式为 `"{chatterId}:{robotName}"`。TalkToMessage.buildPrompt() 中展示给 LLM 的发送方名称只显示 robotName 部分（对 LLM 友好），但回复要带完整的 `{chatterId}:{robotName}` 格式。

```java
public String buildPrompt() {
    String displayName = extractDisplayName(sender);  // 只取 robotName 部分
    StringBuilder sb = new StringBuilder();
    sb.append("[Incoming Message]\n");
    sb.append("来自: ").append(displayName).append("\n");
    sb.append("内容: ").append(content).append("\n\n");
    sb.append("如果你完成任务后需要回复对方，在回复中输出以下 JSON：\n");
    sb.append("{\"action\":\"talk_to\",\"target\":\"").append(sender)
            .append("\",\"content\":\"你的回复内容\"}\n");
    sb.append("如果不需要回复，正常完成任务即可。\n");
    return sb.toString();
}

private String playName(String sender) {
    if (sender != null && sender.contains(":")) {
        return sender.substring(sender.indexOf(':') + 1);
    }
    return sender;
}
```

## 6. TalkToContextInjector 适配

### 6.1 通讯录展示

跨 chatter 的联系人在 prompt 中展示时，隐藏 chatterId 前缀，只展示 robotName + remark：

```java
public String buildContext(List<ContactRef> contacts, ...) {
    // ...
    for (ContactRef contact : contacts) {
        String displayName = contact.getName();
        // 跨 chatter 联系人：展示 robotName 部分
        if (displayName.contains(":")) {
            displayName = displayName.substayName.indexOf(':') + 1);
        }
        sb.append("- ").append(displayName);
        // ...
    }
    // ...
}
```

### 6.2 LLM 输出的 target 格式

LLM 输出 talk_to 时，target 需要带完整的 `{chatterId}:{robotName}` 格式才能正确路由。两种方案：

**方案 A（推荐）：prompt 中直接展示完整格式**

通讯录中展示完整的 `chatterId:robotName`，LLM 输出时自然带上。简单直接，无歧义。

```
通讯录：
- Code Chat Dev: 本地 robot，处理 MolaChat 相关问题
- chatter123:Remote Robot: 跨服务器 robot，处理远程任务
```

**方案 B：prompt 中展示别名，dispatcher 做映射**

通讯录中只展示 robotName，TalkToDispatcher 维护一个 displayName → fullName 的映射表。但如果不同 chatter 有同名 robot 会产生歧义。

**结论：采用方案 A**，直接展示完整格式。LLM 足够聪明，能理解 `chatterId:robotName` 是一个标识符。

### 6.3 跨 chatter 联系人的校验

本地联系人需要在 `robotRegistry` 中存在才展示。跨 chatter 联系人无法本地校验，直接展示（校验由 MolaChat 网关负责）：

```java
for (ContactRef contact : contacts) {
    if (contact.getName() == null || contact.getName().isEmpty()) continue;
    if (contact.getName().equals(selfName)) continue;

    CrossChatterRef crossRef = CrossChatterRef.parse(contact.getName());
    if (crossRef == null) {
        // 本地联系人：需要在 registry 中存在
        if (!robotRegistry.containsKey(contact.getName())) {
            logger.warn("通讯录引用 '{}' 在 robot 注册表中不存在，跳过", contact.getName());
            continue;
        }
    }
    // 跨 chatter 联系人：不做本地校验，直接展示
    // ...
}
```

## 7. TalkToDispatcher 改造汇总

### 7.1 新增依赖

TalkToDispatcher 需要新增以下信息：
- `senderChatterId`：当前 cmd-proxy 的 chatterId（用于构造 crossTalkTo 参数）
- `senderGroupId`：当前 robot 的 groupId（用于 CmdSender.send 的 cmdGroup 参数）

由于 TalkToDispatcher 是全局单例，而 deliver() 是由不同 robot 的 AcpClient 调用的，senderChatterId 和 senderGroupId 需要作为 deliver() 的参数传入：

```java
public String deliver(TalkToRequest request, String senderName, String senderChatterId, String senderGroupId) {
    // ...
}
```

### 7.2 新增公开方法

```java
/**
 * 将消息放入目标 robot 的 inbox（供 crossTalkToDeliver 命令处理器调用）。
 * @return true 入队成功，false inbox 已满
 */
public boolean offerToInbox(String robotName, TalkToMessage message) {
    LinkedBlockingQueue<TalkToMessage> inbox = inboxes.computeIfAbsent(
            robotName, k -> new LinkedBlockingQueue<>(INBOX_CAPACITY));
    return inbox.offer(message);
}
```

## 8. 消息流转完整时序

### 8.1 发送流程（跨 chatter）

```
Robot X (Chatter A) 的 LLM 输出:
  {"action":"talk_to","target":"chatterB:Robot Y","content":"请帮我..."}

→ DispatchBufferFilter 拦截
→ turn 结束后 handleTalkTo()
→ TalkToDispatcher.deliver() 识别到 "chatterB:Robot Y" 含冒号
→ CrossChatterRef.parse() 解析出 chatterId=chatterB, robotName=Robot Y
→ deliverCrossChatter():
    - 防循环检查（depth + 去重）
    - CmdSender.send("crossTalkTo", groupIdOfX, [chatterB, "Robot Y", chatterA, "Robot X", content, depth])
→ MolaChat 网关收到:
    - 校验 chatterB 存在
    - 校验 Robot Y 属于 chatterB
    - 计算 targetGroupId = sort(chatterB, "acp-Robot_Y").join("")
    - CmdSender.send("crossTalkToDeliver", targetGroupId, [chatterA, "Robot X", content, depth])
→ Chatter B 的 cmd-proxy CmdReceiver 收到:
    - 解析参数
    - 构造 TalkToMessage(sender="chatterA:Robot X", content, depth+1)
    - 目标 READYetClient.send(message.buildPrompt())
    - 目标 BUSY → offerToInbox()
→ 发送方收到 CmdInvokeResponse（成功/失败）
→ 结果作为 follow-up prompt 回注 Robot X
```

### 8.2 回复流程（反向跨 chatter）

```
Robot Y (Chatter B) 收到消息后处理完毕，LLM 输出:
  {"action":"talk_to","target":"chatterA:Robot X","content":"处理完了，结果是..."}

→ 走完全相同的跨 chatter 发送流程（方向相反）
→ 最终投递到 Chatter A 的 Robot X inbox
```

## 9. 与 MolaChat 侧的接口约定

### 9.1 cmd-proxy → MolaChat（callback 通道）

- 命令名：`crossTalkTo`
- group：`crossTalkTo`（固定值，所有 cmd-proxy 实例共用）
- 数据格式：CmdResponseContent.resultMap 包含：
  - `targetChatterId`: 目标 chatter ID
  - `targetRobotName`: 目标 robot 名称
  - `senderChatterId`: 发送方 chatter ID
  - `senderRobotName`: 发送方 robot 名称
  - `content`: 消息内容
  - `depth`: 递归深度

- 投递模式：火烧即忘，无返回值

### 9.2 MolaChat → cmd-proxy（send 通道）

- 命令名：`crossTalkToDeliver`
- cmdGroup：目标 robot 的 groupId（MolaChat 计算）
- 参数（String[]）：
  - [0] senderChatterId
  - [1] senderRobotName
  - [2] content
  - [3] depth

- 返回：resultMap 包含：
  - `success`: "true" / "false"
  - `result`: 结果描述

### 9.3 groupId 计算规则

```
acpId = "acp-" + robotName.replace(" ", "_").replace("\u3000", "_")
groupId = listOf(chatterId, acpId).sorted().joinToString("")
```

## 10. 改动文件清单

| 文件 | 改动内容 |
|------|----------|
| `model/CrossChatterRef.java` | **新增**：跨 chatter 引用解析模型 |
| `TalkToDispatcher.java` | 新增 `deliverCrossChatter()` 方法、`offerToInbox()` 公开方法、deliver() 签名扩展 |
| `TalkToContextInjector.java` | 跨 chatter 联系人展示适配（跳过本地 registry 校验） |
| `model/TalkToMessage.java` | `buildPrompt()` 中 sender 显示名提取（去掉 chatterId 前缀） |
| `AcpProxy.kt` | 注册 `crossTalkTo`（dummy，建立 callback 通道）和 `crossTalkToDeliver` 命令处理器 |
| `AcpClient.java` | `handleTalkTo()` 调用 deliver() 时传入 senderChatterId 和 senderGroupId |

## 11. 不变的部分

以下现有逻辑完全不变：
- 本地 talk_to 的完整流程（识别到无冒号的 target 时走原逻辑）
- DispatchBufferFilter 的拦截机制
- inbox 队列管理和自动投递
- 防循环机制（depth + 去重）
- UI 事件推送（onTalkToEvent）
- LLM 侧的 talk_to JSON 格式（action/target/content）
