package com.powercms.cms.identity.web;

import com.powercms.cms.identity.AuthenticatedUserPrincipal;
import com.powercms.cms.identity.IdentityService;
import com.powercms.cms.identity.dto.AddSiteMemberRequest;
import com.powercms.cms.identity.dto.CreateUserRequest;
import com.powercms.cms.identity.dto.DisableUserRequest;
import com.powercms.cms.identity.dto.MeResponse;
import com.powercms.cms.identity.dto.SiteAuthorizationPolicyResponse;
import com.powercms.cms.identity.dto.SiteMemberResponse;
import com.powercms.cms.identity.dto.UpdateSiteAuthorizationPolicyRequest;
import com.powercms.cms.identity.dto.UserResponse;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
class IdentityAccessController {

    private final IdentityService identityService;

    IdentityAccessController(IdentityService identityService) {
        this.identityService = identityService;
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/me")
    MeResponse me(Principal principal, HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object loginMode = session == null ? null : session.getAttribute("loginMode");
        return identityService.currentUser(operatorId(principal),
            loginMode instanceof String s ? s : null);
    }

    /** spec 9.1：本人或 system-admin（前台会员：本站 site-admin）读取资料。 */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/users/{id}/profile")
    java.util.Map<String, Object> getProfile(@PathVariable UUID id, Authentication authentication) {
        return identityService.getUserProfile(id, operatorId(authentication));
    }

    /** spec 9.1：本人或 system-admin（前台会员：本站 site-admin）更新资料。 */
    @PreAuthorize("isAuthenticated()")
    @PutMapping("/users/{id}/profile")
    java.util.Map<String, Object> putProfile(@PathVariable UUID id,
                                             @RequestBody java.util.Map<String, Object> fields,
                                             Authentication authentication) {
        return identityService.updateUserProfile(id, fields, operatorId(authentication));
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'admin')")
    @PostMapping("/sites/{siteId}/members")
    ResponseEntity<Void> addMember(@PathVariable UUID siteId,
                                   @Valid @RequestBody AddSiteMemberRequest req,
                                   Principal principal) {
        identityService.addSiteMember(siteId, req.username(), req.roleCode(), operatorId(principal));
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'admin')")
    @GetMapping("/sites/{siteId}/members")
    List<SiteMemberResponse> members(@PathVariable UUID siteId) {
        return identityService.listSiteMembers(siteId);
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'admin')")
    @GetMapping("/sites/{siteId}/frontend-users")
    List<SiteMemberResponse> frontendUsers(@PathVariable UUID siteId) {
        return identityService.listFrontendUsers(siteId);
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'admin')")
    @PostMapping("/sites/{siteId}/frontend-users")
    ResponseEntity<UserResponse> createFrontendUser(@PathVariable UUID siteId,
                                                    @Valid @RequestBody CreateUserRequest req,
                                                    Principal principal) {
        UserResponse user = identityService.createFrontendUser(siteId, req, operatorId(principal));
        return ResponseEntity.status(HttpStatus.CREATED).body(user);
    }

    /** spec 9.1.1：会员详情（该站 admin、system-admin 或本人）。 */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/sites/{siteId}/frontend-users/{id}")
    java.util.Map<String, Object> getFrontendUser(@PathVariable UUID siteId, @PathVariable UUID id,
                                                  Authentication authentication) {
        return identityService.getFrontendUser(siteId, id, operatorId(authentication));
    }

    /** 通过自助注册的待审核会员。 */
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'admin')")
    @PostMapping("/sites/{siteId}/frontend-users/{id}/approve")
    ResponseEntity<Void> approveFrontendUser(@PathVariable UUID siteId, @PathVariable UUID id,
                                             Principal principal) {
        identityService.approveFrontendUser(siteId, id, operatorId(principal));
        return ResponseEntity.noContent().build();
    }

    /** spec 9.1.1：禁用会员（该站 admin 或 system-admin）；可选 reassignTo 迁移引用。 */
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'admin')")
    @PostMapping("/sites/{siteId}/frontend-users/{id}/disable")
    ResponseEntity<Void> disableFrontendUser(@PathVariable UUID siteId, @PathVariable UUID id,
                                             @RequestBody(required = false) DisableUserRequest req,
                                             Principal principal) {
        identityService.disableFrontendUser(siteId, id,
            req == null ? null : req.reassignTo(),
            operatorId(principal));
        return ResponseEntity.noContent().build();
    }

    /** spec 9.1.1：解锁会员（该站 admin 或 system-admin）；恢复登录并清零失败计数。 */
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'admin')")
    @PostMapping("/sites/{siteId}/frontend-users/{id}/unlock")
    ResponseEntity<Void> unlockFrontendUser(@PathVariable UUID siteId, @PathVariable UUID id,
                                            Principal principal) {
        identityService.unlockFrontendUser(siteId, id, operatorId(principal));
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'admin')")
    @GetMapping("/sites/{siteId}/authorization-policy")
    SiteAuthorizationPolicyResponse getPolicy(@PathVariable UUID siteId) {
        return identityService.getAuthorizationPolicy(siteId);
    }

    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'admin')")
    @PutMapping("/sites/{siteId}/authorization-policy")
    SiteAuthorizationPolicyResponse putPolicy(@PathVariable UUID siteId,
                                              @Valid @RequestBody UpdateSiteAuthorizationPolicyRequest req) {
        return identityService.updateAuthorizationPolicy(siteId, req);
    }

    private UUID operatorId(Principal principal) {
        if (!(principal instanceof Authentication authentication)
            || !(authentication.getPrincipal() instanceof AuthenticatedUserPrincipal user)) {
            throw new BusinessException(ErrorCodes.UNAUTHENTICATED, "请先登录");
        }
        return user.getUserId();
    }
}
