package com.mola.cmd.proxy.app

import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import com.alibaba.fastjson.serializer.SerializerFeature
import com.mola.cmd.proxy.app.acp.AcpProxy
import com.mola.cmd.proxy.app.acp.AcpRobotParam
import com.mola.cmd.proxy.app.acp.configui.ConfigUiServer
import com.mola.cmd.proxy.app.mcp.McpProxy
import com.mola.cmd.proxy.app.utils.LogUtil
import com.mola.cmd.proxy.app.utils.McpFileUtils
import com.mola.cmd.proxy.client.conf.CmdProxyConf
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicBoolean


/**
 * @Project: cmd-proxy
 * @Description:
 * @author : molamola
 * @date : 2023-08-06 23:19
 **/

private val log: Logger = LoggerFactory.getLogger(McpProxy::class.java)

/** 热重载防重入标志 */
private val reloading = AtomicBoolean(false)

fun main(args: Array<String>) {
    val mode = args.getOrNull(0)?.lowercase() ?: "mcp"
    log.info("启动模式: {}", mode)

    LogUtil.debugReject()
    CmdProxyConf.serverPort = 10020
    CmdProxyConf.Receiver.listenedSenderAddress = CmdProxyConf.REMOTE_ADDRESS

    when (mode) {
        "acp" -> startAcp()
        else -> startMcp()
    }
}

private fun startMcp() {
    val file = File(System.getProperty("user.home") + "/.cmd-proxy/cmdGroupList.txt")
    if (!file.exists()) {
        McpFileUtils.createFileSmart(file.absolutePath)
    }
    var keys = file.readText(Charset.forName("UTF-8"))
    while (keys.isBlank()) {
        print("请输入group编码（执行fetch命令获取）：")
        keys = readln()
    }
    file.bufferedWriter().use { writer -> writer.write(keys) }
    val keyList = keys.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    log.info("注册的groups: {}", keyList)
    McpProxy.start(keyList)
}

private fun startAcp() {
    val file = File(System.getProperty("user.home") + "/.cmd-proxy/acpConfig.json")
    if (!file.exists()) {
        McpFileUtils.createFileSmart(file.absolutePath)
    }
    var content = file.readText(Charset.forName("UTF-8"))

    if (content.isBlank()) {
        // 首次初始化：写入默认 configUi 配置，引导用户去页面配置
        val defaultConfig = JSONObject()
        defaultConfig["robots"] = JSON.parseArray("[]")
        defaultConfig["chatterIds"] = JSON.parseArray("[]")
        defaultConfig["configUi"] = JSON.parseObject("""{"port":10528,"enabled":true}""")
        content = JSON.toJSONString(defaultConfig, SerializerFeature.PrettyFormat)
        file.bufferedWriter().use { writer -> writer.write(content) }
        log.info("首次启动，已生成默认配置文件: {}", file.absolutePath)
        println("========================================")
        println("  ACP 配置文件已初始化")
        println("  请通过浏览器访问配置页面完成配置：")
        println("  http://localhost:10528")
        println("========================================")
    }

    val config: JSONObject = JSON.parseObject(content)

    // 启动 ConfigUI 服务（无论是否有 robots 配置都启动，方便用户配置）
    startConfigUiServer(config)

    // 如果尚未配置 robots 或 chatterIds，仅启动 ConfigUI 等待用户配置
    val robotsArray = config.getJSONArray("robots")
    val chatterIdsArray = config.getJSONArray("chatterIds")
    if (robotsArray == null || robotsArray.isEmpty()
        || chatterIdsArray == null || chatterIdsArray.isEmpty()) {
        log.info("robots 或 chatterIds 为空，等待用户通过 ConfigUI 页面完成配置后刷新服务")
        return
    }

    startAcpServices(config)
}

/**
 * 根据配置启动 ACP 核心服务（AcpProxy）。
 */
private fun startAcpServices(config: JSONObject) {
    val robotsArray = config.getJSONArray("robots") ?: return
    val chatterIdsArray = config.getJSONArray("chatterIds") ?: return
    if (robotsArray.isEmpty() || chatterIdsArray.isEmpty()) return

    val chatterIdsJsonStr = chatterIdsArray.toJSONString()
    val allRobots = robotsArray.toJavaList(AcpRobotParam::class.java)
    val robots = allRobots.filter { it.isEnabled }
    // acpSyncRobots 发送启用的非 onlySubAgent robot
    val robotsJsonStr = JSON.toJSONString(robots.filter { !it.isOnlySubAgent })
    val chatterIds = chatterIdsArray.toJavaList(String::class.java)

    if (robots.isEmpty()) {
        log.info("所有 robot 均已禁用，跳过 ACP 服务启动")
        return
    }

    // 笛卡尔积生成 groupId 列表: chatterId 和 acpId 字典序排序后拼接
    val groupIdList = chatterIds.flatMap { chatterId ->
        robots.map { robot ->
            val acpId = "acp-" + robot.name.replace(" ", "_")
                .replace("\u3000", "_")
            listOf(chatterId, acpId).sorted().joinToString("")
        }
    }

    // 构建 groupId -> workDir 映射
    val groupWorkDirMap = chatterIds.flatMap { chatterId ->
        robots.filter { it.workDir.isNotBlank() }.map { robot ->
            val acpId = "acp-" + robot.name.replace(" ", "_")
                .replace("\u3000", "_")
            val groupId = listOf(chatterId, acpId).sorted().joinToString("")
            groupId to robot.workDir
        }
    }.toMap()

    log.info("ACP 注册的groups: {}", groupIdList)

    // 构建 groupId -> AcpRobotParam 映射
    val groupRobotMap = chatterIds.flatMap { chatterId ->
        robots.map { robot ->
            val acpId = "acp-" + robot.name.replace(" ", "_")
                .replace("\u3000", "_")
            val groupId = listOf(chatterId, acpId).sorted().joinToString("")
            groupId to robot
        }
    }.toMap()

    AcpProxy.start(groupIdList, robotsJsonStr, chatterIdsJsonStr, groupWorkDirMap, groupRobotMap)
}

private fun startConfigUiServer(config: JSONObject) {
    val configUi = config.getJSONObject("configUi")
    val enabled = configUi?.getBooleanValue("enabled") ?: true
    if (!enabled) {
        log.info("ConfigUI 已禁用，跳过启动")
        return
    }
    val port = configUi?.getIntValue("port") ?: 10528
    val actualPort = if (port <= 0) 10528 else port
    try {
        val server = ConfigUiServer(actualPort) { reloadAcpServices() }
        server.start()
    } catch (e: Exception) {
        log.error("ConfigUI 启动失败, port={}", actualPort, e)
    }
}

/**
 * ACP 服务热重载：重新读取配置文件，停止旧服务，启动新服务。
 * 使用 AtomicBoolean 防止重复点击导致并发重载。
 * 异步执行，不阻塞 HTTP 线程。
 */
private fun reloadAcpServices() {
    if (!reloading.compareAndSet(false, true)) {
        log.warn("ACP 服务正在重载中，忽略重复请求")
        throw IllegalStateException("服务正在重载中，请稍后再试")
    }
    Thread({
        try {
            log.info("开始 ACP 服务热重载...")

            // 1. 停止现有 ACP 服务
            AcpProxy.stop()
            log.info("旧 ACP 服务已停止")

            // 2. 重新读取配置文件
            val file = File(System.getProperty("user.home") + "/.cmd-proxy/acpConfig.json")
            val content = file.readText(Charset.forName("UTF-8"))
            if (content.isBlank()) {
                log.warn("配置文件为空，跳过重载")
                return@Thread
            }
            val config: JSONObject = JSON.parseObject(content)

            // 3. 启动新服务
            startAcpServices(config)
            log.info("ACP 服务热重载完成")
        } catch (e: Exception) {
            log.error("ACP 服务热重载失败", e)
        } finally {
            reloading.set(false)
        }
    }, "acp-reload").start()
}
