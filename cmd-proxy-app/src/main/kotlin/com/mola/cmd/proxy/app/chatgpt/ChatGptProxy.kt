package com.mola.cmd.proxy.app.chatgpt

import com.google.common.collect.Maps
import com.mola.cmd.proxy.app.utils.NetworkUtils
import com.mola.cmd.proxy.client.conf.CmdProxyConf
import com.mola.cmd.proxy.client.provider.CmdReceiver
import com.mola.cmd.proxy.client.resp.CmdResponseContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object ChatGptProxy {
    private val log: Logger = LoggerFactory.getLogger(ChatGptProxy::class.java)

    private const val CMD_NAME = "chatgpt"

    fun start() {
        CmdReceiver.register(CMD_NAME, CMD_NAME) { param ->
            val (body, apiKey) = param.cmdArgs[0] to param.cmdArgs[1]
            val (toChatterId, appKey) = param.cmdArgs[1] to param.cmdArgs[2]

            CoroutineScope(Dispatchers.IO).launch {
                val url = "https://api.openai.com/v1/chat/completions"
                val headers = mapOf(
                        "Content-Type" to "application/json",
                        "Authorization" to "Bearer $apiKey"
                )
                val resultMap = mutableMapOf(
                        "apiKey" to apiKey,
                        "appKey" to appKey,
                        "toChatterId" to toChatterId
                )
                try {
                    val result = NetworkUtils.post(url, headers, body, 300000)
                    resultMap["result"] = result
                } catch (e: Exception) {
                    log.error("ChatGptProxy error in coroutine", e)
                    resultMap["exception"] = e.message?: "unknown error occur"
                }
                CmdReceiver.callback(CMD_NAME, CMD_NAME, CmdResponseContent(param.cmdId, resultMap))
            }

            Maps.newHashMap()
        }
    }
}