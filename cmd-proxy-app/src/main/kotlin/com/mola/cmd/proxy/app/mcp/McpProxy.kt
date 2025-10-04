package com.mola.cmd.proxy.app.mcp

import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import com.google.common.collect.Maps
import com.mola.cmd.proxy.client.provider.CmdReceiver
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlin.io.path.absolutePathString

object McpProxy {

    private val log: Logger = LoggerFactory.getLogger(McpProxy::class.java)

    val metaDataList : MutableList<McdMetaData> = mutableListOf()

    fun start(cmdGroupList: List<String>) {
        register("treeFile", "treeFile {'path':'/xxx'}", "以树型结构输出path路径下的所有文件")
        { param ->
            val path: String = param.getString("path") ?: "/"
            return@register printTree(path, 20)
        }

        register("readFile", "readFile {'path':'/xxx'}", "读取path路径对应的文件内容")
        { param ->
            val filePath: String = param.getString("path") ?: "/"

            // 检查文件是否存在
            val file = File(filePath)
            if (!file.exists()) {
                return@register "文件不存在，请先treeFile查看文件列表"
            }

            if (file.isDirectory) {
                return@register "此路径为文件夹，无法直接读取"
            }

            // 检查文件大小不超过32KB
            val maxSize = 32 * 1024
            if (file.length() > maxSize) {
                return@register "文件大小超过32KB限制，读取失败，请终止流程"
            }

            // 读取文件内容
            file.readText(Charset.forName("UTF-8"))
        }

        register("createFile", "createFile {\"path\":\"/xxx\", \"content\":\"yyy\"}", "在path路径创建文件，并将content中的内容写入文件")
        { param ->
            val filePath: String = param.getString("path") ?: "/"
            val content: String = param.getString("content") ?: ""

            // 检查文件是否存在
            val file = File(filePath)
            if (file.exists()) {
                return@register "文件已存在，不允许写入"
            }

            File(filePath).bufferedWriter().use { writer ->
                writer.write(content)
            }

            // 读取文件内容
            return@register "文件写入成功"
        }

        register("modifyFile",
            "modifyFile {\"path\":\"/xxx\", \"originContent\":\"aaa\", \"modifyContent\":\"bbb\"}",
            "修改path路径的文件，将原文件中的所有的originContent文本，替换为modifyContent")
        { param ->
            val filePath: String = param.getString("path") ?: "/"
            var originContent: String = param.getString("originContent") ?: ""
            var modifyContent: String = param.getString("modifyContent") ?: ""

            // 检查文件是否存在
            val file = File(filePath)
            if (!file.exists()) {
                return@register "文件不存在，请先treeFile查看文件列表"
            }

            val fileContent = file.readText(Charsets.UTF_8)
            if (fileContent.indexOf("\r\n") != -1) {
                originContent = originContent.replace("\n", "\r\n")
                modifyContent = modifyContent.replace("\n", "\r\n")
            }
            val newContent = fileContent.replace(originContent, modifyContent)

            // 文件备份
            val userHome = System.getProperty("user.home")
            val backupDir = Paths.get(userHome, ".mcp-file-history")
            if (!Files.exists(backupDir)) {
                Files.createDirectories(backupDir)
            }
            val source: Path = Paths.get(filePath)
            val fileName = File(filePath).name
            val timestamp = System.currentTimeMillis()
            val target: Path = backupDir.resolve("${fileName}.${timestamp}")
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)

            File(filePath).bufferedWriter().use { writer ->
                writer.write(newContent)
            }

            // 读取文件内容
            return@register "文件写入成功"
        }

        register("createDir", "createDir {'path':'/xxx'}", "在path路径创建文件夹")
        { param ->
            val filePath: String = param.getString("path") ?: "/"

            val directory = File(filePath)

            var createRes = "创建文件夹成功"
            if (!directory.exists()) {
                // 如果文件夹不存在，就创建它
                directory.mkdirs().also { success ->
                    if (!success) {
                        createRes = "文件夹创建失败"
                    }
                }
            } else {
                createRes = "文件夹创建失败，文件或文件夹已经存在"
            }

            // 读取文件内容
            return@register createRes
        }

        register("moveFile", "moveFile {'fromPath':'/xxx', 'toPath':'/yyy'}", "将fromPath文件的路径移动到toPath路径")
        { param ->
            val fromPath: String = param.getString("fromPath") ?: "/"
            val toPath: String = param.getString("toPath") ?: "/"

            val sourceFile = File(fromPath)
            val destinationFile = File(toPath)

            // 检查源文件是否存在
            if (!sourceFile.exists()) {
                // 读取文件内容
                return@register "源文件不存在，请先treeFile查看文件列表"
            }

            // 如果目标文件已存在，可以选择删除或重命名
            if (destinationFile.exists()) {
                // 读取文件内容
                return@register "目标文件已存在"
            }

            // 确保目标目录存在
            destinationFile.parentFile?.mkdirs()

            // 执行移动操作
            if (sourceFile.renameTo(destinationFile)) "文件移动成功" else "文件移动失败"
        }

        register("copyFile", "copyFile {'fromPath':'/xxx', 'toPath':'/yyy'}", "将fromPath文件复制到toPath路径")
        { param ->
            val fromPath: String = param.getString("fromPath") ?: "/"
            val toPath: String = param.getString("toPath") ?: "/"

            val sourceFile = File(fromPath)
            val destinationFile = File(toPath)

            // 检查源文件是否存在
            if (!sourceFile.exists()) {
                return@register "源文件不存在，请先treeFile查看文件列表"
            }

            // 如果目标文件已存在，可以选择删除或重命名
            if (destinationFile.exists()) {
                return@register "目标文件已存在"
            }

            // 确保目标目录存在
            destinationFile.parentFile?.mkdirs()

            // 执行复制操作
            try {
                sourceFile.copyTo(destinationFile)
                "文件复制成功"
            } catch (e: Exception) {
                "文件复制失败: ${e.message}"
            }
        }

        register("openUrl", "openUrl {'url':'xxx'}", "在打开url对应的网页或文件") {
                param ->
            var url = param.getString("url")
            when {
                getOS().contains("win") -> {
                    executeCommand("start $url")
                }
                getOS().contains("linux") -> {
                    executeCommand("xdg-open $url")
                }
            }
            "打开成功"
        }


        register("listJavaDependencyPath", "listJavaDependencyPath {'javaFilePath':'/xxx'}", "列出Java文件的所有import类的绝对路径") { param ->
            val javaPath: String = param.getString("javaFilePath") ?: return@register "路径不能为空"
            val javaFile = File(javaPath)
            if (!javaFile.exists() || !javaFile.isFile) return@register "文件不存在，请先treeFile查看文件列表"

            val importRegex = Regex("^import\\s+([\\w.\\*]+);?")
            val imports = javaFile.readLines()
                .mapNotNull { line -> importRegex.matchEntire(line.trim())?.groupValues?.get(1) }
                .filterNot { it.startsWith("java.") || it.startsWith("javax.") }

            val result = StringBuilder()
            result.appendLine("| 类名称 | 绝对路径地址 |")
            result.appendLine("|--------|--------------|")

            imports.forEach { fqcn ->
                val className = fqcn.substringAfterLast('.')
                val relativePath = fqcn.replace('.', '/') + ".java"
                val absolutePath = javaFile.parentFile?.parentFile?.parentFile?.parentFile?.parentFile?.parentFile?.parentFile
                    ?.resolve("src/main/java/$relativePath")
                    ?.absolutePath ?: "未找到"
                result.appendLine("| $className | $absolutePath |")
            }
            result.toString()
        }

        loadExtensions()

        for (mcdMetaData in metaDataList) {
            CmdReceiver.register(mcdMetaData.cmdName, cmdGroupList,
                "#mcp:${mcdMetaData.cmdExample}#next#${mcdMetaData.cmdDesc}") { params ->
                val param: JSONObject = JSON.parse(params.cmdArgs[0]) as JSONObject
                val resultMap = mutableMapOf<String, String>()
                resultMap["result"] = mcdMetaData.executor.invoke(param)
                resultMap
            }
            log.info("register cmd ${mcdMetaData.cmdName} : ${mcdMetaData.cmdDesc}")
        }
    }

    private fun loadExtensions() {
        log.info("load extension path : ${Paths.get(".").absolutePathString()}")
        Files.walk(Paths.get("."))
            .filter { path ->
                Files.isRegularFile(path) &&
                        path.toString().endsWith(".groovy")
            }.forEach {
                log.info("start load extension : ${it.absolutePathString()}")
                val file = File(it.absolutePathString())
                McpExtensionEngine.eval(file.readText(Charset.forName("UTF-8")))
                log.info("finish load extension : ${it.absolutePathString()}")
            }
    }

    private fun printTree(path: String, depth: Int) : String {
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
}

fun register(cmdName: String, cmdExample: String, cmdDesc: String, executor: (param: JSONObject) -> String) {
    McpProxy.metaDataList.add(McdMetaData(cmdName, cmdExample, cmdDesc, executor))
}

data class McdMetaData (
    val cmdName: String,
    val cmdExample: String,
    val cmdDesc: String,
    val executor: (param: JSONObject) -> String
)
