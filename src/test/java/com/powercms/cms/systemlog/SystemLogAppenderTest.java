package com.powercms.cms.systemlog;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.powercms.cms.systemlog.entity.RuntimeLogEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-LOG-RT-002/005/006 Appender 字段映射与截断规则单测（AC-LOG-002）。
 */
class SystemLogAppenderTest {

    private SystemLogAppender appender;
    private LoggerContext context;

    @BeforeEach
    void setUp() {
        appender = new SystemLogAppender();
        context = new LoggerContext();
    }

    private RuntimeLogEntity capture(String loggerName, Level level, String message, Throwable error) {
        LoggingEvent event = new LoggingEvent(loggerName, context.getLogger(loggerName), level,
            message, error, new Object[0]);
        event.setTimeStamp(System.currentTimeMillis());
        return appender.toEntity(event);
    }

    @Test
    void mapsCoreFields() {
        MDC.put("traceId", "abc123");
        try {
            RuntimeLogEntity e = capture("com.powercms.cms.content.ContentService", Level.ERROR, "发布失败", null);
            assertThat(e.getLevel()).isEqualTo("ERROR");
            assertThat(e.getModule()).isEqualTo("ContentService");
            assertThat(e.getTraceId()).isEqualTo("abc123");
            assertThat(e.getMessage()).isEqualTo("发布失败");
            assertThat(e.getThread()).isNotBlank();
            assertThat(e.isTruncated()).isFalse();
        } finally {
            MDC.remove("traceId");
        }
    }

    @Test
    void exceptionTypeAndStackCaptured() {
        RuntimeLogEntity e = capture("com.powercms.cms.content.ContentService", Level.ERROR, "boom",
            new IllegalStateException("broken"));
        assertThat(e.getExceptionType()).isEqualTo(IllegalStateException.class.getName());
        assertThat(e.getStack()).contains("IllegalStateException").contains("broken");
    }

    @Test
    void overlongMessageTruncatedWithMarker() {
        // FR-LOG-RT-006：超长截断 + truncation 标记
        String big = "x".repeat(10 * 1024);
        RuntimeLogEntity e = capture("test", Level.INFO, big, null);
        assertThat(e.isTruncated()).isTrue();
        assertThat(e.getOriginalBytes()).isEqualTo(10 * 1024);
        assertThat(e.getMessage()).endsWith("...(truncated, origin 10240 bytes)");
        assertThat(e.getMessage().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
            .isLessThanOrEqualTo(SystemLogAppender.MAX_MESSAGE_BYTES + 64);
    }

    @Test
    void stackFramesCappedAt100() {
        RuntimeException deep = deepStack(300);
        RuntimeLogEntity e = capture("test", Level.ERROR, "deep", deep);
        long frames = e.getStack().lines().filter(l -> l.trim().startsWith("at ")).count();
        assertThat(frames).isLessThanOrEqualTo(SystemLogAppender.MAX_STACK_FRAMES);
    }

    @Test
    void messageSanitizedBeforeEnqueue() {
        RuntimeLogEntity e = capture("test", Level.INFO, "login ok password=SuperSecret123", null);
        assertThat(e.getMessage()).doesNotContain("SuperSecret123");
        assertThat(e.getMessage()).contains(LogSanitizer.MASK);
    }

    private static RuntimeException deepStack(int depth) {
        RuntimeException ex = new RuntimeException("root");
        StackTraceElement[] frames = new StackTraceElement[depth];
        for (int i = 0; i < depth; i++) {
            frames[i] = new StackTraceElement("com.powercms.cms.Fake", "method" + i, "Fake.java", i);
        }
        ex.setStackTrace(frames);
        return ex;
    }
}
