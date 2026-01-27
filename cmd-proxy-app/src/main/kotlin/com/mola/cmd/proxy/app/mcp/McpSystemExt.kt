package com.mola.cmd.proxy.app.mcp

import com.google.common.collect.Maps
import java.io.File

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


fun printTree(path: String, depth: Int) : String {
    val root = File(path)
    if (!root.exists()) {
        return "路径不存在"
    }
    if (!root.isDirectory) {
        return "路径不是文件夹"
    }
    if (depth < 1) {
        return "文件路径打印失败"
    }
    if (root.listFiles()?.isNotEmpty() == false) {
        return "无文件"
    }
    var currentDepth = 0
    val sb = StringBuilder()
    val depthFileCntMap = Maps.newLinkedHashMap<Int, Int>()
    fun walk(dir: File, prefix: String) {
        currentDepth ++
        if (depthFileCntMap[currentDepth] == null) {
            depthFileCntMap[currentDepth] = 0
        }
        depthFileCntMap[currentDepth] = depthFileCntMap[currentDepth]!! + dir.listFiles()!!.size
        dir.listFiles()?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name })?.forEachIndexed { index, file ->
            val isLast = index == dir.listFiles()!!.size - 1
            if (file.name.endsWith(".class")) {
                return@forEachIndexed
            }
            val node = if (isLast) "└──" else "├──"
            sb.appendLine("$prefix$node${file.name}")
            if (file.isDirectory) {
                // 检查是否为隐藏文件夹（以.开头）
                if (file.name.startsWith(".")) {
                    return@forEachIndexed
                }
                if ((file.listFiles()?.size ?: 0) > 100) {
                    sb.appendLine(prefix + (if (isLast) "    └──(文件数量过多，已隐藏)" else "│   └──(文件数量过多，已隐藏)"))
                } else {
                    if (currentDepth < depth) {
                        walk(file, prefix + (if (isLast) "    " else "│   "))
                    }
                }
            }
        }
        currentDepth --
    }
    sb.appendLine(root.name)
    walk(root, "")

    var totalFileCount = 0
    for ((index, i) in depthFileCntMap.values.withIndex()) {
        totalFileCount += i
        if (totalFileCount > 500) {
            return printTree(path, index)
        }
    }

    return sb.toString()
}