package com.mola.cmd.proxy.app

import com.mola.cmd.proxy.app.chatgpt.ChatGptProxy
import com.mola.cmd.proxy.client.conf.CmdProxyConf


/**
 * @Project: cmd-proxy
 * @Description:
 * @author : molamola
 * @date : 2023-08-06 23:19
 **/
fun main() {
    CmdProxyConf.serverPort = 10020
    CmdProxyConf.Receiver.listenedSenderAddress = CmdProxyConf.REMOTE_ADDRESS
    ChatGptProxy.start()
}