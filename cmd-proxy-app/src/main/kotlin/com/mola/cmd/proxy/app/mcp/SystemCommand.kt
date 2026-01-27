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
            resultMap["result"] = "操作系统:${getOS()}\n当前路径:${parsePath(queryWorkingDir())}"
            resultMap
        }

        CmdReceiver.register("queryLastProcessDir", cmdGroupList,
            "读取最新的process文件夹信息") { params ->
            val processDir = File("${parsePath(queryWorkingDir())}/.process")
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
            if (listFiles == null || listFiles.size < 2) {
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

        CmdReceiver.register("openUrl", cmdGroupList, "在打开url对应的网页或文件") {
                param ->
            val param: JSONObject = JSON.parse(param.cmdArgs[0]) as JSONObject
            val url = parsePath(param.getString("url"))
            when {
                getOS().contains("win") -> {
                    executeCommand("start $url")
                }
                getOS().contains("linux") -> {
                    executeCommand("xdg-open $url")
                }
            }

            mutableMapOf<String, String>()
        }

        CmdReceiver.register("listSkills", cmdGroupList,
            "读取并展示 .skills 目录下所有 skill 的元信息及目录结构") { params ->
            val resultMap = mutableMapOf<String, String>()
            val lines = mutableListOf<String>()
            lines.add("使用loadSkill指令加载技能")
            lines.add("|skill名称|描述|")
            lines.add("|---|---|")
            
            val skillNameSet = mutableSetOf<String>()
            
            val workSkillsDir = File("${parsePath(queryWorkingDir())}/.skills")
            val userSkillsDir = File("${System.getProperty("user.home")}/.skills")
            
            processSkillsDirectory(workSkillsDir, skillNameSet, lines)
            processSkillsDirectory(userSkillsDir, skillNameSet, lines)
            
            if (lines.size == 3) {
                resultMap["result"] = "暂无可用Skill"
            } else{
                resultMap["result"] = lines.joinToString("\n")
            }
            return@register resultMap
        }
    }
    
    private fun processSkillsDirectory(skillsDir: File, skillNameSet: MutableSet<String>, lines: MutableList<String>) {
        if (!skillsDir.exists() || !skillsDir.isDirectory) return
        
        val skillDirs = skillsDir.listFiles { file -> file.isDirectory } ?: arrayOf<File>()
        for (skillDir in skillDirs) {
            val skillMd = File(skillDir, "SKILL.md")
            if (!skillMd.exists()) continue
            val content = skillMd.readText()
            val name = Regex("""^name:\s*(.+)""", RegexOption.MULTILINE).find(content)?.groupValues?.get(1)?.trim()
            val desc = Regex("""^description:\s*(.+)""", RegexOption.MULTILINE).find(content)?.groupValues?.get(1)?.trim()
            if (!name.isNullOrEmpty() && !desc.isNullOrEmpty()) {
                if (name !in skillNameSet) {
                    skillNameSet.add(name)
                    lines.add("|${name}|${desc}|")
                }
            }
        }
    }
}
