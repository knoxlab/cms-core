package com.powercms.cms.stats.web;

import com.powercms.cms.identity.repo.UserRepository;
import com.powercms.cms.stats.repo.MemberProgressRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 前台会员学习进度查询（FR-STAT-038）：仅能查询本人进度（会员会话鉴权），
 * 未登录返回 401；返回断点位置、最远位置、累计时长、完成度与最近观看时间。
 * 站点归属取会员 home_site（会员会话即站点成员）。
 */
@RestController
@RequestMapping("/api/v1/frontend/me")
public class FrontendProgressController {

    private final MemberProgressRepository progressRepo;
    private final UserRepository userRepo;
    private final com.powercms.cms.stats.AnalyticsMemberResolver memberResolver;

    public FrontendProgressController(MemberProgressRepository progressRepo,
                                      UserRepository userRepo,
                                      com.powercms.cms.stats.AnalyticsMemberResolver memberResolver) {
        this.progressRepo = progressRepo;
        this.userRepo = userRepo;
        this.memberResolver = memberResolver;
    }

    @GetMapping("/progress")
    public ResponseEntity<?> progress(@RequestParam UUID contentId, HttpServletRequest request) {
        UUID memberId = memberResolver.currentMemberId(request);
        if (memberId == null) return ResponseEntity.status(401).body(Map.of("code", "UNAUTHENTICATED"));
        UUID siteId = homeSiteOf(memberId);
        if (siteId == null) return ResponseEntity.status(401).body(Map.of("code", "UNAUTHENTICATED"));
        return progressRepo.findBySiteIdAndMemberIdAndContentId(siteId, memberId, contentId)
            .<ResponseEntity<?>>map(p -> ResponseEntity.ok(toRow(p.getContentId(), p.getResumeMs(),
                p.getFarthestMs(), p.getWatchedMsTotal(), p.isCompleted(), p.getLastWatchedAt())))
            .orElseGet(() -> ResponseEntity.ok(toRow(contentId, 0, 0, 0, false, null)));
    }

    @GetMapping("/progress/list")
    public ResponseEntity<?> progressList(HttpServletRequest request,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        UUID memberId = memberResolver.currentMemberId(request);
        if (memberId == null) return ResponseEntity.status(401).body(Map.of("code", "UNAUTHENTICATED"));
        UUID siteId = homeSiteOf(memberId);
        if (siteId == null) return ResponseEntity.status(401).body(Map.of("code", "UNAUTHENTICATED"));
        var rows = progressRepo.findBySiteIdAndMemberIdOrderByLastWatchedAtDesc(
            siteId, memberId, PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size))));
        List<Map<String, Object>> list = rows.stream()
            .map(p -> toRow(p.getContentId(), p.getResumeMs(), p.getFarthestMs(),
                p.getWatchedMsTotal(), p.isCompleted(), p.getLastWatchedAt()))
            .toList();
        return ResponseEntity.ok(Map.of("rows", list));
    }

    private UUID homeSiteOf(UUID memberId) {
        return userRepo.findById(memberId).map(u -> u.getHomeSiteId()).orElse(null);
    }

    private Map<String, Object> toRow(UUID contentId, long resumeMs, long farthestMs,
                                      long watchedMsTotal, boolean completed, java.time.Instant lastWatchedAt) {
        return Map.of(
            "contentId", contentId.toString(),
            "resumeMs", resumeMs,
            "farthestMs", farthestMs,
            "watchedMsTotal", watchedMsTotal,
            "completed", completed,
            "lastWatchedAt", lastWatchedAt == null ? "" : lastWatchedAt.toString());
    }
}
