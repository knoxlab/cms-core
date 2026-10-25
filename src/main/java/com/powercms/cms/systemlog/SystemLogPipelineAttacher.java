package com.powercms.cms.systemlog;

import ch.qos.logback.classic.LoggerContext;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 将 Spring 管理的 {@link SystemLogPipeline} 回填给 logback.xml 中声明的
 * {@link SystemLogAppender}（Appender 由 Logback 自建、不受容器管理，需桥接注入）。
 * 启动早期（ApplicationReadyEvent 之前）日志只走文件通道，属预期行为。
 */
@Component
public class SystemLogPipelineAttacher {

    private final SystemLogPipeline pipeline;

    public SystemLogPipelineAttacher(SystemLogPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void attach() {
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
            var appender = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
                .getAppender("SYSTEM_LOG_DB");
            if (appender instanceof SystemLogAppender dbAppender) {
                dbAppender.attach(pipeline);
            }
        }
    }
}
