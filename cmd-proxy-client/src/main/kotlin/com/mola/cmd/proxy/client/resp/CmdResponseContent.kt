package com.mola.cmd.proxy.client.resp

import com.google.common.collect.Maps


/**
 * @Project: cmd-proxy
 * @Description:
 * @author : molamola
 * @date : 2023-08-06 22:51
 **/

class CmdResponseContent {

    constructor(cmdId: String, resultMap: Map<String, String>) {
        this.cmdId = cmdId
        this.resultMap = resultMap
    }

    constructor()


    /**
     * 命令id，UUID
     */
    lateinit var cmdId: String

    /**
     * 返回内容
     */
    lateinit var resultMap : Map<String, String>
}
