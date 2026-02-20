package com.mola.cmd.proxy.app.mcp

import com.alibaba.fastjson.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JOptionPane
import javax.swing.UIManager
import kotlin.io.path.absolutePathString

/**
 * 执行bash脚本并返回输出内容
 */
class ExecuteBashScript {
    companion object {

        val sessionWorkingDirMap = ConcurrentHashMap<String, String>()

        private val PERSIST_FILE_BASE = System.getProperty("user.home") + "/.cmd-proxy/cmd-proxy-wd"

        fun readPersistedWd(sessionId: String): String {
            return try {
                val file = File(PERSIST_FILE_BASE + "-" + sessionId)
                if (!file.exists() || file.length() == 0L) return "."
                file.readText(Charset.defaultCharset()).trim().takeIf { it.isNotEmpty() } ?: "."
            } catch (e: Exception) {
                "."
            }
        }

        private fun writePersistedWd(sessionId: String, path: String) {
            try {
                File(PERSIST_FILE_BASE + "-" + sessionId).writeText(path, Charset.defaultCharset())
            } catch (ignored: Exception) {
            }
        }
    }


    fun executeCommand(script: String, sessionId: String): String {
        // 危险指令黑名单（防止执行高危操作）
        val dangerousPatterns = listOf(
            "rm -r",
            ":(){ :|:& };:",
            "dd if=/dev/zero",
            "mkfs",
            "fdisk",
            "shutdown",
            "reboot",
            "halt",
            "poweroff",
            "> /dev/sd",
            "chmod -R 777",
            "chown -R root",
            "wget.*-O /",
            "curl.*-o /",
            "sudo",
            "su "
        )

        for (pattern in dangerousPatterns) {
            if (script.contains(pattern, ignoreCase = true)) {
                return "禁止执行危险指令: $pattern"
            }
        }

        // 写指令检测，匹配到时弹窗让用户确认
        val writePatterns = listOf(
            "mv ", "cp ", "mkdir ", "touch ", "rm ", "rmdir ",
            "tee ", "sed -i", "chmod ", "chown ",
            "install ", "uninstall ",
            ">>", "> ",
            "ln ", "tar ", "unzip ", "zip ",
            "apt ", "yum ", "dnf ", "brew ",
            "pip install", "npm install", "yarn add",
            "make install","mvn",
            // 操作系统级别操作（杀进程、关机、重启等）
            "kill ", "kill -", "killall ", "pkill ", "xkill",
            "shutdown", "reboot", "halt", "poweroff", "init 0", "init 6",
            "systemctl stop", "systemctl restart", "systemctl disable",
            "service ", "nohup ",
            "ifconfig ", "ip link set", "iptables ",
            "mount ", "umount ", "eject ",
            "crontab ",
            "useradd ", "userdel ", "usermod ", "groupadd ", "groupdel ",
            "passwd "
        )

        val matchedPatterns = writePatterns.filter { script.contains(it, ignoreCase = true) }
        if (matchedPatterns.isNotEmpty()) {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
            } catch (_: Exception) {}
            val descriptions = matchedPatterns.mapNotNull { getCommandDescription(it) }.distinct()
            val descriptionText = if (descriptions.isNotEmpty()) {
                "命令简介：\n${descriptions.joinToString("\n") { "• $it" }}\n\n"
            } else ""
            val choice = JOptionPane.showConfirmDialog(
                null,
                "即将执行以下写指令：\n\n$script\n\n${descriptionText}是否允许执行？",
                "写指令确认",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE
            )
            if (choice != JOptionPane.OK_OPTION) {
                return "用户禁止此脚本的执行，请勿再次执行"
            }
        }

        val workingDirectory = sessionWorkingDirMap.getOrPut(sessionId) { readPersistedWd(sessionId) }

        try {
            val fullCommand = if (script.startsWith("cd ")) {
                "cd $workingDirectory && $script && pwd"
            } else {
                "cd $workingDirectory && $script"
            }

            println("fullCommand: $fullCommand")
            val command: List<String> = listOf("bash", "-c", fullCommand)

            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String?
            var lastLine: String? = null
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
                lastLine = line
            }
            reader.close()
            process.waitFor()

            var result = output.toString().trim()
            // 如果是 cd 命令，则更新当前工作目录
            if (script.startsWith("cd ")) {
                if (lastLine != null && parsePath(lastLine, sessionId).pathExists()) {
                    sessionWorkingDirMap[sessionId] = lastLine
                    writePersistedWd(sessionId, lastLine)
                    val lines = result.lines()
                    if (lines.isNotEmpty()) {
                        result = lines.dropLast(1).joinToString("\n")
                    }
                }
            }
            if (result.isBlank()) {
                result = "执行完成"
            } else{
                result = "执行完成，返回结果: $result"
            }
            return result
        } catch (e: Exception) {
            return "执行bash脚本时发生错误: ${e.message}"
        }
    }
}


fun queryWorkingDir(sessionId: String): String {
    return when {
        getOS().contains("win") -> {
            ExecutePowerShellScript.getWorkingDir(sessionId)
        }
        getOS().contains("linux") -> {
            ExecuteBashScript.getWorkingDir(sessionId)
        }
        else -> "."
    }
}

private fun ExecuteBashScript.Companion.getWorkingDir(sessionId: String): String {
    return sessionWorkingDirMap.getOrPut(sessionId) { readPersistedWd(sessionId) }
}

fun String.pathExists(): Boolean = Files.exists(Paths.get(this))

fun String.replaceLastOccurrence(oldValue: String, newValue: String): String {
    val lastIndex = this.lastIndexOf(oldValue)
    return if (lastIndex == -1) {
        this // ??? oldValue???????
    } else {
        this.substring(0, lastIndex) + newValue + this.substring(lastIndex + oldValue.length)
    }
}

/**
 * 根据匹配到的写指令模式，返回该命令的简要说明
 */
fun getCommandDescription(pattern: String): String? {
    return when (pattern.trim().lowercase()) {
        // 文件操作
        "mv " -> "移动或重命名文件/目录"
        "cp " -> "复制文件/目录"
        "mkdir " -> "创建目录"
        "touch " -> "创建空文件或更新文件时间戳"
        "rm " -> "删除文件"
        "rmdir " -> "删除空目录"
        "tee " -> "将输出写入文件"
        "sed -i" -> "直接修改文件内容"
        "chmod " -> "修改文件权限"
        "chown " -> "修改文件所有者"
        "ln " -> "创建链接（软链接/硬链接）"
        "tar " -> "打包/解包归档文件"
        "unzip " -> "解压 ZIP 文件"
        "zip " -> "压缩文件为 ZIP 格式"
        ">>" -> "追加内容到文件"
        "> " -> "重定向输出到文件（覆盖）"
        // 安装/卸载
        "install " -> "安装软件或组件"
        "uninstall " -> "卸载软件或组件"
        "apt " -> "Debian/Ubuntu 包管理器操作"
        "yum " -> "CentOS/RHEL 包管理器操作"
        "dnf " -> "Fedora 包管理器操作"
        "brew " -> "Homebrew 包管理器操作"
        "pip install" -> "安装 Python 包"
        "npm install" -> "安装 Node.js 包"
        "yarn add" -> "安装 Node.js 包（Yarn）"
        "make install" -> "编译安装软件"
        "mvn" -> "执行 Maven 构建命令"
        "choco " -> "Chocolatey 包管理器操作（Windows）"
        "scoop " -> "Scoop 包管理器操作（Windows）"
        "winget " -> "Windows 包管理器操作"
        "msiexec" -> "运行 Windows 安装程序"
        "start-process" -> "启动新进程（Windows）"
        // 进程/系统操作
        "kill " -> "终止指定进程"
        "kill -" -> "向进程发送信号"
        "killall " -> "按名称终止所有匹配进程"
        "pkill " -> "按模式匹配终止进程"
        "xkill" -> "点击窗口终止对应进程"
        "shutdown" -> "关闭计算机"
        "reboot" -> "重启计算机"
        "halt" -> "停止系统"
        "poweroff" -> "关闭电源"
        "init 0" -> "关机（init 级别 0）"
        "init 6" -> "重启（init 级别 6）"
        "nohup " -> "后台运行命令（不挂断）"
        "systemctl stop" -> "停止系统服务"
        "systemctl restart" -> "重启系统服务"
        "systemctl disable" -> "禁用系统服务"
        "service " -> "管理系统服务"
        // 网络
        "ifconfig " -> "配置网络接口"
        "ip link set" -> "设置网络链路属性"
        "iptables " -> "配置防火墙规则"
        // 磁盘/挂载
        "mount " -> "挂载文件系统"
        "umount " -> "卸载文件系统"
        "eject " -> "弹出可移动设备"
        // 定时任务
        "crontab " -> "编辑定时任务"
        "at " -> "设置一次性定时任务"
        // 用户管理
        "useradd " -> "添加系统用户"
        "userdel " -> "删除系统用户"
        "usermod " -> "修改用户属性"
        "groupadd " -> "添加用户组"
        "groupdel " -> "删除用户组"
        "passwd " -> "修改用户密码"
        // PowerShell 特有
        "move-item" -> "移动或重命名文件/目录（PowerShell）"
        "copy-item" -> "复制文件/目录（PowerShell）"
        "new-item" -> "创建新文件或目录（PowerShell）"
        "set-content" -> "写入文件内容（PowerShell）"
        "add-content" -> "追加文件内容（PowerShell）"
        "out-file" -> "将输出写入文件（PowerShell）"
        "rename-item" -> "重命名文件/目录（PowerShell）"
        "set-itemproperty" -> "设置项属性（PowerShell）"
        "stop-process" -> "终止进程（PowerShell）"
        "taskkill" -> "终止进程（Windows）"
        "stop-computer" -> "关闭计算机（PowerShell）"
        "restart-computer" -> "重启计算机（PowerShell）"
        "logoff" -> "注销当前用户"
        "stop-service" -> "停止服务（PowerShell）"
        "restart-service" -> "重启服务（PowerShell）"
        "set-service" -> "配置服务属性（PowerShell）"
        "disable-netadapter" -> "禁用网络适配器（PowerShell）"
        "enable-netadapter" -> "启用网络适配器（PowerShell）"
        "new-netfirewallrule" -> "创建防火墙规则（PowerShell）"
        "remove-netfirewallrule" -> "删除防火墙规则（PowerShell）"
        "netsh " -> "网络配置工具（Windows）"
        "mount-diskimage" -> "挂载磁盘映像（PowerShell）"
        "dismount-diskimage" -> "卸载磁盘映像（PowerShell）"
        "schtasks " -> "管理计划任务（Windows）"
        "register-scheduledtask" -> "注册计划任务（PowerShell）"
        "unregister-scheduledtask" -> "取消注册计划任务（PowerShell）"
        "new-localuser" -> "创建本地用户（PowerShell）"
        "remove-localuser" -> "删除本地用户（PowerShell）"
        "set-localuser" -> "修改本地用户（PowerShell）"
        "net user " -> "管理用户账户（Windows）"
        "net stop " -> "停止服务（Windows）"
        "net start " -> "启动服务（Windows）"
        "bcdedit" -> "编辑启动配置数据（Windows）"
        "reagentc" -> "配置 Windows 恢复环境"
        else -> null
    }
}

fun executeBashScript(param: JSONObject): String {
    val script: String = param.getString("script") ?: return "脚本内容不能为空"
    val sessionId: String = param.getString("sessionId") ?: "default"
    val session = ExecuteBashScript()
    return session.executeCommand(script, sessionId)
}

fun parsePath(url: String, sessionId: String): String {
    var parsedUrl = url.replace("\\", "\\\\")

    // 通用相对路径
    if (parsedUrl == ".") {
        parsedUrl = queryWorkingDir(sessionId)
    } else if (parsedUrl.startsWith("./")) {
        parsedUrl = queryWorkingDir(sessionId) + parsedUrl.substring(1)
    }

    // linux相对路径
    if (!parsedUrl.startsWith("/") && !parsedUrl.contains(":")) {
        parsedUrl = "${queryWorkingDir(sessionId)}/$parsedUrl"
    }

    // WSL → Windows 路径转换
    if (parsedUrl.startsWith("/mnt/") && getOS().contains("win")) {
        val driveLetter = parsedUrl.substring(5, 6).uppercase()
        var remainPath = parsedUrl.substring(6).replace("/", "\\")
        if (remainPath == "") {
            remainPath = "/"
        }
        return "$driveLetter:$remainPath"
    }

    return parsedUrl
}

fun main() {
    println(parsePath("C:\\\\Users\\\\cn-molaxu\\\\Desktop\\\\my-test", "default"))
}