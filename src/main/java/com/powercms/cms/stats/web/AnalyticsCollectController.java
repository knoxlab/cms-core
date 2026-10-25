package com.powercms.cms.stats.web;

import com.powercms.cms.stats.AnalyticsCollectService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 匿名采集端点（FR-STAT-002）。挂 /api/v1/delivery/** 以复用 permitAll 与 CORS。
 * 无状态：IP 从 XFF/直连还原（FR-STAT-101），限流由网关边缘层与应用层共享存储承接（FR-STAT-103）。
 */
@RestController
@RequestMapping("/api/v1/delivery/analytics")
public class AnalyticsCollectController {

    private final AnalyticsCollectService collectService;
    private final com.powercms.cms.stats.AnalyticsMemberResolver memberResolver;

    public AnalyticsCollectController(AnalyticsCollectService collectService,
                                      com.powercms.cms.stats.AnalyticsMemberResolver memberResolver) {
        this.collectService = collectService;
        this.memberResolver = memberResolver;
    }

    public record InboundEvent(
        @NotBlank String eventId, String type, Instant occurredAt,
        String sessionId, String visitorId, Instant sessionStartedAt, Integer sessionSeq,
        String routePath, String title, UUID contentId, UUID mediaAssetId,
        String referer, String keyword, Integer hitCount, Integer errorStatus,
        String playSessionId, Long playPositionMs, Long playDeltaMs, Boolean paused,
        Long trafficBytes, String endReason, Long farthestMs, Long videoDurationMs,
        String utmSource, String utmMedium, String utmCampaign,
        String os, String browser, String browserVersion, String deviceType, String language, String screen,
        boolean spaNav, Map<String, Object> attrs) {}

    public record CollectRequest(@NotBlank String siteKey, List<InboundEvent> events) {}

    @PostMapping("/collect")
    public ResponseEntity<?> collect(@RequestBody CollectRequest request, HttpServletRequest http) {
        String origin = http.getHeader("Origin");
        String xff = http.getHeader("X-Forwarded-For");
        String ua = http.getHeader("User-Agent");
        String lang = http.getHeader("Accept-Language");
        var result = collectService.collect(request.siteKey(), origin, xff,
            http.getRemoteAddr(), ua, lang, convert(request.events()),
            memberResolver.currentMemberId(http));
        if (result.accepted() == 0 && result.rejected() > 0) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(result);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 采集脚本分发（FR-STAT-001）：同源匿名加载。内容为 classpath 第一方脚本，
     * 页面注入时的 siteKey/endpoint 经 window.powercmsAnalyticsConfig 传入，脚本自身无需参数。
     */
    @GetMapping(value = "/script.js", produces = "application/javascript;charset=UTF-8")
    public ResponseEntity<String> script() {
        try (var in = new ClassPathResource("static/analytics.js").getInputStream()) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                .header("Cache-Control", "public, max-age=300")
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Content-Type-Options", "nosniff")
                .body("/* analytics.js unavailable */");
        }
    }

    private List<AnalyticsCollectService.InboundEvent> convert(List<InboundEvent> events) {
        if (events == null) return List.of();
        return events.stream()
            .map(e -> new AnalyticsCollectService.InboundEvent(
                e.eventId(), e.type(), e.occurredAt(), e.sessionId(), e.visitorId(),
                e.sessionStartedAt(), e.sessionSeq(), e.routePath(), e.title(), e.contentId(), e.mediaAssetId(),
                e.referer(), e.keyword(), e.hitCount(), e.errorStatus(),
                e.playSessionId(), e.playPositionMs(), e.playDeltaMs(), e.paused(),
                e.trafficBytes(), e.endReason(), e.farthestMs(), e.videoDurationMs(),
                e.utmSource(), e.utmMedium(), e.utmCampaign(),
                e.os(), e.browser(), e.browserVersion(), e.deviceType(), e.language(), e.screen(),
                e.spaNav(), e.attrs()))
            .toList();
    }
}
