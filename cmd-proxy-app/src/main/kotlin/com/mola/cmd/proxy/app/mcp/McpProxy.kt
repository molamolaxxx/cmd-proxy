package com.mola.cmd.proxy.app.mcp

import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import com.mola.cmd.proxy.client.provider.CmdReceiver
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.absolutePathString

object McpProxy {

    private val log: Logger = LoggerFactory.getLogger(McpProxy::class.java)

    val metaDataList : MutableList<McdMetaData> = mutableListOf()

    fun start(cmdGroupList: List<String>) {
        register("listFile", "listFile {'path':'/xxx'}", "列出/xxx下文件列表")
        { param ->
            val path: String = param.getString("path") ?: "/"
            val targetDir = File(path)
            val fileMap = mutableMapOf<String, String>()

            if (!targetDir.exists() || !targetDir.isDirectory) {
                fileMap["result"] = "路径不存在或不是目录"
            } else {
                val files = targetDir.listFiles()
                val fileList = files?.filter { it.isFile }?.joinToString("\n") { it.name } ?: ""
                val dirList = files?.filter { it.isDirectory }?.joinToString("\n") { it.name } ?: ""

                fileMap["文件列表"] = fileList.ifEmpty { "无" }
                fileMap["文件夹列表"] = dirList.ifEmpty { "无" }
            }
            JSON.toJSONString(fileMap)
        }

        register("readFile", "readFile {'path':'/xxx'}", "读取/xxx的文件内容")
        { param ->
            val filePath: String = param.getString("path") ?: "/"

            // 检查文件是否存在
            val file = File(filePath)
            if (!file.exists()) {
                return@register "文件不存在"
            }

            // 检查文件大小不超过16KB (16 * 1024 = 16384字节)
            val maxSize = 16 * 1024
            if (file.length() > maxSize) {
                return@register "文件大小超过16KB限制，读取失败，流程终止"
            }

            // 读取文件内容
            file.readText(Charset.forName("UTF-8"))
        }

        register("openFile", "openFile {'path':'/xxx'}", "打开/xxx对应的文件或文件夹") {
                param ->
            var path = param.getString("path")
            // 检查文件是否存在
            val file = File(path)
            if (!file.exists()) {
                return@register "文件不存在,请重新查找"
            }

            when {
                getOS().contains("win") -> {
                    executeCommand("start $path")
                }
                getOS().contains("linux") -> {
                    executeCommand("xdg-open $path")
                }
            }
            "打开成功"
        }


        register("writeFile", "writeFile {'path':'/xxx', 'content':'yyyyy'}", "在path路径创建文件，并写入content中的内容")
        { param ->
            val filePath: String = param.getString("path") ?: "/"
            val content: String = param.getString("content") ?: ""

            // 检查文件是否存在
            val file = File(filePath)
            if (file.exists()) {
                return@register "文件或文件夹已存在，不允许写入"
            }

            File(filePath).bufferedWriter().use { writer ->
                writer.write(content)
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

        register("moveFile", "moveFile {'fromPath':'/xxx', 'toPath':'/yyy'}", "将/xxx文件的路径转换为/yyy")
        { param ->
            val fromPath: String = param.getString("fromPath") ?: "/"
            val toPath: String = param.getString("toPath") ?: "/"

            val sourceFile = File(fromPath)
            val destinationFile = File(toPath)

            // 检查源文件是否存在
            if (!sourceFile.exists()) {
                // 读取文件内容
                return@register "源文件不存在，请重新查找"
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

        register("openUrl", "openUrl {'url':'xxx'}", "打开/在浏览器打开url对应的页面") {
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

        loadExtensions()

        for (mcdMetaData in metaDataList) {
            CmdReceiver.register(mcdMetaData.cmdName, cmdGroupList,
                "#mcp:${mcdMetaData.cmdExample}\n${mcdMetaData.cmdDesc}") { params ->
                val param: JSONObject = JSON.parse(params.cmdArgs[0]) as JSONObject
                val resultMap = mutableMapOf<String, String>()
                resultMap["result"] = mcdMetaData.executor.invoke(param)
                resultMap
            }
            log.info("register cmd ${mcdMetaData.cmdName} : ${mcdMetaData.cmdDesc}")
        }
    }

    private fun loadExtensions() {
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
