package com.powercms.cms.stats;

import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.site.entity.SiteEntity;
import com.powercms.cms.site.repo.SiteRepository;
import com.powercms.cms.stats.entity.AnalyticsEventEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.powercms.cms.stats.entity.AnalyticsEventEntity;

/**
 * 事件接入服务（FR-STAT-002/003/101）：siteKey/Origin 校验、白名单、enrichment、入队。
 * 无状态：全部判定依据请求头与共享库站点配置，多节点行为一致（FR-STAT-100/104）。
 */
@Service
public class AnalyticsCollectService {

    private static final int MAX_BATCH = 50;
    private static final int MAX_EVENT_BYTES = 8 * 1024;
    private static final Pattern KEYWORD_SAFE = Pattern.compile("[\\p{L}\\p{N} _\\-]{1,100}");

    private final SiteRepository siteRepo;
    private final AnalyticsIngestPipeline pipeline;
    private final AnalyticsPlaybackService playbackService;
    private final AnalyticsMemberResolver memberResolver;
    private final com.powercms.cms.content.repo.ContentItemRepository contentItemRepo;
    private final String serverNode;
    private final int trustedProxies;

    public AnalyticsCollectService(SiteRepository siteRepo,
                                   AnalyticsIngestPipeline pipeline,
                                   AnalyticsPlaybackService playbackService,
                                   AnalyticsMemberResolver memberResolver,
                                   com.powercms.cms.content.repo.ContentItemRepository contentItemRepo,
                                   @Value("${powercms.analytics.server-node:}") String serverNode,
                                   @Value("${powercms.analytics.trusted-proxies:0}") int trustedProxies) {
        this.siteRepo = siteRepo;
        this.pipeline = pipeline;
        this.playbackService = playbackService;
        this.memberResolver = memberResolver;
        this.contentItemRepo = contentItemRepo;
        this.serverNode = serverNode == null || serverNode.isBlank() ? defaultNodeName() : serverNode;
        this.trustedProxies = Math.max(0, trustedProxies);
    }

    private static String defaultNodeName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "node-" + ProcessHandle.current().pid();
        }
    }

    public record InboundEvent(
        String eventId, String type, Instant occurredAt,
        String sessionId, String visitorId, Instant sessionStartedAt, Integer sessionSeq,
        String routePath, String title, UUID contentId, UUID mediaAssetId,
        String referer, String keyword, Integer hitCount, Integer errorStatus,
        String playSessionId, Long playPositionMs, Long playDeltaMs, Boolean paused,
        Long trafficBytes, String endReason, Long farthestMs, Long videoDurationMs,
        String utmSource, String utmMedium, String utmCampaign,
        String os, String browser, String browserVersion, String deviceType, String language, String screen,
        boolean spaNav, Map<String, Object> attrs) {}

    public record CollectResult(int accepted, int rejected, List<String> errors) {}

    /**
     * 受理一批事件。站点冻结返回 423（FR-STAT-095）；非法事件逐条拒绝不中断整批。
     */
    public CollectResult collect(String siteKey, String origin, String xForwardedFor, String remoteAddr,
                                 String userAgent, String acceptLanguage, List<InboundEvent> events,
                                 java.util.UUID memberId) {
        SiteEntity site = siteRepo.findBySiteKey(siteKey).orElse(null);
        if (site == null) throw new com.powercms.common.error.BusinessException(
            com.powercms.common.error.ErrorCodes.UNAUTHENTICATED, "siteKey 无效");
        if (com.powercms.cms.site.entity.SiteEntity.STATUS_FROZEN.equals(site.getStatus())) {
            throw new com.powercms.common.error.BusinessException(
                com.powercms.common.error.ErrorCodes.SITE_FROZEN, "站点已冻结，采集暂停");
        }
        if (!originAllowed(origin, site.getSiteKey())) {
            throw new com.powercms.common.error.BusinessException(
                com.powercms.common.error.ErrorCodes.FORBIDDEN, "Origin 不在白名单");
        }
        if (events == null || events.isEmpty()) {
            return new CollectResult(0, 0, List.of());
        }
        if (events.size() > MAX_BATCH) {
            throw new com.powercms.common.error.BusinessException(
                com.powercms.common.error.ErrorCodes.BAD_REQUEST, "单批事件数超过上限 " + MAX_BATCH);
        }
        boolean bot = ClientFingerprint.isBot(userAgent);
        String clientIp = ClientFingerprint.resolveClientIp(xForwardedFor, remoteAddr, trustedProxies);
        String ipHash = bot ? null : ClientFingerprint.hashIp(clientIp, siteSalt(site));
        String ipMasked = bot ? null : ClientFingerprint.maskIp(clientIp);
        String siteSalt = siteSalt(site);
        // 会员身份服务端解析注入（FR-STAT-037），随事件落库，供播放会话与学习进度归因
        java.util.UUID resolvedMemberId = bot ? null : memberId;

        int accepted = 0;
        int rejected = 0;
        List<String> errors = new ArrayList<>();
        for (InboundEvent event : events) {
            try {
                AnalyticsEventEntity entity = enrich(event, site, bot, ipHash, ipMasked, siteSalt, userAgent, acceptLanguage, resolvedMemberId);
                if (entity != null) {
                    pipeline.offer(entity);
                    accepted++;
                } else {
                    rejected++;
                }
            } catch (IllegalArgumentException e) {
                rejected++;
                errors.add(e.getMessage());
            }
        }
        return new CollectResult(accepted, rejected, errors);
    }

    private String siteSalt(SiteEntity site) {
        return "site-salt:" + site.getId();
    }

    private boolean originAllowed(String origin, String siteKey) {
        if (origin == null || origin.isBlank()) return true;
        try {
            String host = java.net.URI.create(origin).getHost();
            return host != null && !host.isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * enrichment（FR-STAT-003）：UA/语言/来源归类/栏目归属/会话合法性校验。
     * 返回 null 表示事件被静默拒绝（爬虫或非法），抛出 IllegalArgumentException 表示请求错误。
     */
    private AnalyticsEventEntity enrich(InboundEvent e, SiteEntity site, boolean bot,
                                        String ipHash, String ipMasked, String siteSalt,
                                        String userAgent, String acceptLanguage, UUID memberId) {
        if (e.eventId() == null || e.type() == null) {
            throw new IllegalArgumentException("eventId 与 type 必填");
        }
        UUID eventId;
        try {
            eventId = UUID.fromString(e.eventId());
        } catch (Exception ex) {
            throw new IllegalArgumentException("eventId 格式非法: " + e.eventId());
        }
        if (!EventTypes.ALL.contains(e.type())) {
            throw new IllegalArgumentException("未知事件类型: " + e.type());
        }
        if (bot) return null;
        if (e.sessionId() == null || e.sessionId().isBlank() || e.visitorId() == null || e.visitorId().isBlank()) {
            throw new IllegalArgumentException("sessionId 与 visitorId 必填");
        }
        if (e.contentId() == null && e.mediaAssetId() == null && EventTypes.isVideo(e.type())) {
            throw new IllegalArgumentException("视频事件必须携带 contentId 或 mediaAssetId");
        }
        String keyword = e.keyword();
        if (keyword != null && !KEYWORD_SAFE.matcher(keyword).matches()) {
            keyword = null;
        }

        Instant occurredAt = e.occurredAt() == null ? Instant.now() : e.occurredAt();
        AnalyticsEventEntity entity = AnalyticsEventEntity.create(
            eventId, site.getId(), e.type(), occurredAt, trim(e.sessionId(), 64), trim(e.visitorId(), 64));
        entity.setMemberId(memberId);
        entity.setSessionStartedAt(e.sessionStartedAt());
        entity.setSessionSeq(e.sessionSeq());
        entity.setIpHash(ipHash);
        entity.setIpMasked(ipMasked);
        entity.setServerNode(serverNode);
        entity.setRoutePath(trim(e.routePath(), 1000));
        entity.setTitle(trim(e.title(), 500));
        entity.setContentId(e.contentId());
        entity.setMediaAssetId(e.mediaAssetId());
        entity.setContentTypeId(resolveContentType(site.getId(), e.contentId()));
        entity.setSearchKeyword(keyword);
        entity.setUtmSource(trim(e.utmSource(), 200));
        entity.setUtmMedium(trim(e.utmMedium(), 200));
        entity.setUtmCampaign(trim(e.utmCampaign(), 200));
        entity.setSourceType(classifySource(e.referer(), e.utmSource()));
        entity.setPlaySessionId(trim(e.playSessionId(), 64));
        entity.setPlayPositionMs(e.playPositionMs());
        entity.setPlayDeltaMs(e.playDeltaMs());
        entity.setPlayBuffer(e.paused());
        entity.setTrafficBytes(e.trafficBytes());
        entity.setEndReason(trim(e.endReason(), 20));
        entity.setFarthestMs(e.farthestMs());
        entity.setErrorStatus(e.errorStatus());
        entity.setSpaNav(e.spaNav());
        entity.setDurationMs(e.videoDurationMs());
        entity.setBot(false);

        ClientFingerprint.UaInfo ua = ClientFingerprint.parseUserAgent(userAgent);
        entity.setOs(ua.os());
        entity.setBrowser(ua.browser());
        entity.setBrowserVersion(ua.browserVersion());
        entity.setDeviceType(ua.deviceType());
        String lang = acceptLanguage == null ? null : acceptLanguage.split(",")[0].trim();
        entity.setLanguage(lang == null || lang.isBlank() ? "未知" : trim(lang, 30));
        if (ipHash != null) {
            entity.setRegionProvince("未知地区");
        }
        return entity;
    }

    private UUID resolveContentType(UUID siteId, UUID contentId) {
        if (contentId == null) return null;
        // 缓存带 TTL（30 秒，FR-STAT-104 配置收敛口径）：命中直接返回；未命中查库并回填，查不到缓存空映射避免穿透
        long now = System.currentTimeMillis();
        var cached = contentTypes.get(new SiteKey(siteId));
        if (cached != null && now - cached.loadAt < CACHE_TTL_MS) {
            return cached.map.get(contentId);
        }
        Map<UUID, UUID> loaded = new java.util.HashMap<>();
        for (ContentItemEntity item : contentItemRepo.findBySiteId(siteId)) {
            loaded.put(item.getId(), item.getContentTypeId());
        }
        contentTypes.put(new SiteKey(siteId), new CachedTypeMap(loaded, now));
        return loaded.get(contentId);
    }

    private record SiteKey(UUID siteId) {}

    private record CachedTypeMap(Map<UUID, UUID> map, long loadAt) {}

    private static final long CACHE_TTL_MS = 30_000;
    private static final java.util.concurrent.ConcurrentHashMap<SiteKey, CachedTypeMap> contentTypes =
        new java.util.concurrent.ConcurrentHashMap<>();

    private static String classifySource(String referer, String utmSource) {
        if (utmSource != null && !utmSource.isBlank()) return "CAMPAIGN";
        if (referer == null || referer.isBlank()) return "DIRECT";
        try {
            java.net.URI uri = java.net.URI.create(referer);
            String host = uri.getHost();
            if (host == null) return "REFERRAL";
            Set<String> engines = Set.of("google.", "bing.com", "baidu.com", "sogou.com", "so.com", "duckduckgo.com", "yandex.");
            for (String engine : engines) {
                if (host.contains(engine)) return "SEARCH_ENGINE";
            }
            return "REFERRAL";
        } catch (Exception e) {
            return "REFERRAL";
        }
    }

    private static String trim(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
