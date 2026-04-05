package com.mola.cmd.proxy.app.acp

import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import com.mola.cmd.proxy.app.acpclient.AcpClient
import com.mola.cmd.proxy.app.acpclient.AcpClientRegistry
import com.mola.cmd.proxy.app.acpclient.DefaultAcpResponseListener
import com.mola.cmd.proxy.app.constants.CmdProxyConstant
import com.mola.cmd.proxy.app.utils.LogUtil
import com.mola.cmd.proxy.client.conf.CmdProxyConf
import com.mola.cmd.proxy.client.provider.CmdReceiver
import com.mola.cmd.proxy.client.resp.CmdResponseContent
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.UUID

object AcpProxy {

    private val log: Logger = LoggerFactory.getLogger(AcpProxy::class.java)

    private val registry: AcpClientRegistry = AcpClientRegistry.getInstance()

    fun start(cmdGroupList: List<String>, robotsJson: String? = null, chatterIdsJson: String? = null, groupWorkDirMap: Map<String, String> = emptyMap()) {

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
                if (client == null || client.state != AcpClient.State.READY) {
                    resultMap["result"] = "当前client状态不为READY，不允许清除上下文"
                    return@register resultMap
                }
                registry.createSession(groupId, null)
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
                // 解析可选的图片数组
                val imageBase64List: MutableList<String>? = if (param.containsKey("images")) {
                    param.getJSONArray("images")?.map { it.toString() }?.toMutableList()
                } else null
                // 发送消息
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

        log.info("AcpProxy 命令注册完成")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        LogUtil.debugReject()
        CmdProxyConf.serverPort = 10021
        CmdProxyConf.Receiver.listenedSenderAddress = CmdProxyConf.LOCAL_ADDRESS
        val robotsJson = "[{\"name\":\"kiro_code_1\",\"signature\":\"专注代码质量\",\"workDir\":\"/home/mola/my-test\",\"avatar\":\"\"}]"
        val chatterIdsJson = "[\"1740242633231HjpIO\"]"
        val chatterId = "1740242633231HjpIO"
        val robotsArray = JSON.parseArray(robotsJson)
        val groupIdList = robotsArray.map { val obj = it as JSONObject; "acp-${obj.getString("name")}${chatterId}" }
        val groupWorkDirMap = robotsArray.mapNotNull { val obj = it as JSONObject; val wd = obj.getString("workDir"); if (!wd.isNullOrBlank()) "acp-${obj.getString("name")}${chatterId}" to wd else null }.toMap()
        start(groupIdList, robotsJson, chatterIdsJson, groupWorkDirMap)
    }
}
