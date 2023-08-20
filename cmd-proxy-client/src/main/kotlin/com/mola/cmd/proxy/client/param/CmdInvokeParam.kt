package com.mola.cmd.proxy.client.param


/**
 * @Project: cmd-proxy
 * @Description:
 * @author : molamola
 * @date : 2023-08-06 22:46
 **/

class CmdInvokeParam {
    /**
     * 命令id，UUID
     */
    lateinit var cmdId: String

    /**
     * 命令名称，如callChatGpt
     */
    lateinit var cmdName: String

    /**
     * 命令参数
     */
    lateinit var cmdArgs: Array<String>
}
