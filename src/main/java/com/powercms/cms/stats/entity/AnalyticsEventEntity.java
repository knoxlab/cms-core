package com.powercms.cms.stats.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 访问事件明细（FR-STAT-001/002）。id 即 event_id 幂等键。
 * 保留期默认 90 天（FR-STAT-006），生产 PG 按月分区由运维脚本落地（NFR-STAT-008）。
 */
@Entity
@Table(name = "analytics_event")
public class AnalyticsEventEntity {

    @Id
    private UUID id;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "visitor_id", nullable = false, length = 64)
    private String visitorId;

    @Column(name = "session_started_at")
    private Instant sessionStartedAt;

    @Column(name = "session_seq")
    private Integer sessionSeq;

    @Column(name = "member_id")
    private UUID memberId;

    @Column(name = "ip_hash", length = 64)
    private String ipHash;

    @Column(name = "ip_masked", length = 45)
    private String ipMasked;

    @Column(name = "region_province", length = 50)
    private String regionProvince;

    @Column(name = "region_city", length = 50)
    private String regionCity;

    @Column(name = "server_node", length = 100)
    private String serverNode;

    @Column(name = "route_path", length = 1000)
    private String routePath;

    @Column(name = "title", length = 500)
    private String title;

    @Column(name = "content_id")
    private UUID contentId;

    @Column(name = "content_type_id")
    private UUID contentTypeId;

    @Column(name = "media_asset_id")
    private UUID mediaAssetId;

    @Column(name = "folder_id")
    private UUID folderId;

    @Column(name = "referer_host", length = 200)
    private String refererHost;

    @Column(name = "source_type", length = 30)
    private String sourceType;

    @Column(name = "search_keyword", length = 200)
    private String searchKeyword;

    @Column(name = "utm_source", length = 200)
    private String utmSource;

    @Column(name = "utm_medium", length = 200)
    private String utmMedium;

    @Column(name = "utm_campaign", length = 200)
    private String utmCampaign;

    @Column(name = "os", length = 50)
    private String os;

    @Column(name = "browser", length = 50)
    private String browser;

    @Column(name = "browser_version", length = 50)
    private String browserVersion;

    @Column(name = "device_type", length = 30)
    private String deviceType;

    @Column(name = "language", length = 30)
    private String language;

    @Column(name = "screen", length = 20)
    private String screen;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "is_bot", nullable = false)
    private boolean bot;

    @Column(name = "is_spa_nav", nullable = false)
    private boolean spaNav;

    @Column(name = "play_session_id", length = 64)
    private String playSessionId;

    @Column(name = "play_position_ms")
    private Long playPositionMs;

    @Column(name = "play_delta_ms")
    private Long playDeltaMs;

    @Column(name = "play_buffer")
    private Boolean playBuffer;

    @Column(name = "traffic_bytes")
    private Long trafficBytes;

    @Column(name = "end_reason", length = 20)
    private String endReason;

    @Column(name = "farthest_ms")
    private Long farthestMs;

    @Column(name = "error_status")
    private Integer errorStatus;

    @Column(name = "attrs", columnDefinition = "text")
    private String attrs;

    public static AnalyticsEventEntity create(UUID id, UUID siteId, String eventType, Instant occurredAt,
                                              String sessionId, String visitorId) {
        AnalyticsEventEntity e = new AnalyticsEventEntity();
        e.id = id;
        e.siteId = siteId;
        e.eventType = eventType;
        e.occurredAt = occurredAt;
        e.sessionId = sessionId;
        e.visitorId = visitorId;
        return e;
    }

    public UUID getId() { return id; }
    public UUID getSiteId() { return siteId; }
    public String getEventType() { return eventType; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getSessionId() { return sessionId; }
    public String getVisitorId() { return visitorId; }
    public Instant getSessionStartedAt() { return sessionStartedAt; }
    public void setSessionStartedAt(Instant v) { this.sessionStartedAt = v; }
    public Integer getSessionSeq() { return sessionSeq; }
    public void setSessionSeq(Integer v) { this.sessionSeq = v; }
    public UUID getMemberId() { return memberId; }
    public void setMemberId(UUID v) { this.memberId = v; }
    public String getIpHash() { return ipHash; }
    public void setIpHash(String v) { this.ipHash = v; }
    public String getIpMasked() { return ipMasked; }
    public void setIpMasked(String v) { this.ipMasked = v; }
    public String getRegionProvince() { return regionProvince; }
    public void setRegionProvince(String v) { this.regionProvince = v; }
    public String getRegionCity() { return regionCity; }
    public void setRegionCity(String v) { this.regionCity = v; }
    public String getServerNode() { return serverNode; }
    public void setServerNode(String v) { this.serverNode = v; }
    public String getRoutePath() { return routePath; }
    public void setRoutePath(String v) { this.routePath = v; }
    public String getTitle() { return title; }
    public void setTitle(String v) { this.title = v; }
    public UUID getContentId() { return contentId; }
    public void setContentId(UUID v) { this.contentId = v; }
    public UUID getContentTypeId() { return contentTypeId; }
    public void setContentTypeId(UUID v) { this.contentTypeId = v; }
    public UUID getMediaAssetId() { return mediaAssetId; }
    public void setMediaAssetId(UUID v) { this.mediaAssetId = v; }
    public UUID getFolderId() { return folderId; }
    public void setFolderId(UUID v) { this.folderId = v; }
    public String getRefererHost() { return refererHost; }
    public void setRefererHost(String v) { this.refererHost = v; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String v) { this.sourceType = v; }
    public String getSearchKeyword() { return searchKeyword; }
    public void setSearchKeyword(String v) { this.searchKeyword = v; }
    public String getUtmSource() { return utmSource; }
    public void setUtmSource(String v) { this.utmSource = v; }
    public String getUtmMedium() { return utmMedium; }
    public void setUtmMedium(String v) { this.utmMedium = v; }
    public String getUtmCampaign() { return utmCampaign; }
    public void setUtmCampaign(String v) { this.utmCampaign = v; }
    public String getOs() { return os; }
    public void setOs(String v) { this.os = v; }
    public String getBrowser() { return browser; }
    public void setBrowser(String v) { this.browser = v; }
    public String getBrowserVersion() { return browserVersion; }
    public void setBrowserVersion(String v) { this.browserVersion = v; }
    public String getDeviceType() { return deviceType; }
    public void setDeviceType(String v) { this.deviceType = v; }
    public String getLanguage() { return language; }
    public void setLanguage(String v) { this.language = v; }
    public String getScreen() { return screen; }
    public void setScreen(String v) { this.screen = v; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long v) { this.durationMs = v; }
    public boolean isBot() { return bot; }
    public void setBot(boolean v) { this.bot = v; }
    public boolean isSpaNav() { return spaNav; }
    public void setSpaNav(boolean v) { this.spaNav = v; }
    public String getPlaySessionId() { return playSessionId; }
    public void setPlaySessionId(String v) { this.playSessionId = v; }
    public Long getPlayPositionMs() { return playPositionMs; }
    public void setPlayPositionMs(Long v) { this.playPositionMs = v; }
    public Long getPlayDeltaMs() { return playDeltaMs; }
    public void setPlayDeltaMs(Long v) { this.playDeltaMs = v; }
    public Boolean getPlayBuffer() { return playBuffer; }
    public void setPlayBuffer(Boolean v) { this.playBuffer = v; }
    public Long getTrafficBytes() { return trafficBytes; }
    public void setTrafficBytes(Long v) { this.trafficBytes = v; }
    public String getEndReason() { return endReason; }
    public void setEndReason(String v) { this.endReason = v; }
    public Long getFarthestMs() { return farthestMs; }
    public void setFarthestMs(Long v) { this.farthestMs = v; }
    public Integer getErrorStatus() { return errorStatus; }
    public void setErrorStatus(Integer v) { this.errorStatus = v; }
    public String getAttrs() { return attrs; }
    public void setAttrs(String v) { this.attrs = v; }
}
