package com.powercms.cms.delivery.web;

import com.powercms.cms.delivery.CacheWarmupService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sites/{siteId}/render-cache/warmup")
class CacheWarmupController {

    private final CacheWarmupService service;

    CacheWarmupController(CacheWarmupService s) {
        service = s;
    }

    /** 触发预热属**运维写面**（消耗机器资源），收敛到交互式会话。 */
    @PostMapping
    @PreAuthorize("hasRole('system-admin') and @interactiveSessionGuard.requireInteractive(authentication)")
    void trigger(@PathVariable UUID siteId) {
        service.warm(siteId);
    }

    /** 预热进度：**刻意保持机读凭据可用**（治理边界 R3，无秘密、无副作用）。 */
    @GetMapping
    @PreAuthorize("hasRole('system-admin')")
    CacheWarmupService.Status status(@PathVariable UUID siteId) {
        return service.status(siteId);
    }
}
