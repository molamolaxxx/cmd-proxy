package com.mola.cmd.proxy.app

import com.mola.cmd.proxy.app.acp.AcpProxy
import com.mola.cmd.proxy.app.mcp.McpProxy
import com.mola.cmd.proxy.app.utils.LogUtil
import com.mola.cmd.proxy.app.utils.McpFileUtils
import com.mola.cmd.proxy.client.conf.CmdProxyConf
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.charset.Charset


/**
 * @Project: cmd-proxy
 * @Description:
 * @author : molamola
 * @date : 2023-08-06 23:19
 **/

private val log: Logger = LoggerFactory.getLogger(McpProxy::class.java)
fun main(args: Array<String>) {
    val mode = args.getOrNull(0)?.lowercase() ?: "mcp"
    log.info("启动模式: {}", mode)

    LogUtil.debugReject()
    CmdProxyConf.serverPort = 10020
    CmdProxyConf.Receiver.listenedSenderAddress = CmdProxyConf.REMOTE_ADDRESS

    val groupFileName = if (mode == "acp") "acpGroupList.txt" else "cmdGroupList.txt"
    val file = File(System.getProperty("user.home") + "/.cmd-proxy/$groupFileName")
    if (!file.exists()) {
        McpFileUtils.createFileSmart(file.absolutePath)
    }
    var keys = file.readText(Charset.forName("UTF-8"))
    while (keys.isBlank()) {
        print("请输入group编码（执行fetch命令获取）：")
        keys = readln()
    }

    file.bufferedWriter().use { writer ->
        writer.write(keys)
    }
    val keyList = keys.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    log.info("注册的groups: {}", keyList)

    when (mode) {
        "acp" -> AcpProxy.start(keyList)
        else -> McpProxy.start(keyList)
    }
}