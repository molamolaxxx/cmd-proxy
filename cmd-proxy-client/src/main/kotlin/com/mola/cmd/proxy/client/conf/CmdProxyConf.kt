package com.mola.cmd.proxy.client.conf

object CmdProxyConf {

    const val DEFAULT_REMOTE_HOST = "106.54.193.10"

    const val REMOTE_HOST_ENV = "CMD_PROXY_REMOTE_HOST"

    const val REMOTE_PORT = 9003

    @Volatile
    var remoteHost: String = resolveRemoteHost(emptyArray(), System.getenv(REMOTE_HOST_ENV))
        private set

    val REMOTE_ADDRESS: String
        get() = "$remoteHost:$REMOTE_PORT"

    const val LOCAL_ADDRESS = "127.0.0.1:9003"

    var serverPort = REMOTE_PORT

    /**
     * 远端服务 IP/主机名解析优先级：
     * --remote-host 启动入参 > CMD_PROXY_REMOTE_HOST 环境变量 > 默认地址。
     *
     * 同时兼容 `--remote-host=value` 与 `--remote-host value` 两种写法。
     */
    fun configureRemoteHost(args: Array<String>) {
        remoteHost = resolveRemoteHost(args, System.getenv(REMOTE_HOST_ENV))
    }

    @JvmStatic
    fun resolveRemoteHost(
        args: Array<String>,
        environmentHost: String?
    ): String {
        var argumentSpecified = false
        var argumentHost: String? = null
        for (index in args.indices) {
            val arg = args[index]
            when {
                arg.startsWith("--remote-host=") -> {
                    argumentSpecified = true
                    argumentHost = arg.substringAfter('=')
                    break
                }
                arg == "--remote-host" -> {
                    argumentSpecified = true
                    argumentHost = args.getOrNull(index + 1)
                    break
                }
            }
        }
        return validateRemoteHost(if (argumentSpecified) {
            argumentHost.orEmpty()
        } else {
            environmentHost?.takeIf { it.isNotBlank() } ?: DEFAULT_REMOTE_HOST
        })
    }

    private fun validateRemoteHost(raw: String): String {
        val host = raw.trim()
        require(host.isNotEmpty()
            && host.matches(Regex("[A-Za-z0-9._-]+"))) {
            "远端地址只允许填写 IP 或主机名，不要包含协议、路径或端口: $raw"
        }
        return host
    }

    object Sender {
    }

    object Receiver {
        var listenedSenderAddress = LOCAL_ADDRESS
    }
}
