package com.mola.cmd.proxy.app.utils

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL


/**
 * @Project: cmd-proxy
 * @Description:
 * @author : molamola
 * @date : 2023-08-20 18:14
 **/
class NetworkUtils {

    companion object {
        // 发送 POST 请求
        fun post(urlString: String, headers: Map<String, String>?, payload: String, timeout: Int): String {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"

            // 添加请求头
            headers?.forEach { (key, value) ->
                connection.setRequestProperty(key, value)
            }

            // 发送请求体
            connection.doOutput = true
            connection.connectTimeout = timeout
            val writer = BufferedWriter(OutputStreamWriter(connection.outputStream))
            writer.write(payload)
            writer.flush()
            writer.close()

            // 获取响应
            val response = StringBuilder()
            BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                var line: String? = reader.readLine()
                while (line != null) {
                    response.append(line)
                    line = reader.readLine()
                }
            }
            connection.disconnect()

            return response.toString()
        }

        // 发送 GET 请求
        fun get(urlString: String, headers: Map<String, String>?): String {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"

            // 添加请求头
            headers?.forEach { (key, value) ->
                connection.setRequestProperty(key, value)
            }

            // 获取响应
            val response = StringBuilder()
            BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                var line: String? = reader.readLine()
                while (line != null) {
                    response.append(line)
                    line = reader.readLine()
                }
            }
            connection.disconnect()

            return response.toString()
        }
    }
}