import com.mola.cmd.proxy.app.imagegenerate.ImageGenerateProxy
import com.mola.cmd.proxy.app.utils.LogUtil
import com.mola.cmd.proxy.client.conf.CmdProxyConf


LogUtil.debugReject()
CmdProxyConf.serverPort = 10020
CmdProxyConf.Receiver.listenedSenderAddress = CmdProxyConf.REMOTE_ADDRESS
ImageGenerateProxy.start()