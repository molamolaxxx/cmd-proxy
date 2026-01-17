package com.mola.cmd.proxy.app.mcp

import com.alibaba.fastjson.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Paths

/**
 * 执行PowerShell脚本并返回输出内容
 */
class ExecutePowerShellScript {
    companion object {
        private val PERSIST_FILE = System.getProperty("user.home") + "/.cmd-proxy-ps-wd"
        var workingDirectory: String = readPersistedWd()

        private fun readPersistedWd(): String {
            return try {
                val file = File(PERSIST_FILE)
                if (!file.exists() || file.length() == 0L) return "."
                file.readText(Charset.defaultCharset()).trim().takeIf { it.isNotEmpty() } ?: "."
            } catch (e: Exception) {
                "."
            }
        }

        private fun writePersistedWd(path: String) {
            try {
                File(PERSIST_FILE).writeText(path, Charset.defaultCharset())
            } catch (ignored: Exception) {
            }
        }
    }

    fun executeCommand(script: String): String {
        // 危险指令黑名单（防止执行高危操作）
        val dangerousPatterns = listOf(
            "Remove-Item",
            "rm -r",
            "rm -recurse",
            "Stop-Computer",
            "Restart-Computer",
            "format",
            "del",
            "rmdir",
            "Invoke-Expression",
            "iex",
            "sudo",
            "su "
        )

        for (pattern in dangerousPatterns) {
            if (script.contains(pattern, ignoreCase = true)) {
                return "禁止执行危险指令: $pattern"
            }
        }

        try {
            // 将当前工作目录转换为绝对路径（防止路径转换问题）
            val absWd = if (workingDirectory == ".") {
                System.getProperty("user.dir")
            } else {
                val f = File(workingDirectory)
                if (f.isAbsolute) workingDirectory else f.absolutePath
            }

            // PowerShell 命令构建
            // Set-Location 设置工作目录，然后执行脚本
            // 如果是 cd 命令，我们在脚本内部处理并输出当前路径
            val fullCommand = if (script.trim().startsWith("cd ", ignoreCase = true)) {
                "$script; (Get-Location).Path"
            } else {
                script
            }

            println("PowerShell Command: $fullCommand, WD: $absWd")
            
            // 使用 powershell.exe 执行
            val command: List<String> = listOf("powershell.exe", "-Command", fullCommand)

            val process = ProcessBuilder(command)
                .directory(File(absWd)) // 设置工作目录
                .redirectErrorStream(true)
                .start()

            val reader = BufferedReader(InputStreamReader(process.inputStream, Charset.forName("GBK"))) // Windows通常使用GBK编码
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
            // PowerShell 的 cd 执行后，我们通过 (Get-Location).Path 获取了新路径
            val lowerScript = script.trim().lowercase()
            if (lowerScript.startsWith("cd ")) {
                if (lastLine != null && lastLine.pathExists()) {
                    workingDirectory = lastLine
                    writePersistedWd(lastLine)
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
            return "执行PowerShell脚本时发生错误: ${e.message}"
        }
    }
}


fun executePowerShellScript(param: JSONObject): String {
    val script: String = param.getString("script") ?: return "脚本内容不能为空"
    val session = ExecutePowerShellScript()
    return session.executeCommand(script)
}
