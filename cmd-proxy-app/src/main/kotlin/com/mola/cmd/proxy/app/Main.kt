package com.mola.cmd.proxy.app

import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import com.mola.cmd.proxy.app.acp.AcpProxy
import com.mola.cmd.proxy.app.acp.AcpRobotParam
import com.mola.cmd.proxy.app.acp.memory.model.MemoryConfig
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
    val chatterIdsJsonStr = chatterIdsArray.toJSONString()
    val robots = robotsArray.toJavaList(AcpRobotParam::class.java)
    val robotsJsonStr = JSON.toJSONString(robots.filter { !it.isOnlySubAgent })
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

        // name：必填
        print("  请输入 Robot 名称（name）：")
        var name = readln().trim()
        while (name.isBlank()) {
            print("  名称不能为空，请重新输入：")
            name = readln().trim()
        }

        // signature：可选
        print("  请输入签名描述（signature，直接回车跳过）：")
        val signature = readln().trim()

        // workDir：必填，目录存在性校验
        print("  请输入工作目录（workDir）：")
        var workDir = readln().trim()
        while (true) {
            if (workDir.isBlank()) {
                print("  工作目录不能为空，请重新输入：")
                workDir = readln().trim()
                continue
            }
            if (!File(workDir).isDirectory) {
                print("  目录不存在，请重新输入：")
                workDir = readln().trim()
                continue
            }
            break
        }

        // agentProvider：有默认值
        print("  请选择 Agent 后端（1=KIRO_CLI, 2=OPENCODE，默认1，直接回车使用默认）：")
        val providerInput = readln().trim()
        val agentProvider = if (providerInput == "2") "OPENCODE" else "KIRO_CLI"

        val robot = AcpRobotParam(name, signature, workDir, "")
        robot.agentProvider = agentProvider

        // 记忆系统默认开启
        val memoryConfig = MemoryConfig()
        memoryConfig.isEnabled = true
        robot.memory = memoryConfig

        // 高级配置（可选）
        print("\n  是否进入高级配置？(y/n，默认n)：")
        if (readln().trim().lowercase() == "y") {
            // avatar：可选，有默认
            print("    请输入头像URL（avatar，直接回车使用默认头像）：")
            val avatar = readln().trim()
            if (avatar.isNotBlank()) {
                robot.avatar = avatar
            }

            // description：可选
            print("    请输入 Robot 描述（description，直接回车跳过）：")
            val description = readln().trim()
            if (description.isNotBlank()) {
                robot.description = description
            }

            // memory：开关，默认开启
            print("    是否关闭记忆系统？(y/n，默认n)：")
            if (readln().trim().lowercase() == "y") {
                robot.memory = null
            }

            // onlySubAgent：默认 false
            print("    该 Robot 是否仅作为子 Agent？(y/n，默认n)：")
            if (readln().trim().lowercase() == "y") {
                robot.isOnlySubAgent = true
            }

            // scheduleEnabled：默认 false
            print("    是否启用定时任务能力？(y/n，默认n)：")
            if (readln().trim().lowercase() == "y") {
                robot.isScheduleEnabled = true
            }
        }

        robots.add(robot)
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