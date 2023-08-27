package com.mola.cmd.proxy.client.resp

import com.mola.cmd.proxy.client.enums.CmdResponseCode
import java.io.Serializable


/**
 * @Project: cmd-proxy
 * @Description:
 * @author : molamola
 * @date : 2023-08-06 22:48
 **/

class CmdInvokeResponse<T> : Serializable {
    private var status: Int
    private var msg: String? = null
    private var data: T? = null

    private constructor(status: Int) {
        this.status = status
    }

    private constructor(status: Int, data: T) {
        this.status = status
        this.data = data
    }

    private constructor(status: Int, msg: String, data: T) {
        this.status = status
        this.msg = msg
        this.data = data
    }

    private constructor(status: Int, msg: String) {
        this.status = status
        this.msg = msg
    }

    fun getStatus(): Int {
        return status
    }

    fun setStatus(status: Int) {
        this.status = status
    }

    fun getMsg(): String? {
        return msg
    }

    fun setMsg(msg: String?) {
        this.msg = msg
    }

    fun getData(): T? {
        return data
    }

    fun setData(data: T) {
        this.data = data
    }

    fun isSuccess() : Boolean {
        return this.status == CmdResponseCode.SUCCESS
    }

    companion object {
        fun <T> success(): CmdInvokeResponse<T> {
            return CmdInvokeResponse<T>(CmdResponseCode.SUCCESS, CmdResponseCode.SUCCESS_DESC)
        }

        fun <T> success(msg: String): CmdInvokeResponse<T> {
            return CmdInvokeResponse<T>(CmdResponseCode.SUCCESS, msg)
        }

        fun <T> success(data: T): CmdInvokeResponse<T> {
            return CmdInvokeResponse<T>(CmdResponseCode.SUCCESS, data)
        }

        fun <T> success(msg: String, data: T): CmdInvokeResponse<T> {
            return CmdInvokeResponse<T>(CmdResponseCode.SUCCESS, msg, data)
        }

        fun <T> error(): CmdInvokeResponse<T> {
            return CmdInvokeResponse<T>(CmdResponseCode.ERROR, CmdResponseCode.ERROR_DESC)
        }

        fun <T> error(errorMessage: String): CmdInvokeResponse<T> {
            return CmdInvokeResponse<T>(CmdResponseCode.ERROR, errorMessage)
        }

        fun <T> error(errorCode: Int, errorMessage: String): CmdInvokeResponse<T> {
            return CmdInvokeResponse(errorCode, errorMessage)
        }
    }
}
