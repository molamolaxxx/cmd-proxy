import com.alibaba.fastjson.JSONObject
import com.google.common.collect.Maps
import com.mola.cmd.proxy.client.conf.CmdProxyConf
import com.mola.cmd.proxy.client.provider.CmdReceiver
import org.slf4j.Logger
import org.slf4j.LoggerFactory

val log: Logger = LoggerFactory.getLogger(CmdReceiver::class.java)
fun main() {
    CmdProxyConf.serverPort = 9005
    CmdProxyConf.Receiver.listenedSenderAddress = CmdProxyConf.LOCAL_ADDRESS
    // 1
    CmdReceiver.register("test", "1679337550615j72l1toolRobot") {
        param ->
        log.info("receive cmd args ${JSONObject.toJSONString(param)}")
        var resultMap : MutableMap<String, String> = Maps.newHashMap()
        resultMap["result"] = "hi"
        resultMap
    }
}
main()