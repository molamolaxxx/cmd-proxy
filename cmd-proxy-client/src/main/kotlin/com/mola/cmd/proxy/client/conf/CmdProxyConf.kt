package com.mola.cmd.proxy.client.conf

object CmdProxyConf {

    const val REMOTE_ADDRESS = "120.27.230.24:9003"

    const val LOCAL_ADDRESS = "127.0.0.1:9003"

    var serverPort = 9003

    object Sender {
    }

    object Receiver {
        var listenedSenderAddress = LOCAL_ADDRESS
    }
}