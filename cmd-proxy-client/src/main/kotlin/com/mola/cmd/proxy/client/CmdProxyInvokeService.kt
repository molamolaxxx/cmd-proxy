package com.mola.cmd.proxy.client

import com.mola.cmd.proxy.client.param.CmdInvokeParam
import com.mola.cmd.proxy.client.resp.CmdInvokeResponse
import com.mola.cmd.proxy.client.resp.CmdResponseContent

/**
 * 2023-08-06
 * 服务端调用代理端
 * @author molamolaxxx
 */
interface CmdProxyInvokeService {
    /**
     * 执行方法，通过代理返回值asyncCallback判断处理是同步 or 异步
     * @param param
     * @return
     */
    fun invoke(param: CmdInvokeParam): CmdInvokeResponse<CmdResponseContent?>
}
