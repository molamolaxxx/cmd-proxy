package com.mola.cmd.proxy.app.imagegenerate

import com.alibaba.fastjson.JSONObject
import com.google.common.collect.Maps
import com.mola.cmd.proxy.app.HttpCommonService
import com.mola.cmd.proxy.app.constants.CmdProxyConstant
import com.mola.cmd.proxy.client.provider.CmdReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.util.Assert
import java.util.concurrent.ConcurrentHashMap

object ImageGenerateProxy {

    private val resMap: MutableMap<String, String> = ConcurrentHashMap()

    private val log: Logger = LoggerFactory.getLogger(ImageGenerateProxy::class.java)

    fun start() {

        // 提交任务接收
        CmdReceiver.register("submitTask", CmdProxyConstant.IMAGE_GENERATE) { param ->
            val prompt = param.cmdArgs[0]
            val sessionId = param.cmdArgs[1]

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val body = JSONObject()
                    body["prompt"] = prompt
                    body["steps"] = 35
                    body["width"] = 512
                    body["height"] = 512
                    body["sampler_index"] = "Euler"
                    val res: String = HttpCommonService.INSTANCE.post(
                        "http://127.0.0.1:12345/sdapi/v1/txt2img",
                        body, 600000, null
                    )
                    val jsonObject = JSONObject.parseObject(res)
                    val array = jsonObject.getJSONArray("images")
                    for (o in array) {
                        Assert.isTrue(o is String, "o not instanceof String")
                        resMap[sessionId] = o as String
                    }
                } catch (e: Exception) {
                    log.error("ImageGenerateTask error, prompt = $prompt", e)
                    resMap[sessionId] = "error"
                }
            }
            Maps.newHashMap()
        }

        // 获取结果接收
        CmdReceiver.register("getResult", CmdProxyConstant.IMAGE_GENERATE) { param ->
            val sessionId = param.cmdArgs[0]
            val res = resMap[sessionId]
            if (null != res) {
                resMap.remove(sessionId)
            }
            Assert.isTrue("error" != res, "任务执行失败, res = $res")

            mapOf("result" to res)
        }
    }
}