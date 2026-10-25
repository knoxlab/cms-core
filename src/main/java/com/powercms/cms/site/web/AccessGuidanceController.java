package com.powercms.cms.site.web;

import com.powercms.cms.audit.AuditService;
import com.powercms.cms.site.SiteService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** 站点访问引导：关闭时拒绝仍是 404；打开后已登录用户 403、未登录会员内容 302。 */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/access-guidance")
class AccessGuidanceController {

    private final SiteService sites;
    private final AuditService audit;

    AccessGuidanceController(SiteService sites, AuditService audit) {
        this.sites = sites;
        this.audit = audit;
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @GetMapping
    Map<String, Boolean> get(@PathVariable UUID siteId) {
        return Map.of("enabled", sites.accessGuidanceEnabled(siteId));
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'site-admin')")
    @PutMapping
    Map<String, Boolean> put(@PathVariable UUID siteId, @RequestBody GuidanceBody body) {
        boolean enabled = body != null && body.enabled();
        boolean saved = sites.setAccessGuidance(siteId, enabled);
        audit.write(AuditService.currentActor(), "ACCESS_GUIDANCE", "SITE", siteId.toString(),
            siteId, "SUCCESS", "enabled=" + saved);
        return Map.of("enabled", saved);
    }

    record GuidanceBody(boolean enabled) {}
}
