package com.powercms.cms.publish.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 发布监控策略（FR-AI-AGENT-004）：站点级异常自动回滚配置。默认关闭，显式开启后才参与判定。
 */
@Entity
@Table(name = "release_monitor_policy")
public class ReleaseMonitorPolicyEntity {

    public static final String ACTION_AUTO_ROLLBACK = "AUTO_ROLLBACK";
    public static final String ACTION_NOTIFY_ONLY = "NOTIFY_ONLY";

    @Id
    @Column(name = "site_id")
    private UUID siteId;

    @Column(nullable = false)
    private boolean enabled = false;

    @Column(name = "window_minutes", nullable = false)
    private int windowMinutes = 60;

    @Column(name = "failure_threshold", nullable = false)
    private int failureThreshold = 1;

    @Column(nullable = false, length = 30)
    private String action = ACTION_AUTO_ROLLBACK;

    /**
     * 窗口内失败率阈值（G9-④）：失败部署 / 窗口内部署总数 ≥ 阈值即判定异常，与计数阈值「或」关系；
     * {@code null} 表示关闭（等价于仅用计数阈值的旧行为）。
     */
    @Column(name = "error_rate_threshold")
    private java.math.BigDecimal errorRateThreshold;

    /**
     * 健康劣化持续时长（分钟，G9-④）：0 表示立即动作（既有行为）；>0 则先落「持续观测中」事件，
     * 达到时长仍未恢复才通知/自动回退，观测期内恢复则关单。
     */
    @Column(name = "degraded_duration_minutes", nullable = false)
    private int degradedDurationMinutes = 0;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (updatedAt == null) updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getSiteId() { return siteId; }
    public void setSiteId(UUID siteId) { this.siteId = siteId; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getWindowMinutes() { return windowMinutes; }
    public void setWindowMinutes(int windowMinutes) { this.windowMinutes = windowMinutes; }
    public int getFailureThreshold() { return failureThreshold; }
    public void setFailureThreshold(int failureThreshold) { this.failureThreshold = failureThreshold; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public java.math.BigDecimal getErrorRateThreshold() { return errorRateThreshold; }
    public void setErrorRateThreshold(java.math.BigDecimal errorRateThreshold) {
        this.errorRateThreshold = errorRateThreshold;
    }
    public int getDegradedDurationMinutes() { return degradedDurationMinutes; }
    public void setDegradedDurationMinutes(int degradedDurationMinutes) {
        this.degradedDurationMinutes = degradedDurationMinutes;
    }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
}
