package com.mola.cmd.proxy.app.mcp

import com.alibaba.fastjson.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Paths

/**
 * 执行bash脚本并返回输出内容
 */
class ExecuteBashScript {
    companion object {
        var workingDirectory: String = "."
    }

    fun executeCommand(script: String): String {
        // 危险指令黑名单（防止执行高危操作）
        val dangerousPatterns = listOf(
            "rm",
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
                if (lastLine != null && parsePath(lastLine).pathExists()) {
                    workingDirectory = lastLine
                    val lines = result.lines()
                    if (lines.isNotEmpty()) {
                        result = lines.dropLast(1).joinToString("\n")
                    }
                }
            }
            return result
        } catch (e: Exception) {
            return "执行bash脚本时发生错误: ${e.message}"
        }
    }
}

fun String.pathExists(): Boolean = Files.exists(Paths.get(this))

fun executeBashScript(param: JSONObject): String {
    val script: String = param.getString("script") ?: return "脚本内容不能为空"
    val session = ExecuteBashScript()
    return session.executeCommand(script)
}

fun parsePath(url: String): String {
    var parsedUrl = url
    if (url == ".") {
        parsedUrl = ExecuteBashScript.workingDirectory
    } else if (url.startsWith("./")) {
        parsedUrl = ExecuteBashScript.workingDirectory + url.substring(1)
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
    val parsedUrl = "/mnt/c"
    val driveLetter = parsedUrl.substring(5, 6).uppercase()
    val remainPath = parsedUrl.substring(6).replace("/", "\\")
    println("$driveLetter:$remainPath")
}