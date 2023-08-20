package com.mola.cmd.proxy.client.provider

import com.google.common.collect.Maps
import com.mola.cmd.proxy.client.CmdProxyCallbackService
import com.mola.cmd.proxy.client.CmdProxyInvokeService
import com.mola.cmd.proxy.client.conf.CmdProxyConf
import com.mola.cmd.proxy.client.param.CmdInvokeParam
import com.mola.cmd.proxy.client.resp.CmdInvokeResponse
import com.mola.cmd.proxy.client.resp.CmdResponseContent
import com.mola.rpc.common.entity.RpcMetaData
import com.mola.rpc.core.properties.RpcProperties
import com.mola.rpc.core.proto.ProtoRpcConfigFactory
import com.mola.rpc.core.proto.RpcInvoker
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import org.slf4j.LoggerFactory


/**
 * @Project: cmd-proxy
 * @Description:
 * @author : molamola
 * @date : 2023-08-06 15:38
 **/
object CmdReceiver {

    private val log: Logger = LoggerFactory.getLogger(CmdReceiver::class.java)

    private val receiverProviderMapByGroup: MutableMap<String, CmdProxyInvokeService> = Maps.newConcurrentMap()

    private val callbackConsumerMapByGroup: MutableMap<String, CmdProxyCallbackService> = Maps.newConcurrentMap()

    private val receiverFuncMap: MutableMap<String,
            suspend (param: CmdInvokeParam) -> Map<String, String>> = Maps.newConcurrentMap()

    private fun init(serverPort: Int) {
        val protoRpcConfigFactory = ProtoRpcConfigFactory.fetch()
        if (protoRpcConfigFactory.initialized()) {
            return
        }
        val prop = RpcProperties()
        prop.startConfigServer = false
        prop.serverPort = serverPort
        protoRpcConfigFactory.init(prop)
    }

    private fun start(group: String) {
        try {
            if (receiverProviderMapByGroup.containsKey(group)) {
                return
            }
            init(CmdProxyConf.serverPort)
            // 接收器provider
            startReceiverProvider(group)
            // 回调consumer
            startCallbackConsumer(group)
        } catch (e: Exception) {
            log.error("ProxyProviderStarter start exception, msg = ${e.message}", e)
        }
    }

    private fun startReceiverProvider(group: String) {
        val rpcMetaData = RpcMetaData()
        rpcMetaData.reverseMode = true
        rpcMetaData.reverseModeConsumerAddress = arrayListOf(CmdProxyConf.Receiver.listenedSenderAddress)
        rpcMetaData.group = group
        val provider = CmdProxyInvokeServiceImpl(group)
        RpcInvoker.provider(
                CmdProxyInvokeService::class.java,
                provider,
                rpcMetaData
        )
        receiverProviderMapByGroup[group] = provider
    }

    private fun startCallbackConsumer(group: String) {
        val rpcMetaData = RpcMetaData()
        rpcMetaData.appointedAddress = arrayListOf(CmdProxyConf.Receiver.listenedSenderAddress)
        rpcMetaData.group = group
        callbackConsumerMapByGroup[group] = RpcInvoker.consumer(
                CmdProxyCallbackService::class.java,
                rpcMetaData,
                "cmdProxyCallbackService"
        )
    }

    fun register(cmdName: String, cmdGroup: String,
                 receiver: suspend (param: CmdInvokeParam) -> Map<String, String>) {
        start(cmdGroup)
        if (receiverFuncMap.containsKey("$cmdName$cmdGroup")) {
            log.warn("registerCallback already contains, key = $cmdName$cmdGroup")
            return
        }
        receiverFuncMap["$cmdName$cmdGroup"] = receiver
    }

    fun callback(cmdName: String, cmdGroup: String, response: CmdResponseContent) {
        val cmdProxyCallbackService = callbackConsumerMapByGroup[cmdGroup]
        if (cmdProxyCallbackService == null) {
            log.warn("receiver callback cannot find consumer, group = $cmdGroup")
            return
        }
        cmdProxyCallbackService.callback(cmdName, response)
    }

    class CmdProxyInvokeServiceImpl(private var cmdGroup: String) : CmdProxyInvokeService {

        override fun invoke(param: CmdInvokeParam): CmdInvokeResponse<CmdResponseContent?> {
            val funcKey = "${param.cmdName}${cmdGroup}"
            if (!receiverFuncMap.containsKey(funcKey)) {
                return CmdInvokeResponse.error("not available cmd ${param.cmdName} " +
                        "in group $cmdGroup")
            }
            return runBlocking {
                val resultMap = receiverFuncMap[funcKey]?.invoke(param)!!
                CmdInvokeResponse.success(CmdResponseContent(param.cmdId, resultMap)
                )
            }
        }
    }
}
