package com.mola.cmd.proxy.client.resp

import com.mola.cmd.proxy.client.enums.ResponseCode
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

    companion object {
        fun <T> success(): CmdInvokeResponse<T> {
            return CmdInvokeResponse<T>(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getDesc())
        }

        fun <T> success(msg: String): CmdInvokeResponse<T> {
            return CmdInvokeResponse<T>(ResponseCode.SUCCESS.getCode(), msg)
        }

        fun <T> success(data: T): CmdInvokeResponse<T> {
            return CmdInvokeResponse<T>(ResponseCode.SUCCESS.getCode(), data)
        }

        fun <T> success(msg: String, data: T): CmdInvokeResponse<T> {
            return CmdInvokeResponse<T>(ResponseCode.SUCCESS.getCode(), msg, data)
        }

        fun <T> error(): CmdInvokeResponse<T> {
            return CmdInvokeResponse<T>(ResponseCode.ERROR.getCode(), ResponseCode.ERROR.getDesc())
        }

        fun <T> error(errorMessage: String): CmdInvokeResponse<T> {
            return CmdInvokeResponse<T>(ResponseCode.ERROR.getCode(), errorMessage)
        }

        fun <T> error(errorCode: Int, errorMessage: String): CmdInvokeResponse<T> {
            return CmdInvokeResponse(errorCode, errorMessage)
        }
    }
}
