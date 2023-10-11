import com.alibaba.fastjson.JSONObject
import com.google.common.collect.Maps
import com.mola.cmd.proxy.client.conf.CmdProxyConf
import com.mola.cmd.proxy.client.provider.CmdReceiver
import com.mola.cmd.proxy.client.resp.CmdResponseContent
import kotlinx.coroutines.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory

val log: Logger = LoggerFactory.getLogger(CmdReceiver::class.java)
fun main() {
    CmdProxyConf.Receiver.listenedSenderAddress = CmdProxyConf.REMOTE_ADDRESS
    CmdProxyConf.serverPort = 9002
    // 1
    CmdReceiver.register("testlocal", "1680059511788nQPEXtoolRobot") {
        param ->
        log.info("receive cmd args ${JSONObject.toJSONString(param)}")
        var resultMap : MutableMap<String, String> = Maps.newHashMap()
        println("${Thread.currentThread().name}")
        resultMap
    }
}
main()