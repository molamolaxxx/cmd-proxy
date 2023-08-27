package com.mola.cmd.proxy.app.interceptor

import com.alibaba.nacos.common.utils.Objects
import com.mola.cmd.proxy.app.HttpCommonService
import com.mola.cmd.proxy.app.constants.CmdProxyConstant
import com.mola.cmd.proxy.client.CmdProxyInvokeService
import com.mola.rpc.common.entity.RpcMetaData
import com.mola.rpc.common.interceptor.ReverseProxyRegisterInterceptor
import org.slf4j.Logger
import org.slf4j.LoggerFactory


/**
 * @Project: cmd-proxy
 * @Description:
 * @author : molamola
 * @date : 2023-08-27 11:31
 **/
class ProxyAvailableInterceptor : ReverseProxyRegisterInterceptor() {
    private val log: Logger = LoggerFactory.getLogger(ProxyAvailableInterceptor::class.java)

    override fun intercept(proxyProviderMetaData: RpcMetaData): Boolean {
        if (proxyProviderMetaData.interfaceClazz != CmdProxyInvokeService::class.java) {
            return false
        }
        try {
            if (proxyProviderMetaData.group != CmdProxyConstant.CHAT_GPT
                && proxyProviderMetaData.group != CmdProxyConstant.IMAGE_GENERATE) {
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