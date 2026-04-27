package com.mola.cmd.proxy.app.acp

import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import com.mola.cmd.proxy.app.acp.ability.AbilityReflectionService
import com.mola.cmd.proxy.app.acp.acpclient.AbstractAcpClient
import com.mola.cmd.proxy.app.acp.acpclient.AcpClient
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientRegistry
import com.mola.cmd.proxy.app.acp.acpclient.agent.AgentProviderRouter
import com.mola.cmd.proxy.app.acp.common.PathUtils
import com.mola.cmd.proxy.app.acp.memory.MemoryManager
import com.mola.cmd.proxy.app.acp.memory.model.MemoryConfig
import com.mola.cmd.proxy.app.acp.subagent.SubAgentContextInjector
import com.mola.cmd.proxy.app.acp.subagent.SubAgentDispatcher
import com.mola.cmd.proxy.client.provider.CmdReceiver
import com.mola.cmd.proxy.client.resp.CmdResponseContent
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object AcpProxy {

    private val log: Logger = LoggerFactory.getLogger(AcpProxy::class.java)

    private val registry: AcpClientRegistry = AcpClientRegistry.getInstance()

    /** groupId -> MemoryManager，只有开启记忆的 robot 对应的 groupId 才有值 */
    private val memoryManagers = ConcurrentHashMap<String, MemoryManager>()

    /** groupId -> AbilityReflectionService */
    private val abilityServices = ConcurrentHashMap<String, AbilityReflectionService>()

    /** 全局 robot 注册表，name -> AcpRobotParam */
    private val globalRobotRegistry = ConcurrentHashMap<String, AcpRobotParam>()

    fun start(
        cmdGroupList: List<String>,
        robotsJson: String? = null,
        chatterIdsJson: String? = null,
        groupWorkDirMap: Map<String, String> = emptyMap(),
        groupRobotMap: Map<String, AcpRobotParam> = emptyMap()
    ) {
        // 注册命令
        CmdReceiver.register("acp", "acp") { param ->
            mutableMapOf<String, String>()
        }

        CmdReceiver.register("acpSyncRobots", "acpSyncRobots") { param ->
            mutableMapOf<String, String>()
        }

        // 构建全局 robot 注册表（在冷加载之前，供子 Agent 派发使用）
        for ((_, robot) in groupRobotMap) {
            if (robot != null && robot.name.isNotBlank()) {
                globalRobotRegistry[robot.name] = robot
            }
        }

        // 冷加载：启动时为每个groupId预创建client
        for (groupId in cmdGroupList) {
            try {
                val workDir = groupWorkDirMap[groupId]
                val robot = groupRobotMap[groupId]

                // onlySubAgent 的 robot 不启动 AcpClient，只做 ability 反思
                if (robot != null && robot.isOnlySubAgent) {
                    initAbilityReflectionStandalone(groupId, robot)
                    log.info("onlySubAgent robot 跳过 client 创建, groupId={}, robot={}", groupId, robot.name)
                    continue
                }

                registry.createSession(groupId, workDir, robot)

                val client = registry.getClient(groupId) ?: continue

                // 按 robot 维度初始化记忆
                initMemoryForClient(groupId, client, robot)

                // 初始化能力反思
                initAbilityReflection(groupId, client, robot)

                // 初始化子 Agent 派发
                initSubAgentDispatcher(groupId, client, robot)

                log.info("ACP client 冷加载完成, groupId={}, robot={}, workDir={}, memory={}, subAgents={}",
                    groupId, robot?.name ?: "unknown", workDir ?: "default",
                    robot?.isMemoryEnabled ?: false,
                    robot?.subAgents?.map { it.name } ?: emptyList<String>())
            } catch (e: Exception) {
                log.error("ACP client 冷加载失败, groupId={}", groupId, e)
            }
        }

        // 会话注册完成后，调用 acpSyncRobots 通知服务端同步 robot 信息
        if (!robotsJson.isNullOrBlank() && !chatterIdsJson.isNullOrBlank() && cmdGroupList.isNotEmpty()) {
            try {
                val resultMap = mutableMapOf<String, String?>()
                resultMap["robots"] = robotsJson
                resultMap["visibleChatterIds"] = chatterIdsJson
                CmdReceiver.callback(
                    "acpSyncRobots", "acpSyncRobots",
                    CmdResponseContent(UUID.randomUUID().toString(), resultMap)
                )
                log.info("acpSyncRobots 回调已发送")
            } catch (e: Exception) {
                log.error("acpSyncRobots 回调发送失败", e)
            }
        }

        CmdReceiver.register("acpCancelPrompt", cmdGroupList, "取消ACP当前正在进行的prompt turn，groupId必填") { params ->
            val resultMap = mutableMapOf<String, String>()
            try {
                val param: JSONObject = JSON.parse(params.cmdArgs[0]) as JSONObject
                val groupId = param.getString("groupId")
                if (groupId.isNullOrBlank()) {
                    resultMap["result"] = "groupId不能为空"
                    return@register resultMap
                }
                registry.cancelPrompt(groupId)
                resultMap["result"] = "已发送取消指令，groupId=$groupId"
            } catch (e: Exception) {
                log.error("acpCancelPrompt 失败", e)
                resultMap["result"] = "取消失败: ${e.message}"
            }
            resultMap
        }

        CmdReceiver.register("acpNewSession", cmdGroupList, "会话上下文清除，groupId必填") { params ->
            val resultMap = mutableMapOf<String, String>()
            try {
                val param: JSONObject = JSON.parse(params.cmdArgs[0]) as JSONObject
                val groupId = param.getString("groupId")
                if (groupId.isNullOrBlank()) {
                    resultMap["result"] = "groupId不能为空"
                    return@register resultMap
                }
                val client = registry.getClient(groupId)
                if (client == null || client.state != AbstractAcpClient.State.READY) {
                    resultMap["result"] = "当前client状态不为READY，不允许清除上下文"
                    return@register resultMap
                }

                // close() 内部会自动触发记忆提取
                registry.createSession(groupId, null, null)

                // 重新初始化记忆管理器
                val newClient = registry.getClient(groupId)
                if (newClient != null) {
                    initMemoryForClient(groupId, newClient, newClient.robotParam)
                    initAbilityReflection(groupId, newClient, newClient.robotParam)
                    initSubAgentDispatcher(groupId, newClient, newClient.robotParam)
                }

                resultMap["result"] = "已开启新会话"
            } catch (e: Exception) {
                log.error("acpNewSession 失败", e)
                resultMap["result"] = "开启新会话失败: ${e.message}"
            }
            resultMap
        }

        CmdReceiver.register("acpSendMessage", cmdGroupList, "向ACP会话发送消息，groupId和message必填，files可选(数组，每个元素是{文件名:base64}的map)") { params ->
            val resultMap = mutableMapOf<String, String>()
            try {
                val param: JSONObject = JSON.parse(params.cmdArgs[0]) as JSONObject
                val groupId = param.getString("groupId")
                val message = param.getString("message")
                if (groupId.isNullOrBlank()) {
                    resultMap["result"] = "groupId不能为空"
                    return@register resultMap
                }
                if (message.isNullOrBlank()) {
                    resultMap["result"] = "message不能为空"
                    return@register resultMap
                }
                val files: MutableList<Map<String, String>>? = if (param.containsKey("files")) {
                    param.getJSONArray("files")?.map { item ->
                        val jsonObj = item as JSONObject
                        val map = mutableMapOf<String, String>()
                        for (key in jsonObj.keys) {
                            map[key] = jsonObj.getString(key)
                        }
                        map as Map<String, String>
                    }?.toMutableList()
                } else null
                registry.sendMessage(groupId, message, files)
                resultMap["result"] = "消息发送成功"
            } catch (e: Exception) {
                log.error("acpSendMessage 失败", e)
                resultMap["result"] = "发送消息失败: ${e.message}"
            }
            resultMap
        }

        CmdReceiver.register("acpGetStatus", cmdGroupList, "获取ACP会话状态，groupId必填") { params ->
            val resultMap = mutableMapOf<String, String>()
            try {
                val param: JSONObject = JSON.parse(params.cmdArgs[0]) as JSONObject
                val groupId = param.getString("groupId")
                if (groupId.isNullOrBlank()) {
                    resultMap["result"] = "groupId不能为空"
                    return@register resultMap
                }
                val client = registry.getClient(groupId)
                if (client != null) {
                    resultMap["result"] = client.state.name
                }
            } catch (e: Exception) {
                log.error("acpGetStatus 失败", e)
                resultMap["result"] = "获取状态失败: ${e.message}"
            }
            resultMap
        }

        CmdReceiver.register("acpGetContextUsage", cmdGroupList, "获取ACP会话上下文使用占比，groupId必填") { params ->
            val resultMap = mutableMapOf<String, String>()
            try {
                val param: JSONObject = JSON.parse(params.cmdArgs[0]) as JSONObject
                val groupId = param.getString("groupId")
                val client = registry.getClient(groupId)
                if (client != null) {
                    resultMap["result"] = client.contextUsagePercentage.toString()
                }
            } catch (e: Exception) {
                log.error("acpGetContextUsage 失败", e)
                resultMap["result"] = "-1"
            }
            resultMap
        }

        CmdReceiver.register("acpListSessions", cmdGroupList, "获取最近N个会话列表，groupId必填，limit可选(默认7)") { params ->
            val resultMap = mutableMapOf<String, String>()
            try {
                val param: JSONObject = JSON.parse(params.cmdArgs[0]) as JSONObject
                val groupId = param.getString("groupId")
                if (groupId.isNullOrBlank()) {
                    resultMap["result"] = "groupId不能为空"
                    return@register resultMap
                }
                val limit = param.getIntValue("limit").let { if (it <= 0) 7 else it }
                val client = registry.getClient(groupId)
                if (client == null) {
                    resultMap["result"] = "会话不存在"
                    return@register resultMap
                }
                val currentSessionId = client.sessionId
                val sessions = client.historyManager.listRecentSessions(limit)
                val arr = com.alibaba.fastjson.JSONArray()
                for (s in sessions) {
                    if (arr.size >= limit) break
                    val obj = JSONObject()
                    obj["sessionId"] = s.sessionId
                    val isCurrent = s.sessionId == currentSessionId
                    obj["preview"] = s.preview
                    obj["lastModified"] = s.lastModified
                    obj["current"] = isCurrent
                    arr.add(obj)
                }
                resultMap["result"] = arr.toJSONString()
            } catch (e: Exception) {
                log.error("acpListSessions 失败", e)
                resultMap["result"] = "查询失败: ${e.message}"
            }
            resultMap
        }

        CmdReceiver.register("acpRestoreSession", cmdGroupList, "恢复指定历史会话，groupId和sessionId必填") { params ->
            val resultMap = mutableMapOf<String, String>()
            try {
                val param: JSONObject = JSON.parse(params.cmdArgs[0]) as JSONObject
                val groupId = param.getString("groupId")
                val sessionId = param.getString("sessionId")
                if (groupId.isNullOrBlank()) {
                    resultMap["result"] = "groupId不能为空"
                    return@register resultMap
                }
                if (sessionId.isNullOrBlank()) {
                    resultMap["result"] = "sessionId不能为空"
                    return@register resultMap
                }
                val client = registry.getClient(groupId)
                if (client == null || client.state != AbstractAcpClient.State.READY) {
                    resultMap["result"] = "当前client状态不允许恢复会话"
                    return@register resultMap
                }

                // 如果目标 session 和当前 session 相同，直接报错
                if (sessionId == client.sessionId) {
                    resultMap["result"] = "当前已是该会话，无需切换"
                    return@register resultMap
                }

                registry.restoreSession(groupId, sessionId)

                // 重新初始化 memory/ability/subAgent
                val newClient = registry.getClient(groupId)
                if (newClient != null) {
                    initMemoryForClient(groupId, newClient, newClient.robotParam)
                    initAbilityReflection(groupId, newClient, newClient.robotParam)
                    initSubAgentDispatcher(groupId, newClient, newClient.robotParam)

                    // 异步发送会话快照，让用户回忆聊天细节
                    Thread {
                        try {
                            replaySessionSnapshot(groupId, newClient)
                        } catch (e: Exception) {
                            log.error("发送会话快照失败, groupId={}, sessionId={}", groupId, sessionId, e)
                        }
                    }.start()
                }
            } catch (e: Exception) {
                log.error("acpRestoreSession 失败", e)
                resultMap["result"] = "恢复失败: ${e.message}"
            }
            resultMap
        }

        // ==================== 记忆管理命令 ====================

        CmdReceiver.register("acpMemoryList", cmdGroupList, "列出当前项目的所有记忆，groupId必填") { params ->
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
                val memories = mgr.listMemories(client.workspacePath)
                if (memories.isEmpty()) {
                    resultMap["result"] = "暂无记忆"
                } else {
                    val sb = StringBuilder()
                    for ((i, entry) in memories.withIndex()) {
                        sb.append("${i + 1}. [${entry.type}] ${entry.title}：${entry.summary} (id=${entry.id})\n")
                    }
                    resultMap["result"] = sb.toString()
                }
            } catch (e: Exception) {
                log.error("acpMemoryList 失败", e)
                resultMap["result"] = "查询记忆失败: ${e.message}"
            }
            resultMap
        }

        CmdReceiver.register("acpMemoryDelete", cmdGroupList, "删除指定记忆，groupId和memoryId必填") { params ->
            val resultMap = mutableMapOf<String, String>()
            try {
                val param: JSONObject = JSON.parse(params.cmdArgs[0]) as JSONObject
                val groupId = param.getString("groupId")
                val memoryId = param.getString("memoryId")
                if (groupId.isNullOrBlank()) {
                    resultMap["result"] = "groupId不能为空"
                    return@register resultMap
                }
                if (memoryId.isNullOrBlank()) {
                    resultMap["result"] = "memoryId不能为空"
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
                val success = mgr.deleteMemory(client.workspacePath, memoryId)
                resultMap["result"] = if (success) "记忆已删除: $memoryId" else "记忆不存在: $memoryId"
            } catch (e: Exception) {
                log.error("acpMemoryDelete 失败", e)
                resultMap["result"] = "删除记忆失败: ${e.message}"
            }
            resultMap
        }

        CmdReceiver.register("acpMemoryClean", cmdGroupList, "清理过期记忆，groupId必填") { params ->
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
                val count = mgr.cleanExpiredMemories(client.workspacePath)
                resultMap["result"] = "已清理 $count 条过期记忆"
            } catch (e: Exception) {
                log.error("acpMemoryClean 失败", e)
                resultMap["result"] = "清理记忆失败: ${e.message}"
            }
            resultMap
        }

        CmdReceiver.register("acpMemoryDream", cmdGroupList, "手动触发记忆整理（Memory Dream），groupId必填") { params ->
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

        log.info("AcpProxy 命令注册完成")
    }

    private val DISPATCH_MARKER = "dispatch_subagent"
    private val SUB_AGENT_RESULTS_MARKER = "Sub-Agent Results"
    private val DISPATCH_PATTERN = java.util.regex.Pattern.compile(
        "\\{\\s*\"action\"\\s*:\\s*\"dispatch_subagent\".*?\"tasks\"\\s*:\\s*\\[.*?]\\s*}",
        java.util.regex.Pattern.DOTALL
    )

    /**
     * 将恢复的会话历史以快照形式异步推送给 molachat，让用户回忆聊天细节。
     * <p>
     * 回放规则：
     * - USER 消息：普通消息以用户标识展示；sub_agent_results 以子 Agent 结果展示
     * - ASSISTANT 消息：包含 dispatch_subagent 时解析并展示派发任务；否则正常 onMessage
     * - TOOL 消息：通过 onToolCall 展示
     */
    private fun replaySessionSnapshot(groupId: String, client: AcpClient) {
        val listener = client.globalListener ?: return
        val sessionId = client.sessionId ?: return
        val history = client.historyManager.getFullHistory(sessionId)
        if (history.isEmpty()) return

        // 整个回放过程开启缓冲，最后一次性发送
        val bufferListener = listener as? com.mola.cmd.proxy.app.acp.acpclient.listener.DefaultAcpResponseListener
        bufferListener?.beginBuffer()

        for (msg in history) {
            when (msg.role) {
                com.mola.cmd.proxy.app.acp.acpclient.context.ContextMessage.Role.USER -> {
                    val content = msg.content ?: continue
                    if (content.contains(SUB_AGENT_RESULTS_MARKER)) {
                        // 子 Agent 结果回传，按每个子 Agent 逐条展示
                        val agentBlockPattern = Regex("### (.+?)\\n状态: (.+?)\\n([\\s\\S]*?)(?=### |请综合以上|$)")
                        val matches = agentBlockPattern.findAll(content)
                        var matched = false
                        for (m in matches) {
                            matched = true
                            val agentName = m.groupValues[1].trim()
                            val status = m.groupValues[2].trim()
                            val detail = m.groupValues[3].trim()
                            if (status == "SUCCESS") {
                                listener.onSubAgentEvent("AGENT_COMPLETE", agentName, detail)
                            } else {
                                listener.onSubAgentEvent("AGENT_ERROR", agentName, detail)
                            }
                        }
                        if (!matched) {
                            // 兜底：无法解析时整体展示
                            listener.onSubAgentEvent("AGENT_COMPLETE", "agent派发结果", content)
                        }
                    } else {
                        listener.onMessage("**🧑 用户：**\n${content}\n\n---\n\n")
                    }
                }
                com.mola.cmd.proxy.app.acp.acpclient.context.ContextMessage.Role.ASSISTANT -> {
                    val content = msg.content ?: continue
                    if (content.contains(DISPATCH_MARKER)) {
                        // 解析 dispatch_subagent JSON，展示派发的任务入参
                        val matcher = DISPATCH_PATTERN.matcher(content)
                        var matchedJson: String? = null
                        if (matcher.find()) {
                            matchedJson = matcher.group()
                            try {
                                val json = com.google.gson.JsonParser.parseString(matchedJson).asJsonObject
                                val tasks = json.getAsJsonArray("tasks")
                                val sb = StringBuilder("子 Agent 派发任务：\n")
                                for (t in tasks) {
                                    val task = t.asJsonObject
                                    val agent = task.get("agent")?.asString ?: "unknown"
                                    val title = task.get("title")?.asString ?: ""
                                    val prompt = task.get("prompt")?.asString ?: ""
                                    sb.append("- [$agent/$title] $prompt\n")
                                }
                                listener.onSubAgentEvent("DISPATCH_START", null, sb.toString())
                            } catch (e: Exception) {
                                listener.onSubAgentEvent("DISPATCH_START", null, "子 Agent 派发（解析失败）")
                            }
                        }
                        // dispatch JSON 之外可能还有正常文本，也展示出来
                        val cleanedContent = if (matchedJson != null) content.replace(matchedJson, "").trim() else content.trim()
                        if (cleanedContent.isNotBlank()) {
                            listener.onMessage("${cleanedContent}\n\n---\n\n")
                        }
                    } else if (content.isNotBlank()) {
                        listener.onMessage("${content}\n\n---\n\n")
                    }
                }
                com.mola.cmd.proxy.app.acp.acpclient.context.ContextMessage.Role.TOOL -> {
                    val update = com.google.gson.JsonObject()
                    if (msg.rawInput != null) update.add("rawInput", msg.rawInput)
                    if (msg.rawOutput != null) update.add("rawOutput", msg.rawOutput)
                    listener.onToolCall(
                        msg.toolCallId ?: "",
                        msg.toolName ?: "tool",
                        "completed",
                        update
                    )
                }
            }
        }

        // 回放结束，flush 缓冲后再发终止帧
        bufferListener?.flushBuffer()
        listener.onComplete("")
    }

    /**
     * 为 client 初始化记忆系统（如果该 robot 开启了记忆）。
     */
    private fun initMemoryForClient(groupId: String, client: AcpClient, robot: AcpRobotParam?) {
        if (robot == null || !robot.isMemoryEnabled) return

        val memCfg = robot.memory
        val agentProvider = robot.agentProvider ?: "KIRO_CLI"
        val mgr = memoryManagers.getOrPut(groupId) { MemoryManager(memCfg, agentProvider, robot.name) }
        client.setMemoryManager(mgr)
        setupTurnCallback(client, memCfg, mgr)
    }

    /**
     * 为 AcpClient 注册每 N 轮触发记忆提取的回调。
     */
    private fun setupTurnCallback(client: AcpClient, memoryConfig: MemoryConfig, memoryManager: MemoryManager) {
        val interval = memoryConfig.extractIntervalTurns
        if (interval <= 0) return

        val turnCount = AtomicInteger(0)
        client.historyManager.setOnTurnFlushed {
            if (turnCount.incrementAndGet() % interval == 0) {
                log.info("每 {} 轮触发记忆提取, groupId={}", interval, client.groupId)
                memoryManager.submitExtract(
                    client.workspacePath,
                    client.conversationHistory
                )
            }
        }
    }

    /**
     * 初始化能力反思服务。
     * 触发时机：AcpClient 初始化后发现 ability.md 不存在时立即触发。
     * dream 完成后也会触发（通过 MemoryManager 的回调）。
     */
    private fun initAbilityReflection(groupId: String, client: AcpClient, robot: AcpRobotParam?) {
        if (robot == null || robot.name.isBlank()) return

        val agentProvider = robot.agentProvider ?: "KIRO_CLI"
        val timeoutSeconds = if (robot.isMemoryEnabled) robot.memory.subClientTimeout else 120
        val mcpConfigPaths = client.mcpConfigPaths
        val memoryManager: MemoryManager? = if (robot.isMemoryEnabled) memoryManagers[groupId] else null

        val service = abilityServices.getOrPut(groupId) {
            AbilityReflectionService(
                robot.name, client.workspacePath, agentProvider,
                robot.description, timeoutSeconds, mcpConfigPaths, memoryManager
            )
        }

        // 统一由 submitReflection 内部判断是否需要执行
        service.submitReflection()
    }

    /**
     * 为 onlySubAgent 的 robot 初始化能力反思（不依赖 AcpClient）。
     */
    private fun initAbilityReflectionStandalone(groupId: String, robot: AcpRobotParam) {
        if (robot.name.isBlank()) return

        val agentProvider = robot.agentProvider ?: "KIRO_CLI"
        val workDir = robot.workDir ?: return
        val mcpConfigPaths = AgentProviderRouter.getInstance().resolve(agentProvider)
            .getMcpConfigPaths(workDir)
        val timeoutSeconds = if (robot.isMemoryEnabled) robot.memory.subClientTimeout else 120

        val service = abilityServices.getOrPut(groupId) {
            AbilityReflectionService(
                robot.name, workDir, agentProvider,
                robot.description, timeoutSeconds, mcpConfigPaths, null
            )
        }
        service.submitReflection()
    }

    /**
     * 初始化子 Agent 派发器（如果该 robot 配置了 subAgents）。
     */
    private fun initSubAgentDispatcher(groupId: String, client: AcpClient, robot: AcpRobotParam?) {
        if (robot == null || !robot.hasSubAgents()) return

        // 校验子 Agent 引用的有效性
        val allowedNames = mutableSetOf<String>()
        for (ref in robot.subAgents) {
            if (!globalRobotRegistry.containsKey(ref.name)) {
                log.warn("robot '{}' 引用了不存在的子 Agent '{}'，跳过", robot.name, ref.name)
            } else {
                allowedNames.add(ref.name)
            }
        }

        if (allowedNames.isEmpty()) {
            log.warn("robot '{}' 的所有子 Agent 引用均无效，跳过派发器初始化", robot.name)
            return
        }

        val dispatcher = SubAgentDispatcher(
            globalRobotRegistry,
            allowedNames,
            1080
        )

        // 构建子 Agent 的记忆管理器映射（robot name -> MemoryManager）
        val subAgentMemoryMap = mutableMapOf<String, com.mola.cmd.proxy.app.acp.acpclient.MemoryManagerBridge>()
        for (name in allowedNames) {
            val targetRobot = globalRobotRegistry[name] ?: continue
            if (targetRobot.isMemoryEnabled) {
                val memMgr = MemoryManager(targetRobot.memory, targetRobot.agentProvider ?: "KIRO_CLI", targetRobot.name)
                subAgentMemoryMap[name] = memMgr
            }
        }
        if (subAgentMemoryMap.isNotEmpty()) {
            dispatcher.setMemoryManagers(subAgentMemoryMap)
            log.info("子 Agent 记忆管理器注入完成, agents={}", subAgentMemoryMap.keys)
        }

        val injector = SubAgentContextInjector()

        client.setSubAgentSupport(dispatcher, injector, globalRobotRegistry)

        log.info("子 Agent 派发器初始化完成, groupId={}, subAgents={}",
            groupId, allowedNames)
    }

}

