import com.alibaba.fastjson.JSONObject
import com.google.common.collect.Maps
import com.mola.cmd.proxy.client.conf.CmdProxyConf
import com.mola.cmd.proxy.client.provider.CmdReceiver
import org.slf4j.Logger
import org.slf4j.LoggerFactory

val log: Logger = LoggerFactory.getLogger(CmdReceiver::class.java)
fun main() {
    CmdProxyConf.serverPort = 9006
    CmdProxyConf.Receiver.listenedSenderAddress = CmdProxyConf.LOCAL_ADDRESS
    // 1
    CmdReceiver.register("hello", "test") {
        param ->
        log.info("receive cmd args ${JSONObject.toJSONString(param)}")
        var resultMap : MutableMap<String, String> = Maps.newHashMap()
        resultMap["result"] = "hi"
        resultMap
    }
}
main()