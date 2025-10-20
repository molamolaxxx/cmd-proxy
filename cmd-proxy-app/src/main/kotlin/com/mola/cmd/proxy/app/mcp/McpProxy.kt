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
import kotlin.streams.toList

object McpProxy {

    private val log: Logger = LoggerFactory.getLogger(McpProxy::class.java)

    val metaDataList : MutableList<CmdMetaData> = mutableListOf()

    var blacklist: List<String> = arrayListOf()

    fun start(cmdGroupList: List<String>) {
        val blacklistFile = File("./cmd-blacklist.txt")
        if (blacklistFile.exists()) {
            blacklist = blacklistFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        }

        // 注册系统指令
        SystemCommand.register(cmdGroupList)

        register("treeFile", "treeFile {'path':'/xxx'}", "以树型结构输出path路径下的所有文件")
        { param ->
            var path: String = parsePath(param.getString("path") ?: "/")
            return@register printTree(path, 20)
        }

        register("readFile", "readFile {'path':'/xxx'}", "读取path路径对应的文件内容")
        { param ->
            var filePath: String = parsePath(param.getString("path") ?: "/")

            // 检查文件是否存在
            val file = File(filePath)
            if (!file.exists()) {
                return@register "文件不存在，请先treeFile查看文件列表"
            }

            if (file.isDirectory) {
                return@register "此路径为文件夹，无法直接读取"
            }

            // 检查文件大小不超过32KB
            val maxSize = 64 * 1024
            if (file.length() > maxSize) {
                return@register "文件大小超过64KB限制，读取失败，请终止流程"
            }

            // 读取文件内容
            file.readText(Charset.forName("UTF-8"))
        }

        register("createFile", "createFile {\"path\":\"/xxx\", \"content\":\"yyy\"}", "在path路径创建文件，并将content中的内容写入文件")
        { param ->
            val filePath: String = parsePath(param.getString("path") ?: "/")
            val content: String = param.getString("content") ?: ""

            var targetFile = File(filePath)
            // 如果文件已存在，则按规则生成新文件名
            if (targetFile.exists()) {
                val parent = targetFile.parentFile
                val baseName = targetFile.nameWithoutExtension
                val ext = targetFile.extension.let { if (it.isEmpty()) "" else ".$it" }
                // 扫描同级目录下已存在的序列号文件
                val seqList = parent.listFiles { f ->
                    f.name.matches(Regex("""^\Q$baseName\E\.(\d+)\Q$ext\E$"""))
                }?.mapNotNull {
                    Regex("""^\Q$baseName\E\.(\d+)\Q$ext\E$""").matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull()
                }?.sortedDescending() ?: emptyList()
                val nextSeq = (seqList.firstOrNull() ?: 0) + 1
                targetFile = File(parent, "$baseName.$nextSeq$ext")
            }

            // 确保父目录存在（处理多级文件夹的情况）
            targetFile.parentFile?.mkdirs()

            targetFile.bufferedWriter().use { writer ->
                writer.write(content)
            }

            return@register "文件写入成功：${targetFile.name}"
        }

        register("modifyFile",
            "modifyFile {\"path\":\"/xxx\", \"originContent\":\"aaa\", \"modifyContent\":\"bbb\"}",
            "修改path路径的文件，将原文件中的所有的originContent文本，替换为modifyContent")
        { param ->
            val filePath: String = parsePath(param.getString("path") ?: "/")
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
            // versionNo的获取逻辑如下：取相同文件名、相同processId最大的versionNo+1，如果不存在该文件，则versionNo = 1\
            val processId = param.getString("processId") ?: "default"
            val existingVersions = Files.list(backupDir)
                .filter { it.fileName.toString().startsWith("$fileName.$processId.") }
                .map { it.fileName.toString().substringAfterLast(".").toIntOrNull() ?: 0 }
                .sorted(Comparator.naturalOrder<Int?>().reversed())
                .toList()

            val versionNo = if (existingVersions.isEmpty()) 1 else existingVersions.first() + 1
            val target: Path = backupDir.resolve("${fileName}.$processId.$versionNo")
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)

            File(filePath).bufferedWriter().use { writer ->
                writer.write(newContent)
            }

            // 读取文件内容
            return@register "文件写入成功"
        }

        register("createDir", "createDir {'path':'/xxx'}", "在path路径创建文件夹")
        { param ->
            val filePath: String = parsePath(param.getString("path") ?: "/")

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
            val fromPath: String = parsePath(param.getString("fromPath") ?: "/")
            val toPath: String = parsePath(param.getString("toPath") ?: "/")

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
            var fromPath: String = parsePath(param.getString("fromPath") ?: "/")
            var toPath: String = parsePath(param.getString("toPath") ?: "/")

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



        if (getOS().contains("win")) {
            // Windows 环境使用 WSL 执行 bash
            register("executeBash", "executeBash {\"script\":\"ls -l '/mnt/c'\"}", "执行bash脚本，并返回bash的输出内容。Windows路径需要转换为Linux路径（C: → /mnt/c）")
            { param ->
                executeBashScript(param)
            }
        } else {
            // Linux/macOS 直接使用 bash
            register("executeBash", "executeBash {'script':'ls -l'}", "执行bash脚本，并返回bash的输出内容")
            { param ->
                executeBashScript(param)
            }
        }

        register("openUrl", "openUrl {'url':'xxx'}", "在打开url对应的网页或文件") {
                param ->
            var url = parsePath(param.getString("url"))
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

        loadExtensions()

        for (mcdMetaData in metaDataList) {
            CmdReceiver.register(mcdMetaData.cmdName, cmdGroupList,
                "#mcp:${mcdMetaData.cmdExample}#next#${mcdMetaData.cmdDesc}") { params ->
                val param: JSONObject = JSON.parse(params.cmdArgs[0]) as JSONObject
                param["processId"] = params.cmdArgs[1]
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
}

fun register(cmdName: String, cmdExample: String, cmdDesc: String, executor: (param: JSONObject) -> String) {
    if (cmdName in McpProxy.blacklist) {
        return
    }
    McpProxy.metaDataList.add(CmdMetaData(cmdName, cmdExample, cmdDesc, executor))
}

data class CmdMetaData (
    val cmdName: String,
    val cmdExample: String,
    val cmdDesc: String,
    val executor: (param: JSONObject) -> String
)
