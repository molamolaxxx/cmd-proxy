package com.mola.cmd.proxy.app

import com.mola.cmd.proxy.app.chatgpt.ChatGptProxy
import com.mola.cmd.proxy.app.constants.CmdProxyConstant
import com.mola.cmd.proxy.app.imagegenerate.ImageGenerateProxy
import com.mola.cmd.proxy.app.mcp.McpProxy
import com.mola.cmd.proxy.app.utils.LogUtil
import com.mola.cmd.proxy.client.conf.CmdProxyConf
import java.io.File
import java.nio.charset.Charset


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
    if (args.contains(CmdProxyConstant.MCP)) {
        val file = File("./cmdGroupList.txt")
        if (!file.exists()) {
            file.createNewFile()
        }
        var keys = file.readText(Charset.forName("UTF-8"))
        while (keys.isBlank()) {
            print("请输入group编码（在流水线之王界面输入fetch获取）：")
            keys = readln()
        }

        file.bufferedWriter().use { writer ->
            writer.write(keys)
        }
        McpProxy.start(keys.split("\n").toList())
    }
}