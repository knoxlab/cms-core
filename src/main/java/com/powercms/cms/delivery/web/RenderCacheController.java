package com.powercms.cms.delivery.web;

import com.powercms.cms.audit.AuditService;
import com.powercms.cms.delivery.RenderDatasetCache;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 渲染 LRU 缓存运维接口（命中率监控 + 手动刷新，对齐 FR-CACHE-001 / FR-CACHE-004 基础能力）。
 */
@RestController
@RequestMapping("/api/v1/delivery/render-cache")
class RenderCacheController {
    private final RenderDatasetCache cache;
    private final AuditService auditService;

    RenderCacheController(RenderDatasetCache cache, AuditService auditService) {
        this.cache = cache;
        this.auditService = auditService;
    }

    /** 命中率快照：**刻意保持机读凭据可用**（治理边界 R3，无秘密、无副作用）。 */
    @GetMapping("/stats")
    @PreAuthorize("hasRole('system-admin')")
    RenderDatasetCache.Stats stats() {
        return cache.stats();
    }

    /** 手动刷新缓存属**运维写面**，与 Agent 工具面无关，收敛到交互式会话。 */
    @PostMapping("/clear")
    @PreAuthorize("hasRole('system-admin') and @interactiveSessionGuard.requireInteractive(authentication)")
    ResponseEntity<RenderDatasetCache.Stats> clear(Authentication auth,
                                                   @RequestParam(required = false) String region) {
        if (region == null || region.isBlank()) {
            cache.clearAll();
        } else {
            try { cache.clear(RenderDatasetCache.Region.valueOf(region.trim().toUpperCase(java.util.Locale.ROOT))); } catch (IllegalArgumentException ex) { throw new com.powercms.common.error.BusinessException(com.powercms.common.error.ErrorCodes.VALIDATION_FAILED, "未知缓存区域: " + region); }
        }
        String actor = auth == null ? "anonymous" : auth.getName();
        auditService.write(actor, "RENDER_CACHE_CLEAR", "RENDER_CACHE",
            region == null || region.isBlank() ? "ALL" : region.toUpperCase(),
            null, "SUCCESS", "手动刷新渲染数据集缓存");
        return ResponseEntity.ok(cache.stats());
    }
}
