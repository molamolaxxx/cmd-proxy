package com.mola.cmd.proxy.app.utils

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.turbo.TurboFilter
import ch.qos.logback.core.spi.FilterReply
import org.slf4j.LoggerFactory
import org.slf4j.Marker


/**
 * @Project: cmd-proxy
 * @Description:
 * @author : molamola
 * @date : 2023-08-27 03:16
 **/
class LogUtil {

    companion object {
        fun debugReject() {
            val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
            val turboFilterList = loggerContext.turboFilterList
            val turboFilter: TurboFilter = object : TurboFilter() {
                override fun decide(
                    marker: Marker?,
                    logger: ch.qos.logback.classic.Logger?,
                    level: Level,
                    s: String?,
                    objects: Array<out Any>?,
                    throwable: Throwable?
                ): FilterReply {
                    return if (level.levelStr == "debug" || level.levelStr == "DEBUG") {
                        FilterReply.DENY
                    } else FilterReply.ACCEPT
                }
            };
            turboFilterList.add(turboFilter)
        }
    }

}