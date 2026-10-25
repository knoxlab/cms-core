package com.powercms.cms.stats.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 播放会话（FR-STAT-034~036）：一次连续视频观看过程，由 play_session_id 串联。
 * watched_ms 累加必须走数据库侧原子更新（FR-STAT-102），禁止读-改-写。
 */
@Entity
@Table(name = "analytics_play_session")
public class AnalyticsPlaySessionEntity {

    public static final String REASON_COMPLETED = "COMPLETED";
    public static final String REASON_EXIT = "EXIT";
    public static final String REASON_CLOSED = "CLOSED";
    public static final String REASON_ERROR = "ERROR";
    public static final String REASON_TIMEOUT = "TIMEOUT";

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    @Column(name = "content_id")
    private UUID contentId;

    @Column(name = "content_type_id")
    private UUID contentTypeId;

    @Column(name = "media_asset_id")
    private UUID mediaAssetId;

    @Column(name = "visitor_id", nullable = false, length = 64)
    private String visitorId;

    @Column(name = "member_id")
    private UUID memberId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    /** 最后一次心跳时间：超时收口（NFR-STAT-006）据此判断"是否还在播"，
     *  而不是按起播时间一刀切——否则长视频播放中会被提前 TIMEOUT，END 写不进结束原因。 */
    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "end_reason", length = 20)
    private String endReason;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "watched_ms", nullable = false)
    private long watchedMs;

    @Column(name = "farthest_ms", nullable = false)
    private long farthestMs;

    @Column(name = "resume_ms", nullable = false)
    private long resumeMs;

    @Column(name = "traffic_bytes", nullable = false)
    private long trafficBytes;

    @Column(name = "referrer_path", length = 1000)
    private String referrerPath;

    @Column(name = "device_type", length = 30)
    private String deviceType;

    public String getId() { return id; }
    public UUID getSiteId() { return siteId; }
    public void setSiteId(UUID v) { this.siteId = v; }
    public UUID getContentId() { return contentId; }
    public void setContentId(UUID v) { this.contentId = v; }
    public UUID getContentTypeId() { return contentTypeId; }
    public void setContentTypeId(UUID v) { this.contentTypeId = v; }
    public UUID getMediaAssetId() { return mediaAssetId; }
    public void setMediaAssetId(UUID v) { this.mediaAssetId = v; }
    public String getVisitorId() { return visitorId; }
    public void setVisitorId(String v) { this.visitorId = v; }
    public UUID getMemberId() { return memberId; }
    public void setMemberId(UUID v) { this.memberId = v; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant v) { this.startedAt = v; }
    public Instant getLastHeartbeatAt() { return lastHeartbeatAt; }
    public void setLastHeartbeatAt(Instant v) { this.lastHeartbeatAt = v; }
    public void setId(String v) { this.id = v; }
    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant v) { this.endedAt = v; }
    public String getEndReason() { return endReason; }
    public void setEndReason(String v) { this.endReason = v; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long v) { this.durationMs = v; }
    public long getWatchedMs() { return watchedMs; }
    public void setWatchedMs(long v) { this.watchedMs = v; }
    public long getFarthestMs() { return farthestMs; }
    public void setFarthestMs(long v) { this.farthestMs = v; }
    public long getResumeMs() { return resumeMs; }
    public void setResumeMs(long v) { this.resumeMs = v; }
    public long getTrafficBytes() { return trafficBytes; }
    public void setTrafficBytes(long v) { this.trafficBytes = v; }
    public String getReferrerPath() { return referrerPath; }
    public void setReferrerPath(String v) { this.referrerPath = v; }
    public String getDeviceType() { return deviceType; }
    public void setDeviceType(String v) { this.deviceType = v; }
}
