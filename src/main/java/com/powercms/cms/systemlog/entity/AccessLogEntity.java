package com.powercms.cms.systemlog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 系统访问日志条目（FR-LOG-AC-002，传输层技术维度记录，与 audit_event 的语义审计分工见 §3.3）。
 * 不记录请求体/响应体（FR-LOG-AC-003）；业务失败仅记错误码与摘要（{@code errorCode}）。
 * outcome 结果分类（FR-LOG-AC-002）：SUCCESS / BUSINESS_FAILURE / AUTH_FAILURE / RATE_LIMITED / SYSTEM_ERROR。
 */
@Entity
@Table(name = "access_log")
public class AccessLogEntity {

    public static final String OUTCOME_SUCCESS = "SUCCESS";
    public static final String OUTCOME_BUSINESS_FAILURE = "BUSINESS_FAILURE";
    public static final String OUTCOME_AUTH_FAILURE = "AUTH_FAILURE";
    public static final String OUTCOME_RATE_LIMITED = "RATE_LIMITED";
    public static final String OUTCOME_SYSTEM_ERROR = "SYSTEM_ERROR";

    @Id
    private UUID id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Column(nullable = false, length = 8)
    private String method;

    @Column(nullable = false, length = 512)
    private String path;

    @Column(nullable = false)
    private short status;

    @Column(name = "duration_ms", nullable = false)
    private int durationMs;

    @Column(name = "resp_bytes")
    private Long respBytes;

    /** 认证身份：登录用户名 / API 客户端 ID / anonymous（FR-LOG-AC-002）。 */
    @Column(length = 128)
    private String identity;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "site_key", length = 64)
    private String siteKey;

    @Column(nullable = false, length = 16)
    private String outcome;

    /** 业务失败时的错误码摘要（不含正文，FR-LOG-AC-003）。 */
    @Column(name = "error_code", length = 100)
    private String errorCode;

    public static AccessLogEntity create(Instant occurredAt, String clientIp, String method, String path,
                                         short status, int durationMs, Long respBytes, String identity,
                                         String userAgent, String traceId, String siteKey,
                                         String outcome, String errorCode) {
        AccessLogEntity e = new AccessLogEntity();
        e.id = UUID.randomUUID();
        e.occurredAt = occurredAt;
        e.clientIp = clientIp;
        e.method = method;
        e.path = path;
        e.status = status;
        e.durationMs = durationMs;
        e.respBytes = respBytes;
        e.identity = identity;
        e.userAgent = userAgent;
        e.traceId = traceId;
        e.siteKey = siteKey;
        e.outcome = outcome;
        e.errorCode = errorCode;
        return e;
    }

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (occurredAt == null) occurredAt = Instant.now();
    }

    public UUID getId() { return id; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getClientIp() { return clientIp; }
    public String getMethod() { return method; }
    public String getPath() { return path; }
    public short getStatus() { return status; }
    public int getDurationMs() { return durationMs; }
    public Long getRespBytes() { return respBytes; }
    public String getIdentity() { return identity; }
    public String getUserAgent() { return userAgent; }
    public String getTraceId() { return traceId; }
    public String getSiteKey() { return siteKey; }
    public String getOutcome() { return outcome; }
    public String getErrorCode() { return errorCode; }
}
