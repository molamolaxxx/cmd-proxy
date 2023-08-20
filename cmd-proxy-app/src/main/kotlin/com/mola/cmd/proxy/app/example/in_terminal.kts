import com.alibaba.fastjson.JSONObject
import com.google.common.collect.Maps
import com.mola.cmd.proxy.app.chatgpt.ChatGptProxy
import com.mola.cmd.proxy.app.utils.NetworkUtils
import com.mola.cmd.proxy.client.conf.CmdProxyConf
import com.mola.cmd.proxy.client.provider.CmdReceiver
import com.mola.cmd.proxy.client.resp.CmdResponseContent
import kotlinx.coroutines.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory

val log: Logger = LoggerFactory.getLogger(CmdReceiver::class.java)
fun main() {
    CmdProxyConf.serverPort = 9002
    // 1
    CmdReceiver.register("test", "test") {
        param ->
        log.info("receive cmd args ${JSONObject.toJSONString(param)}")
        var resultMap : MutableMap<String, String> = Maps.newHashMap()
        resultMap["content"] = "mytest"

        CoroutineScope(Dispatchers.IO).launch {
            println("${Thread.currentThread().name} in async")
            Thread.sleep(3000)
            CmdReceiver.callback("test", "test", CmdResponseContent(
                    param.cmdId, resultMap
            ))
        }
        println("${Thread.currentThread().name}")
        resultMap
    }
}
main()