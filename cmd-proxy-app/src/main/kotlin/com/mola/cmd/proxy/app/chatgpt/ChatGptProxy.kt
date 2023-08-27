package com.mola.cmd.proxy.app.chatgpt

import com.alibaba.fastjson.JSONObject
import com.alibaba.nacos.common.utils.Objects
import com.google.common.collect.Maps
import com.mola.cmd.proxy.app.HttpCommonService
import com.mola.cmd.proxy.app.constants.CmdProxyConstant
import com.mola.cmd.proxy.client.CmdProxyInvokeService
import com.mola.cmd.proxy.client.provider.CmdReceiver
import com.mola.cmd.proxy.client.resp.CmdResponseContent
import com.mola.rpc.common.entity.RpcMetaData
import com.mola.rpc.common.interceptor.ReverseProxyRegisterInterceptor
import com.mola.rpc.core.proto.ProtoRpcConfigFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.apache.http.message.BasicHeader
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object ChatGptProxy {
    private val log: Logger = LoggerFactory.getLogger(ChatGptProxy::class.java)

    fun start() {

        // 注册命令
        CmdReceiver.register(CmdProxyConstant.CHAT_GPT, CmdProxyConstant.CHAT_GPT) { param ->
            val (body, apiKey) = param.cmdArgs[0] to param.cmdArgs[1]
            val (toChatterId, appKey) = param.cmdArgs[2] to param.cmdArgs[3]

            CoroutineScope(Dispatchers.IO).launch {
                val url = "https://api.openai.com/v1/chat/completions"
                val resultMap = mutableMapOf(
                        "apiKey" to apiKey,
                        "appKey" to appKey,
                        "toChatterId" to toChatterId
                )
                try {
                    // headers
                    val headers = arrayOf(BasicHeader("Content-Type", "application/json"),
                            BasicHeader("Authorization", "Bearer $apiKey"))

                    resultMap["result"] = HttpCommonService.PROXY.post(url, JSONObject.parseObject(body),
                            300000, headers)
                    log.info(resultMap["result"])

                } catch (e: Exception) {
                    log.error("ChatGptProxy error in coroutine", e)
                    resultMap["exception"] = e.message?: "unknown error occur"
                }
                CmdReceiver.callback(CmdProxyConstant.CHAT_GPT, CmdProxyConstant.CHAT_GPT, CmdResponseContent(param.cmdId, resultMap))
            }
            Maps.newHashMap()
        }


        // 注册拦截器
        val extensionRegistryManager = ProtoRpcConfigFactory.fetch().extensionRegistryManager
        extensionRegistryManager.addInterceptor(ProxyAvailableInterceptor())
    }

    class ProxyAvailableInterceptor : ReverseProxyRegisterInterceptor() {
        override fun intercept(proxyProviderMetaData: RpcMetaData): Boolean {
            if (proxyProviderMetaData.interfaceClazz != CmdProxyInvokeService::class.java) {
                return false
            }
            try {
                if (proxyProviderMetaData.group != CmdProxyConstant.CHAT_GPT) {
                    return false
                }
                val res: String = HttpCommonService.INSTANCE.get("https://google.com", "",
                    null, 5000)
                if (Objects.isNull(res)) {
                    log.info("ProxyAvailableInterceptor res 为空")
                    return true
                }
            } catch (e: java.lang.Exception) {
                log.info("ProxyAvailableInterceptor 异常", e)
                return true
            }
            log.info("ProxyAvailableInterceptor 代理正常")
            return false
        }
    }

}

