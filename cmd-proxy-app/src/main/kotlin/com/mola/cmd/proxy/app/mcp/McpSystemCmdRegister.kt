package com.mola.cmd.proxy.app.mcp

import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import com.mola.cmd.proxy.client.provider.CmdReceiver
import java.io.File


object SystemCommand {
    fun register(cmdGroupList: List<String>){
        CmdReceiver.register("systemSettings", cmdGroupList,
            "系统环境变量获取") { params ->
            val resultMap = mutableMapOf<String, String>()
            resultMap["result"] = "操作系统:${getOS()}\n当前路径:${parsePath(ExecuteBashScript.workingDirectory)}"
            resultMap
        }

        CmdReceiver.register("queryLastProcessDir", cmdGroupList,
            "读取最新的process文件夹信息") { params ->
            val processDir = File("${parsePath(ExecuteBashScript.workingDirectory)}/.process")
            val resultMap = mutableMapOf<String, String>()
            if (!processDir.exists() || !processDir.isDirectory) {
                resultMap["result"] = ""
                return@register resultMap
            }
            val latest = processDir.listFiles { file -> file.isDirectory }
                ?.maxByOrNull { it.lastModified() } ?: return@register resultMap
            val listFiles = latest.listFiles { file ->
                file.name.equals("todoList.md") || file.name.equals("resource.md")
                        || file.name.equals("request.txt") || file.name.equals("question.md")}
            if (listFiles == null || listFiles.size < 3) {
                println("queryLastProcessDir fileSize error $listFiles")
                return@register resultMap
            }

            val existFiles = mutableSetOf<String>()
            listFiles.forEach { if (it.exists()) existFiles.add(it.name) }

            resultMap["result"] = latest.name ?: ""
            resultMap["existFiles"] = existFiles.joinToString(",")
            resultMap
        }

        CmdReceiver.register("createFile64", cmdGroupList,
            "创建文件base64格式") { params ->
            val param: JSONObject = JSON.parse(params.cmdArgs[0]) as JSONObject
            val filePath: String = parsePath(param.getString("path") ?: "/")
            val base64: String = param.getString("base64") ?: ""

            val targetFile = File(filePath)

            val resultMap = mutableMapOf<String, String>()
            if (targetFile.exists()) {
                return@register resultMap
            }

            // 确保父目录存在（处理多级文件夹的情况）
            targetFile.parentFile?.mkdirs()

            val content = String(java.util.Base64.getDecoder().decode(base64))
            targetFile.bufferedWriter().use { writer ->
                writer.write(content)
            }

            return@register resultMap
        }
    }
}
