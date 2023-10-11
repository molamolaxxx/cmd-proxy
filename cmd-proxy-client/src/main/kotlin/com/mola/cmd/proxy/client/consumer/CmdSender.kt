package com.mola.cmd.proxy.client.consumer

import com.mola.cmd.proxy.client.CmdProxyCallbackService
import com.mola.cmd.proxy.client.CmdProxyInvokeService
import com.mola.cmd.proxy.client.conf.CmdProxyConf
import com.mola.cmd.proxy.client.param.CmdInvokeParam
import com.mola.cmd.proxy.client.resp.CmdInvokeResponse
import com.mola.cmd.proxy.client.resp.CmdResponseContent
import com.mola.rpc.common.context.InvokeContext
import com.mola.rpc.common.entity.RpcMetaData
import com.mola.rpc.core.properties.RpcProperties
import com.mola.rpc.core.proto.ProtoRpcConfigFactory
import com.mola.rpc.core.proto.RpcInvoker
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*


/**
 * @Project: cmd-proxy
 * @Description:
 * @author : molamola
 * @date : 2023-08-06 20:32
 **/
object CmdSender {

    private val log: Logger = LoggerFactory.getLogger(CmdSender::class.java)

    private val sendConsumerMapByGroup: MutableMap<String, CmdProxyInvokeService> = mutableMapOf()

    private val callbackProviderMapByGroup: MutableMap<String, CmdProxyCallbackService> = mutableMapOf()

    private val callbackFuncMap: MutableMap<String, (response:CmdResponseContent) -> Unit> = mutableMapOf()

    fun start(cmdGroupList: List<String>) {
        cmdGroupList.forEach {group ->
            start(group)
        }
    }

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
            init(CmdProxyConf.serverPort)
            if (sendConsumerMapByGroup.containsKey(group)) {
                return
            }
            startSendConsumer(group)
            startCallbackProvider(group)
        } catch (e: Exception) {
            log.error("ProxyProviderStarter start exception, msg = ${e.message}", e)
        }
    }

    private fun startSendConsumer(group: String) {
        val rpcMetaData = RpcMetaData()
        rpcMetaData.reverseMode = true
        rpcMetaData.group = group
        sendConsumerMapByGroup[group] = RpcInvoker.consumer(
                CmdProxyInvokeService::class.java,
                rpcMetaData,
                "cmdProxyInvokeService#$group"
        )
    }

    private fun startCallbackProvider(group: String) {
        val rpcMetaData = RpcMetaData()
        rpcMetaData.group = group
        val provider = CmdProxyCallbackServiceImpl(group)
        RpcInvoker.provider(
                CmdProxyCallbackService::class.java,
                provider,
                rpcMetaData
        )
        callbackProviderMapByGroup[group] = provider
    }

    fun send(cmdName:String, cmdGroup:String, cmdArgs: Array<String>): CmdInvokeResponse<CmdResponseContent?>? {
        var cmdProxyInvokeService: CmdProxyInvokeService? = sendConsumerMapByGroup[cmdGroup]
        if (cmdProxyInvokeService == null) {
            start(cmdGroup)
            cmdProxyInvokeService = sendConsumerMapByGroup[cmdGroup]?: return CmdInvokeResponse.success()
        }

        var param = CmdInvokeParam()
        param.cmdId = UUID.randomUUID().toString()
        param.cmdArgs = cmdArgs
        param.cmdName = cmdName

        // 路由分组tag，使用命令名称
        InvokeContext.routeTag(cmdName)
        return cmdProxyInvokeService.invoke(param)
    }

    fun registerCallback(cmdName:String, cmdGroup:String, callback: (response:CmdResponseContent) -> Unit) {
        start(cmdGroup)
        if (callbackFuncMap.containsKey("$cmdName$cmdGroup")) {
            log.warn("registerCallback already contains, key = $cmdName$cmdGroup")
            return
        }
        callbackFuncMap["$cmdName$cmdGroup"] = callback
    }

    class CmdProxyCallbackServiceImpl(private var cmdGroup: String) : CmdProxyCallbackService {

        override fun callback(cmdName: String, response: CmdResponseContent) {
            val funcKey = "${cmdName}${cmdGroup}"
            if (!callbackFuncMap.containsKey(funcKey)) {
                return
            }
            callbackFuncMap[funcKey]?.invoke(response)!!
        }
    }
}