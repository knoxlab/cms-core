package com.powercms.cms.systemlog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 系统运行日志条目（FR-LOG-RT-002，结构化 JSON 落库口径，lowerCamelCase 列语义）。
 * 时间戳 UTC 存储（Instant）；traceId 取自 {@code MDC["traceId"]}（可空）；
 * siteKey 由采集侧按当前请求/任务上下文填充（可空 = 平台级条目，仅 ORG 可见域可见）。
 */
@Entity
@Table(name = "runtime_log")
public class RuntimeLogEntity {

    /** 六级体系（FR-LOG-RT-003）。 */
    public static final String LEVEL_TRACE = "TRACE";
    public static final String LEVEL_DEBUG = "DEBUG";
    public static final String LEVEL_INFO = "INFO";
    public static final String LEVEL_WARN = "WARN";
    public static final String LEVEL_ERROR = "ERROR";
    public static final String LEVEL_FATAL = "FATAL";

    @Id
    private UUID id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(nullable = false, length = 8)
    private String level;

    @Column(length = 64)
    private String service;

    @Column(length = 128)
    private String module;

    @Column(length = 64)
    private String node;

    @Column(length = 128)
    private String thread;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "site_key", length = 64)
    private String siteKey;

    @Column(nullable = false, columnDefinition = "text")
    private String message;

    @Column(name = "exception_type", length = 256)
    private String exceptionType;

    @Column(columnDefinition = "text")
    private String stack;

    /** 超长截断标记（FR-LOG-RT-006），前端据此展示 truncation 提示。 */
    @Column(nullable = false)
    private boolean truncated = false;

    /** 截断前原始字节数（truncated=true 时有值）。 */
    @Column(name = "original_bytes")
    private Long originalBytes;

    public static RuntimeLogEntity create(Instant occurredAt, String level, String service, String module,
                                          String node, String thread, String traceId, String siteKey,
                                          String message, String exceptionType, String stack,
                                          boolean truncated, Long originalBytes) {
        RuntimeLogEntity e = new RuntimeLogEntity();
        e.id = UUID.randomUUID();
        e.occurredAt = occurredAt;
        e.level = level;
        e.service = service;
        e.module = module;
        e.node = node;
        e.thread = thread;
        e.traceId = traceId;
        e.siteKey = siteKey;
        e.message = message;
        e.exceptionType = exceptionType;
        e.stack = stack;
        e.truncated = truncated;
        e.originalBytes = originalBytes;
        return e;
    }

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (occurredAt == null) occurredAt = Instant.now();
    }

    public UUID getId() { return id; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getLevel() { return level; }
    public String getService() { return service; }
    public String getModule() { return module; }
    public String getNode() { return node; }
    public String getThread() { return thread; }
    public String getTraceId() { return traceId; }
    public String getSiteKey() { return siteKey; }
    public String getMessage() { return message; }
    public String getExceptionType() { return exceptionType; }
    public String getStack() { return stack; }
    public boolean isTruncated() { return truncated; }
    public Long getOriginalBytes() { return originalBytes; }
}
