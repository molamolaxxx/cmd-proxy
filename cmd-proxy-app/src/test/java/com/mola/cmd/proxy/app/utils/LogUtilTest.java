package com.mola.cmd.proxy.app.utils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LogUtilTest {

    @Test
    public void debugRejectMustNotForceAcceptNonDebugLogs() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        List<TurboFilter> filters = context.getTurboFilterList();
        int originalSize = filters.size();
        Logger logger = context.getLogger("test.logger");
        Level originalLevel = logger.getLevel();

        try {
            LogUtil.Companion.debugReject();
            TurboFilter filter = filters.get(filters.size() - 1);

            assertEquals(FilterReply.DENY,
                    filter.decide(null, logger, Level.DEBUG, "debug", null, null));
            assertEquals(FilterReply.NEUTRAL,
                    filter.decide(null, logger, Level.INFO, "info", null, null));
            assertEquals(FilterReply.NEUTRAL,
                    filter.decide(null, logger, Level.WARN, "warn", null, null));

            logger.setLevel(Level.WARN);
            assertFalse("INFO must still obey the logger's WARN level", logger.isInfoEnabled());
            assertTrue(logger.isWarnEnabled());
        } finally {
            logger.setLevel(originalLevel);
            while (filters.size() > originalSize) {
                filters.remove(filters.size() - 1);
            }
        }
    }
}
