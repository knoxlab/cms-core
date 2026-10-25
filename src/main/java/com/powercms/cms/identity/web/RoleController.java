package com.powercms.cms.identity.web;

import com.powercms.cms.identity.BackendLayoutCatalog;
import com.powercms.cms.identity.IdentityService;
import com.powercms.cms.identity.SiteCapabilities;
import com.powercms.cms.identity.dto.CreateRoleRequest;
import com.powercms.cms.identity.dto.RoleResponse;
import com.powercms.cms.identity.dto.UpdateRoleCapabilitiesRequest;
import com.powercms.cms.identity.dto.UpdateRoleLayoutsRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/roles")
class RoleController {

    private final IdentityService identityService;

    RoleController(IdentityService identityService) {
        this.identityService = identityService;
    }

    @PreAuthorize("isAuthenticated() and @interactiveSessionGuard.requireInteractive(authentication)")
    @GetMapping
    List<RoleResponse> list(@RequestAttribute("organizationId") UUID orgId) {
        return identityService.listRoles(orgId);
    }

    @PreAuthorize("hasRole('system-admin') and @interactiveSessionGuard.requireInteractive(authentication)")
    @PostMapping
    ResponseEntity<RoleResponse> create(@RequestAttribute("organizationId") UUID orgId,
                                        @Valid @RequestBody CreateRoleRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(identityService.createRole(orgId, req));
    }

    @PreAuthorize("hasRole('system-admin') and @interactiveSessionGuard.requireInteractive(authentication)")
    @DeleteMapping("/{roleId}")
    ResponseEntity<Void> delete(@PathVariable UUID roleId) {
        identityService.deleteRole(roleId);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("hasRole('system-admin') and @interactiveSessionGuard.requireInteractive(authentication)")
    @PutMapping("/{roleId}/capabilities")
    RoleResponse updateCapabilities(@PathVariable UUID roleId,
                                    @Valid @RequestBody UpdateRoleCapabilitiesRequest req) {
        return identityService.updateRoleCapabilities(roleId, req.capabilities());
    }

    @PreAuthorize("hasRole('system-admin') and @interactiveSessionGuard.requireInteractive(authentication)")
    @PutMapping("/{roleId}/layouts")
    RoleResponse updateLayouts(@PathVariable UUID roleId,
                               @RequestBody UpdateRoleLayoutsRequest req) {
        return identityService.updateRoleLayouts(roleId, req.layoutCodes());
    }

    /**
     * 后台布局目录（DEF-11-20）。
     *
     * <p>此前前端在 {@code Identity.tsx} 里硬编码同一份目录，后端已加 {@code interaction} 而前端
     * 未跟进，导致 UI 无法授予「互动」布局、角色列表也不同步。改由后端下发、前端优先取用，
     * 从根上避免再次漂移。</p>
     */
    @PreAuthorize("isAuthenticated() and @interactiveSessionGuard.requireInteractive(authentication)")
    @GetMapping("/layouts")
    List<LayoutOption> layouts() {
        return BackendLayoutCatalog.seeds().stream()
            .map(seed -> new LayoutOption(seed.code(), seed.name()))
            .toList();
    }

    /** 后台布局目录项：编码 + 展示名。 */
    record LayoutOption(String code, String name) {}

    /**
     * 站点能力键目录（FR-IAM-004 / FR-LOG-PM-001）。
     *
     * <p>与 {@code /layouts} 同一治理方案（DEF-11-20 教训）：能力键由后端 {@link SiteCapabilities#ALL}
     * 单一来源下发，前端只做展示兜底，不再各自硬编码 —— 此前前端硬编码 8 项、后端已有 14 项，
     * 缺 stats / stats-admin / interaction-review / interaction-admin / logs / logs-admin，
     * 导致自定义角色无法在 UI 授予这些能力。</p>
     */
    @PreAuthorize("isAuthenticated() and @interactiveSessionGuard.requireInteractive(authentication)")
    @GetMapping("/capabilities")
    List<CapabilityOption> capabilities() {
        return SiteCapabilities.ALL.stream()
            .map(CapabilityOption::new)
            .toList();
    }

    /** 站点能力目录项：能力键（即展示名，键本身语义自明）。 */
    record CapabilityOption(String key) {}
}
