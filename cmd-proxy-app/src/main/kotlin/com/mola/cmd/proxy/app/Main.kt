package com.mola.cmd.proxy.app

import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import com.mola.cmd.proxy.app.acp.AcpProxy
import com.mola.cmd.proxy.app.acp.AcpRobotParam
import com.mola.cmd.proxy.app.mcp.McpProxy
import com.mola.cmd.proxy.app.utils.LogUtil
import com.mola.cmd.proxy.app.utils.McpFileUtils
import com.mola.cmd.proxy.client.conf.CmdProxyConf
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.charset.Charset


/**
 * @Project: cmd-proxy
 * @Description:
 * @author : molamola
 * @date : 2023-08-06 23:19
 **/

private val log: Logger = LoggerFactory.getLogger(McpProxy::class.java)
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
        content = interactiveAcpConfig()
    }
    file.bufferedWriter().use { writer -> writer.write(content) }

    val config: JSONObject = JSON.parseObject(content)
    val robotsArray = config.getJSONArray("robots")
    val chatterIdsArray = config.getJSONArray("chatterIds")
    val robotsJsonStr = robotsArray.toJSONString()
    val chatterIdsJsonStr = chatterIdsArray.toJSONString()
    val robots = robotsArray.toJavaList(AcpRobotParam::class.java)
    val chatterIds = chatterIdsArray.toJavaList(String::class.java)

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

/**
 * 交互式引导用户逐字段配置ACP参数，返回完整的配置JSON字符串
 */
private fun interactiveAcpConfig(): String {
    println("========== ACP 配置引导 ==========")

    // 1. 引导配置 robots
    println("\n--- 第1步：配置 Robot 列表 ---")
    val robots = mutableListOf<AcpRobotParam>()
    var addMore = true
    var robotIndex = 1
    while (addMore) {
        println("\n配置第 $robotIndex 个 Robot：")

        print("  请输入 Robot 名称（name）：")
        var name = readln().trim()
        while (name.isBlank()) {
            print("  名称不能为空，请重新输入：")
            name = readln().trim()
        }

        print("  请输入 Robot 签名描述（signature）：")
        val signature = readln().trim()

        print("  请输入工作目录（workDir，可选，直接回车跳过）：")
        val workDir = readln().trim()

        print("  请输入用户头像URL（avatar，可选，直接回车跳过）：")
        val avatar = readln().trim()

        robots.add(AcpRobotParam(name, signature, workDir, avatar))
        println("  ✓ Robot「$name」已添加")
        robotIndex++

        print("\n是否继续添加下一个 Robot？(y/n，默认n)：")
        addMore = readln().trim().lowercase() == "y"
    }

    // 2. 引导配置 chatterIds
    println("\n--- 第2步：配置 ChatterId 列表 ---")
    val chatterIds = mutableListOf<String>()
    var addMoreChatter = true
    var chatterIndex = 1
    while (addMoreChatter) {
        print("  请输入第 $chatterIndex 个 ChatterId：")
        var chatterId = readln().trim()
        while (chatterId.isBlank()) {
            print("  ChatterId不能为空，请重新输入：")
            chatterId = readln().trim()
        }
        chatterIds.add(chatterId)
        println("  ✓ ChatterId「$chatterId」已添加")
        chatterIndex++

        print("是否继续添加下一个 ChatterId？(y/n，默认n)：")
        addMoreChatter = readln().trim().lowercase() == "y"
    }

    // 3. 组装JSON
    val config = JSONObject()
    config["robots"] = JSON.parseArray(JSON.toJSONString(robots))
    config["chatterIds"] = JSON.parseArray(JSON.toJSONString(chatterIds))
    val configStr = config.toJSONString()

    println("\n========== 配置完成 ==========")
    println("生成的配置：$configStr")
    return configStr
}