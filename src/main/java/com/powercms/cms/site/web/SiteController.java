package com.powercms.cms.site.web;

import com.powercms.cms.identity.AuthenticatedUserPrincipal;
import com.powercms.cms.site.SiteDeletionService;
import com.powercms.cms.site.SiteService;
import com.powercms.cms.site.dto.CreateSiteRequest;
import com.powercms.cms.site.dto.SiteResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * 站点与环境管理 REST API（FR-SITE-001～006）。
 */
@RestController
@RequestMapping("/api/v1/sites")
class SiteController {

    private final SiteService siteService;
    private final com.powercms.cms.platform.security.SiteAuthorization siteAuthorization;
    private final SiteDeletionService siteDeletionService;

    SiteController(SiteService siteService, com.powercms.cms.platform.security.SiteAuthorization siteAuthorization,
                   SiteDeletionService siteDeletionService) {
        this.siteService = siteService;
        this.siteAuthorization = siteAuthorization;
        this.siteDeletionService = siteDeletionService;
    }

    @GetMapping
    List<SiteResponse> list(@RequestAttribute(value = "organizationId") UUID orgId,
                            @RequestParam(required = false) String status,
                            Authentication authentication) {
        return siteService.list(orgId, status).stream()
            .filter(site -> siteAuthorization.canSee(authentication, site.id())).toList();
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @GetMapping("/{siteId}")
    SiteResponse get(@PathVariable UUID siteId) {
        return siteService.getById(siteId);
    }

    /**
     * 新建站点。社区版无授权配额层（{@code entitlementGate} 为企业版组件），
     * 仅要求系统管理员 + 交互式会话。
     */
    @PreAuthorize("hasRole('system-admin') and @interactiveSessionGuard.requireInteractive(authentication)")
    @PostMapping
    ResponseEntity<SiteResponse> create(@RequestAttribute(value = "organizationId") UUID orgId,
                                        @Valid @RequestBody CreateSiteRequest req,
                                        Authentication authentication) {
        UUID actorId = authentication != null && authentication.getPrincipal() instanceof AuthenticatedUserPrincipal user
            ? user.getUserId() : null;
        return ResponseEntity.status(HttpStatus.CREATED).body(siteService.create(orgId, req, actorId));
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'site-admin')")
    @PostMapping("/{siteId}/freeze")
    ResponseEntity<Void> freeze(@PathVariable UUID siteId) {
        siteService.freeze(siteId);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'site-admin')")
    @PostMapping("/{siteId}/unarchive")
    ResponseEntity<Void> unarchive(@PathVariable UUID siteId) {
        siteService.unarchive(siteId);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'site-admin')")
    @PostMapping("/{siteId}/archive")
    ResponseEntity<Void> archive(@PathVariable UUID siteId) {
        siteService.archive(siteId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 删除预览（FR-SITE-LC-007）：影响面计数 + 阻断项（外部引用）。
     * 前端据此渲染确认弹窗——有阻断项时只提示、不允许确认删除。
     */
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'site-admin')")
    @GetMapping("/{siteId}/deletion-preview")
    SiteDeletionService.DeletionPreview deletionPreview(@PathVariable UUID siteId) {
        return siteDeletionService.preview(siteId);
    }

    /**
     * 删除归档站点（FR-SITE-LC-007）：物理删除该站点全部站点级数据（内容类型、内容、
     * 版本、模板、主题、媒体、发布、工作流、互动、知识库、AI、统计等），审计保留。
     * 仅已归档站点可删；仍有外部引用时 409（SITE_DELETE_BLOCKED），明细见删除预览。
     */
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'site-admin')"
        + " and @interactiveSessionGuard.requireInteractive(authentication)")
    @DeleteMapping("/{siteId}")
    ResponseEntity<Void> delete(@PathVariable UUID siteId) {
        siteDeletionService.delete(siteId);
        return ResponseEntity.noContent().build();
    }
}
