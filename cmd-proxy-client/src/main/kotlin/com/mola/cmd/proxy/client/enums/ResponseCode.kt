package com.mola.cmd.proxy.client.enums


/**
 * @Project: cmd-proxy
 * @Description:
 * @author : molamola
 * @date : 2023-08-06 22:37
 **/
enum class ResponseCode(code: Int, desc: String) {
    SUCCESS(0, "SUCCESS"),
    ERROR(1, "ERROR");

    private val code: Int
    private val desc: String

    init {
        this.code = code
        this.desc = desc
    }

    fun getCode(): Int {
        return code
    }

    fun getDesc(): String {
        return desc
    }
}
