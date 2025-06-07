package com.mola.cmd.proxy.app.mcp

import javax.script.ScriptEngine
import javax.script.ScriptEngineManager
import javax.script.SimpleBindings


/**
 * @Project: neptune
 * @Description:
 * @author : molamola
 * @date : 2024-06-10 12:23
 **/
object McpExtensionEngine {

    private lateinit var scriptEngine : ScriptEngine

    init {
        val manager = ScriptEngineManager()
        scriptEngine = manager.getEngineByName("groovy")
    }

    fun eval(script: String) {
        // 创建 Bindings 对象并设置 input 参数
        scriptEngine.eval(script, SimpleBindings())
    }
}