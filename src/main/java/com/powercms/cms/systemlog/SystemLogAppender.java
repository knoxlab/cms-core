package com.powercms.cms.systemlog;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import com.powercms.cms.systemlog.entity.RuntimeLogEntity;
import org.slf4j.MDC;

import java.net.InetAddress;
import java.time.Instant;

/**
 * 运行日志自定义 Appender（FR-LOG-RT-001/002/005/006）：
 * Logback 事件 → 字段映射 → 脱敏 → 入 {@link SystemLogPipeline}（异步、不阻塞业务线程）。
 * 通过 Spring 桥接器 {@link SystemLogPipelineAttacher} 注入管道引用；管道未就绪时直接跳过
 * （启动早期日志只走文件通道，不得因采集失败影响启动）。
 */
public class SystemLogAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    /** 消息正文上限（FR-LOG-RT-006：8 KB），超长截断并标记。 */
    static final int MAX_MESSAGE_BYTES = 8 * 1024;
    /** 异常堆栈最大帧数（FR-LOG-RT-006：100 帧）。 */
    static final int MAX_STACK_FRAMES = 100;

    private static final String ANONYMOUS_LOGGER_ROOT = "com.powercms";

    private volatile SystemLogPipeline pipeline;
    private volatile String node = null;
    private volatile String service = "powercms-core";

    /** 由 Spring 容器就绪后回填（避免 Appender 构造期依赖注入）。 */
    public void attach(SystemLogPipeline pipeline) {
        this.pipeline = pipeline;
    }

    public void setService(String service) {
        if (service != null && !service.isBlank()) this.service = service;
    }

    @Override
    protected void append(ILoggingEvent event) {
        SystemLogPipeline target = pipeline;
        if (target == null) return;
        try {
            target.submit(toEntity(event));
        } catch (RuntimeException ignored) {
            // 采集为观测旁路（约束 4）：任何异常吞掉，绝不影响业务
        }
    }

    RuntimeLogEntity toEntity(ILoggingEvent event) {
        // 写入侧脱敏（FR-LOG-PM-004）：先脱敏再计量/截断，originalBytes = 脱敏后截断前字节数
        String raw = LogSanitizer.sanitize(event.getFormattedMessage());
        int rawBytes = safeBytes(raw);
        boolean truncated = rawBytes > MAX_MESSAGE_BYTES;
        String message = truncate(raw, MAX_MESSAGE_BYTES);
        String level = event.getLevel().toString();
        // FATAL 在 Logback/SLF4J 中以 ERROR 落地，无独立级别；此处保留六级兼容
        String module = shortName(event.getLoggerName());
        IThrowableProxy throwable = event.getThrowableProxy();
        String exceptionType = null;
        String stack = null;
        if (throwable != null) {
            exceptionType = throwable.getClassName();
            stack = buildStack(throwable);
        }
        return RuntimeLogEntity.create(
            Instant.ofEpochMilli(event.getTimeStamp()),
            level,
            service,
            module,
            node(),
            event.getThreadName(),
            MDC.get(TraceIdMdc.MDC_KEY),
            null, // siteKey 由 MDC 上下文（请求域/任务域）填充；平台组件为空
            message,
            exceptionType,
            stack,
            truncated,
            (long) rawBytes);
    }

    private String node() {
        String n = node;
        if (n != null) return n;
        try {
            n = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            n = "unknown";
        }
        node = n;
        return n;
    }

    private static String shortName(String loggerName) {
        if (loggerName == null) return "";
        int dot = loggerName.lastIndexOf('.');
        return dot >= 0 ? loggerName.substring(dot + 1) : loggerName;
    }

    private static int safeBytes(String text) {
        if (text == null) return 0;
        return text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    /** 截断 + 标记（FR-LOG-RT-006）：...(truncated, origin N bytes)。 */
    static String truncate(String text, int maxBytes) {
        if (text == null) return null;
        int bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (bytes <= maxBytes) return text;
        StringBuilder out = new StringBuilder();
        int used = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int size = Character.charCount(cp);
            int cpBytes = String.valueOf(Character.toChars(cp)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (used + cpBytes > maxBytes) break;
            out.appendCodePoint(cp);
            used += cpBytes;
            i += size;
        }
        out.append("...(truncated, origin ").append(bytes).append(" bytes)");
        return out.toString();
    }

    private static String buildStack(IThrowableProxy throwable) {
        StringBuilder sb = new StringBuilder();
        IThrowableProxy current = throwable;
        int depth = 0;
        while (current != null && depth < 4) {
            if (depth > 0) sb.append("Caused by: ");
            sb.append(current.getClassName()).append(": ")
                .append(current.getMessage() == null ? "" : current.getMessage()).append('\n');
            StackTraceElementProxy[] frames = current.getStackTraceElementProxyArray();
            int common = current == throwable ? 0
                : (frames == null ? 0 : countCommonFrames(current));
            int limit = frames == null ? 0 : Math.min(frames.length, MAX_STACK_FRAMES);
            for (int i = 0; i < limit; i++) {
                sb.append("\tat ").append(frames[i].getStackTraceElement()).append('\n');
            }
            int omitted = (frames == null ? 0 : frames.length) - limit + common;
            if (omitted > 0) sb.append("\t... ").append(omitted).append(" more\n");
            current = current.getCause();
            depth++;
        }
        return sb.toString().trim();
    }

    private static int countCommonFrames(IThrowableProxy cause) {
        return cause.getCommonFrames();
    }

    /** MDC 键唯一定义处（与 TraceIdFilter 对齐，避免字符串漂移）。 */
    static final class TraceIdMdc {
        static final String MDC_KEY = "traceId";
        private TraceIdMdc() {}
    }
}
