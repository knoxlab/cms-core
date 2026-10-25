package com.powercms.cms.identity.web;

import com.powercms.cms.identity.AuthenticatedUserPrincipal;
import com.powercms.cms.identity.SiteCapabilities;
import com.powercms.cms.identity.SiteCapabilityEvaluator;
import com.powercms.cms.identity.dto.PermissionCheckResponse;
import com.powercms.cms.identity.dto.PermissionViewResponse;
import com.powercms.cms.identity.dto.SavePermissionsRequest;
import com.powercms.cms.identity.permission.ObjectPermissionService;
import com.powercms.cms.identity.permission.ObjectType;
import com.powercms.cms.identity.permission.entity.ObjectAclEntity;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * 对象权限 API（spec 9.6，P1）：
 * <ul>
 *   <li>GET /permissions?objectType&objectId → 有效权限 + 直接 ACL + 继承来源；</li>
 *   <li>PUT /permissions → 保存角色/用户权利，可 breakInheritance=true；</li>
 *   <li>POST /permissions/inherit → 恢复继承（二次确认 confirm=true）；</li>
 *   <li>GET /permissions/check → 当前主体是否具备某权利。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/permissions")
class PermissionController {

    private final ObjectPermissionService permissions;
    private final SiteCapabilityEvaluator capabilities;

    PermissionController(ObjectPermissionService permissions, SiteCapabilityEvaluator capabilities) {
        this.permissions = permissions;
        this.capabilities = capabilities;
    }

    @PreAuthorize("isAuthenticated() and @interactiveSessionGuard.requireInteractive(authentication)")
    @GetMapping
    PermissionViewResponse view(@RequestParam String objectType, @RequestParam UUID objectId,
                                Authentication authentication) {
        String type = normalizeType(objectType);
        requireView(authentication, type, objectId);
        ObjectPermissionService.EffectiveView view = permissions.view(type, objectId);
        return new PermissionViewResponse(
            type, objectId, view.inherit(),
            view.source() == null ? null : view.source().objectType(),
            view.source() == null ? null : view.source().objectId(),
            entries(view.directAcl()),
            entries(view.effectiveAcl()));
    }

    @PreAuthorize("isAuthenticated() and @interactiveSessionGuard.requireInteractive(authentication)")
    @PutMapping
    PermissionViewResponse save(@Valid @RequestBody SavePermissionsRequest req,
                                @RequestParam String objectType, @RequestParam UUID objectId,
                                Authentication authentication) {
        String type = normalizeType(objectType);
        requireManage(authentication, type, objectId);
        List<ObjectPermissionService.SavePermissionEntry> entries = req.entries().stream()
            .map(e -> new ObjectPermissionService.SavePermissionEntry(
                e.principalType(), e.principalId(), e.permissions()))
            .toList();
        ObjectPermissionService.EffectiveView view = permissions.savePermissions(
            type, objectId, entries, req.breakInheritance(), userId(authentication));
        return new PermissionViewResponse(
            type, objectId, view.inherit(),
            view.source() == null ? null : view.source().objectType(),
            view.source() == null ? null : view.source().objectId(),
            entries(view.directAcl()),
            entries(view.effectiveAcl()));
    }

    @PreAuthorize("isAuthenticated() and @interactiveSessionGuard.requireInteractive(authentication)")
    @PostMapping("/inherit")
    void restoreInheritance(@RequestParam String objectType, @RequestParam UUID objectId,
                            @RequestParam(defaultValue = "false") boolean confirm,
                            Authentication authentication) {
        String type = normalizeType(objectType);
        requireManage(authentication, type, objectId);
        if (!confirm) {
            throw new BusinessException(ErrorCodes.PERMISSION_INHERIT_CONFLICT,
                "恢复继承需要二次确认（confirm=true）");
        }
        permissions.restoreInheritance(type, objectId, userId(authentication));
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/check")
    PermissionCheckResponse check(@RequestParam String objectType, @RequestParam UUID objectId,
                                  @RequestParam String right, Authentication authentication) {
        String type = normalizeType(objectType);
        boolean granted = permissions.hasRight(userId(authentication), type, objectId, right);
        return new PermissionCheckResponse(type, objectId, right, granted);
    }

    private void requireView(Authentication authentication, String objectType, UUID objectId) {
        if (canManage(authentication, objectType, objectId)) return;
        if (permissions.hasRight(userId(authentication), objectType, objectId, ObjectType.VIEW)) return;
        throw new BusinessException(ErrorCodes.FORBIDDEN, "无权查看该对象的权限");
    }

    private void requireManage(Authentication authentication, String objectType, UUID objectId) {
        if (!canManage(authentication, objectType, objectId)) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "无权管理该对象的权限");
        }
    }

    private boolean canManage(Authentication authentication, String objectType, UUID objectId) {
        UUID operator = userId(authentication);
        if (operator == null) return false;
        if (capabilities.isSystemAdmin(operator)) return true;
        UUID siteId = permissions.siteIdOf(objectType, objectId);
        if (siteId != null && capabilities.has(operator, siteId, SiteCapabilities.ADMIN)) return true;
        return permissions.hasRight(operator, objectType, objectId, ObjectType.MANAGE);
    }

    private static List<PermissionViewResponse.Entry> entries(List<ObjectAclEntity> rows) {
        return rows.stream()
            .sorted(ObjectPermissionService.byPrincipal())
            .map(row -> new PermissionViewResponse.Entry(
                row.getPrincipalType(), row.getPrincipalId(),
                List.copyOf(parse(row.getPermissions()))))
            .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> parse(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, List.class);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static String normalizeType(String objectType) {
        String type = objectType == null ? "" : objectType.trim().toUpperCase(java.util.Locale.ROOT);
        if (!ObjectType.ALL.contains(type)) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "不支持的对象类型: " + objectType);
        }
        return type;
    }

    private static UUID userId(Authentication authentication) {
        return authentication != null
            && authentication.getPrincipal() instanceof AuthenticatedUserPrincipal p ? p.getUserId() : null;
    }
}
