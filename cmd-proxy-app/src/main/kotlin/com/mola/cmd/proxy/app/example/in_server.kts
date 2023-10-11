import com.alibaba.fastjson.JSONObject
import com.mola.cmd.proxy.client.conf.CmdProxyConf
import com.mola.cmd.proxy.client.consumer.CmdSender
import com.mola.cmd.proxy.client.provider.CmdReceiver
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.Scanner


val log: Logger = LoggerFactory.getLogger(CmdReceiver::class.java)
fun main() {
    CmdProxyConf.serverPort = 9003
    // 1
    CmdSender.start(arrayListOf("test"))
    // 2
    CmdSender.registerCallback("test", "test") {
        response ->
        log.warn("callback receive, res = ${JSONObject.toJSONString(response)}")
    }
    val scanner = Scanner(System.`in`)
    while (scanner.hasNextLine()) {
        val cmd = scanner.nextLine()
        var split = cmd.split(" ")
        // 3
        var res = CmdSender.send(split[0], "test", arrayOf(split[1]))
        log.info("cmd call success , return = ${JSONObject.toJSONString(res)}")
    }
}
main()