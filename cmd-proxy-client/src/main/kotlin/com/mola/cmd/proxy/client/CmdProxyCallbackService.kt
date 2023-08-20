package com.mola.cmd.proxy.client

import com.mola.cmd.proxy.client.resp.CmdInvokeResponse
import com.mola.cmd.proxy.client.resp.CmdResponseContent


/**
 * @Project: cmd-proxy
 * @Description:
 * @author : molamola
 * @date : 2023-08-06 22:52
 **/

interface CmdProxyCallbackService {

    /**
     * 异步回调
     * @param cmdName
     * @param response
     */
    fun callback(cmdName: String, response: CmdResponseContent)
}
