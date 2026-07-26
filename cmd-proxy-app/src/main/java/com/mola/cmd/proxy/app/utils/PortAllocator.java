package com.mola.cmd.proxy.app.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Collections;
import java.util.Set;

/**
 * 端口自动分配器。
 * <p>
 * 多套环境并行时无需用户手工规划端口：从基准端口开始向后探测，
 * 跳过“已被其它存活实例登记”与“本机实际无法 bind”的端口，返回第一个可用端口。
 * <p>
 * 第一个启动的环境总能拿到基准端口（如 10020 / 10528），后续环境自动顺延，
 * 因此默认环境的端口与历史行为保持一致。
 */
public final class PortAllocator {

    private static final Logger logger = LoggerFactory.getLogger(PortAllocator.class);

    /** 默认最大探测步数 */
    public static final int DEFAULT_MAX_PROBE = 50;

    private PortAllocator() {
    }

    public static int allocate(String usage, int basePort) {
        return allocate(usage, basePort, Collections.<Integer>emptySet(), DEFAULT_MAX_PROBE);
    }

    public static int allocate(String usage, int basePort, Set<Integer> reserved) {
        return allocate(usage, basePort, reserved, DEFAULT_MAX_PROBE);
    }

    /**
     * 从 basePort 起分配一个可用端口。
     *
     * @param usage    用途描述，仅用于日志
     * @param basePort 基准端口
     * @param reserved 已被其它存活实例登记的端口，直接跳过（避免 TOCTOU 抢占）
     * @param maxProbe 最大探测步数
     * @return 可用端口
     * @throws IllegalStateException 探测范围内无可用端口
     */
    public static int allocate(String usage, int basePort, Set<Integer> reserved, int maxProbe) {
        if (basePort <= 0 || basePort > 65535) {
            throw new IllegalStateException("基准端口非法: " + usage + "=" + basePort);
        }
        for (int offset = 0; offset <= maxProbe; offset++) {
            int port = basePort + offset;
            if (port > 65535) {
                break;
            }
            if (reserved != null && reserved.contains(port)) {
                continue;
            }
            if (!isFree(port)) {
                continue;
            }
            if (offset > 0) {
                logger.info("{} 端口 {} 不可用，自动顺延到 {}", usage, basePort, port);
            }
            return port;
        }
        throw new IllegalStateException(String.format(
                "%s 端口分配失败：%d ~ %d 均不可用", usage, basePort, Math.min(basePort + maxProbe, 65535)));
    }

    /** 试 bind 判断端口是否空闲 */
    public static boolean isFree(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            socket.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
