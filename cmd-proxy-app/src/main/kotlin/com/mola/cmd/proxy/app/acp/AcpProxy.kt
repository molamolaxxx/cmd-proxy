package com.mola.cmd.proxy.app.acp

import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import com.mola.cmd.proxy.app.acp.acpclient.AbstractAcpClient
import com.mola.cmd.proxy.app.acp.acpclient.AcpClient
import com.mola.cmd.proxy.app.acp.acpclient.AcpClientRegistry
import com.mola.cmd.proxy.app.acp.memory.MemoryManager
import com.mola.cmd.proxy.app.acp.memory.model.MemoryConfig
import com.mola.cmd.proxy.client.provider.CmdReceiver
import com.mola.cmd.proxy.client.resp.CmdResponseContent
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

object AcpProxy {

    private val log: Logger = LoggerFactory.getLogger(AcpProxy::class.java)

    private val registry: AcpClientRegistry = AcpClientRegistry.getInstance()

    private var memoryManager: MemoryManager? = null
    private var memoryConfig: MemoryConfig =
        MemoryConfig()

    fun start(
        cmdGroupList: List<String>,
        robotsJson: String? = null,
        chatterIdsJson: String? = null,
        groupWorkDirMap: Map<String, String> = emptyMap(),
        memoryConfig: MemoryConfig = MemoryConfig()
    ) {
        this.memoryConfig = memoryConfig

        // 初始化记忆管理器
        if (memoryConfig.isEnabled) {
            memoryManager = MemoryManager(memoryConfig)
            log.info("记忆系统已启用, baseDir={}", memoryConfig.baseDir)
        }

        // 注册命令
        CmdReceiver.register("acp", "acp") { param ->
            mutableMapOf<String, String>()
        }

        CmdReceiver.register("acpSyncRobots", "acpSyncRobots") { param ->
            mutableMapOf<String, String>()
        }

        // 冷加载：启动时为每个groupId预创建client
        for (groupId in cmdGroupList) {
            try {
                val workDir = groupWorkDirMap[groupId]
                registry.createSession(groupId, workDir)

                // 注入记忆管理器 + 注册每 N 轮回调
                val client = registry.getClient(groupId)
                if (client != null && memoryManager != null) {
                    client.setMemoryManager(memoryManager)
                    setupTurnCallback(client, memoryConfig)
                }

                log.info("ACP client 冷加载完成, groupId={}, workDir={}", groupId, workDir ?: "default")
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
                val firstGroupId = cmdGroupList[0]
                CmdReceiver.callback(
                    "acpSyncRobots", "acpSyncRobots",
                    CmdResponseContent(UUID.randomUUID().toString(), resultMap)
                )
                log.info("acpSyncRobots 回调已发送, groupId={}", firstGroupId)
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

        CmdReceiver.register("acpClearContext", cmdGroupList, "会话上下文清除，groupId必填") { params ->
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
                registry.createSession(groupId, null)

                // 重新注入记忆管理器
                val newClient = registry.getClient(groupId)
                if (newClient != null && memoryManager != null) {
                    newClient.setMemoryManager(memoryManager)
                    setupTurnCallback(newClient, memoryConfig)
                }

                resultMap["result"] = "会话上下文已清除"
            } catch (e: Exception) {
                log.error("acpClearSession 失败", e)
                resultMap["result"] = "会话上下文清除失败: ${e.message}"
            }
            resultMap
        }

        CmdReceiver.register("acpSendMessage", cmdGroupList, "向ACP会话发送消息，groupId和message必填，images可选(base64数组)") { params ->
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
                val imageBase64List: MutableList<String>? = if (param.containsKey("images")) {
                    param.getJSONArray("images")?.map { it.toString() }?.toMutableList()
                } else null
                registry.sendMessage(groupId, message, imageBase64List)
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
                val mgr = memoryManager
                if (mgr == null) {
                    resultMap["result"] = "记忆系统未启用"
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
                val mgr = memoryManager
                if (mgr == null) {
                    resultMap["result"] = "记忆系统未启用"
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
                val mgr = memoryManager
                if (mgr == null) {
                    resultMap["result"] = "记忆系统未启用"
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

        log.info("AcpProxy 命令注册完成")
    }

    /**
     * 为 AcpClient 注册每 N 轮触发记忆提取的回调。
     */
    private fun setupTurnCallback(client: AcpClient, memoryConfig: MemoryConfig) {
        val interval = memoryConfig.extractIntervalTurns
        if (interval <= 0 || memoryManager == null) return

        val turnCount = AtomicInteger(0)
        client.historyManager.setOnTurnFlushed {
            if (turnCount.incrementAndGet() % interval == 0) {
                log.info("每 {} 轮触发记忆提取, groupId={}", interval, client.groupId)
                memoryManager?.submitExtract(
                    client.workspacePath,
                    client.conversationHistory
                )
            }
        }
    }
}
