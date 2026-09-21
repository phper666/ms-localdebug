package com.localdebug.plugin.core.util;

import lombok.experimental.UtilityClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * 日志输出工具类（headless 版本）
 * 只保留纯日志输出能力，Swing 面板渲染已剥离，由 P2 的 UI 层通过 Consumer&lt;String&gt; 接收
 */
@UtilityClass
public class LogOutputUtil {
    private static final Logger log = LoggerFactory.getLogger(LogOutputUtil.class);

    /**
     * 输出信息：同时写 slf4j 日志和调用方提供的 logger
     */
    public static void log(Consumer<String> logger, String message) {
        log.info(message);
        if (logger != null) {
            logger.accept(message);
        }
    }

    /**
     * 输出错误信息：同时写 slf4j 日志和调用方提供的 logger
     */
    public static void error(Consumer<String> logger, String message) {
        log.error(message);
        if (logger != null) {
            logger.accept("错误: " + message);
        }
    }
}
