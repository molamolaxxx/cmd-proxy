package com.mola.cmd.proxy.app

import com.mola.cmd.proxy.app.chatgpt.ChatGptProxy
import com.mola.cmd.proxy.app.constants.CmdProxyConstant
import com.mola.cmd.proxy.app.imagegenerate.ImageGenerateProxy
import com.mola.cmd.proxy.app.mcp.McpProxy
import com.mola.cmd.proxy.app.utils.LogUtil
import com.mola.cmd.proxy.client.conf.CmdProxyConf


/**
 * @Project: cmd-proxy
 * @Description:
 * @author : molamola
 * @date : 2023-08-06 23:19
 **/
fun main(args: Array<String>) {
    LogUtil.debugReject()
    CmdProxyConf.serverPort = 10020
    CmdProxyConf.Receiver.listenedSenderAddress = CmdProxyConf.REMOTE_ADDRESS

    if (args.contains(CmdProxyConstant.CHAT_GPT)) {
        ChatGptProxy.start()
    }
    if (args.contains(CmdProxyConstant.IMAGE_GENERATE)) {
        ImageGenerateProxy.start()
    }
    if (args.size >= 2 && CmdProxyConstant.MCP == args[0]) {
        McpProxy.start(args.slice(1..< args.size).toList())
    }
}