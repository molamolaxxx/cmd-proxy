package com.mola.cmd.proxy.app.acp

import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import com.mola.cmd.proxy.app.acpclient.AcpClientRegistry
import com.mola.cmd.proxy.app.acpclient.DefaultAcpResponseListener
import com.mola.cmd.proxy.app.constants.CmdProxyConstant
import com.mola.cmd.proxy.app.utils.LogUtil
import com.mola.cmd.proxy.client.conf.CmdProxyConf
import com.mola.cmd.proxy.client.provider.CmdReceiver
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object AcpProxy {

    private val log: Logger = LoggerFactory.getLogger(AcpProxy::class.java)

    private val registry: AcpClientRegistry = AcpClientRegistry.getInstance()

    fun start(cmdGroupList: List<String>) {

        // 冷加载：启动时为每个groupId预创建client
        for (groupId in cmdGroupList) {
            try {
                registry.createSession(groupId)
                log.info("ACP client 冷加载完成, groupId={}", groupId)
            } catch (e: Exception) {
                log.error("ACP client 冷加载失败, groupId={}", groupId, e)
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
                registry.createSession(groupId)
                resultMap["result"] = "会话上下文已清除"
            } catch (e: Exception) {
                log.error("acpClearSession 失败", e)
                resultMap["result"] = "会话上下文清除失败: ${e.message}"
            }
            resultMap
        }

        CmdReceiver.register("acpSendMessage", cmdGroupList, "向ACP会话发送消息，groupId和message必填") { params ->
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
                // 发送消息
                registry.sendMessage(groupId, message)
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


        // 注册命令
        CmdReceiver.register("acp", "acp") { param ->
            mutableMapOf<String, String>()
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        LogUtil.debugReject()
        CmdProxyConf.serverPort = 10021
        CmdProxyConf.Receiver.listenedSenderAddress = CmdProxyConf.LOCAL_ADDRESS
        start(listOf("1740242633231HjpIOacp"))
    }
}
