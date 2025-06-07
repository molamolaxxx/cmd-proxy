package com.mola.cmd.proxy.app.mcp

fun executeCommand(command: String): String {
    return try {
        val parts = if (System.getProperty("os.name").lowercase().contains("win")) {
            // Windows 需要显式调用 cmd.exe
            arrayOf("cmd.exe", "/c", command)
        } else {
            // Unix-like 系统直接使用 shell
            arrayOf("/bin/sh", "-c", command)
        }

        val process = Runtime.getRuntime().exec(parts)
        val output = process.inputStream.bufferedReader().readText()
        val error = process.errorStream.bufferedReader().readText()
        process.waitFor()

        if (process.exitValue() == 0) output else error
    } catch (e: Exception) {
        e.message ?: "Unknown error"
    }
}

fun getOS(): String {
    return System.getProperty("os.name").lowercase()
}