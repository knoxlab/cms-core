package com.powercms.cms.identity.web;

import com.powercms.cms.identity.AuthenticatedUserPrincipal;
import com.powercms.cms.identity.IdentityService;
import com.powercms.cms.identity.dto.ChangePasswordRequest;
import com.powercms.cms.identity.dto.CreateUserRequest;
import com.powercms.cms.identity.dto.DisableUserRequest;
import com.powercms.cms.identity.dto.UserResponse;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

/**
 * 用户管理 REST API（FR-IAM-001～003）。
 */
@RestController
@RequestMapping("/api/v1/users")
class UserController {

    private final IdentityService identityService;

    UserController(IdentityService identityService) {
        this.identityService = identityService;
    }

    @PreAuthorize("hasRole('system-admin') and @interactiveSessionGuard.requireInteractive(authentication)")
    @GetMapping
    List<UserResponse> list(@RequestAttribute("organizationId") UUID orgId) {
        return identityService.listUsers(orgId);
    }

    @PreAuthorize("(hasRole('system-admin') and @interactiveSessionGuard.requireInteractive(authentication))"
        + " or @identityAuthorization.isSelf(authentication, #userId)")
    @GetMapping("/{userId}")
    UserResponse get(@PathVariable UUID userId) {
        return identityService.getUser(userId);
    }

    @PreAuthorize("hasRole('system-admin') and @interactiveSessionGuard.requireInteractive(authentication)")
    @PostMapping
    ResponseEntity<UserResponse> create(@RequestAttribute("organizationId") UUID orgId,
                                        @Valid @RequestBody CreateUserRequest req) {
        UserResponse user = identityService.createUser(orgId, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(user);
    }

    @PreAuthorize("hasRole('system-admin') and @interactiveSessionGuard.requireInteractive(authentication)")
    @GetMapping("/{userId}/references")
    java.util.Map<String, Object> references(@PathVariable UUID userId) {
        return identityService.getUserReferences(userId);
    }

    @PreAuthorize("hasRole('system-admin') and @interactiveSessionGuard.requireInteractive(authentication)")
    @PostMapping("/{userId}/disable")
    ResponseEntity<Void> disable(@PathVariable UUID userId,
                                 @RequestBody(required = false) DisableUserRequest req,
                                 Principal principal) {
        identityService.disableUser(userId,
            req == null ? null : req.reassignTo(),
            resolveOperatorId(principal));
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("hasRole('system-admin') and @interactiveSessionGuard.requireInteractive(authentication)")
    @PostMapping("/{userId}/unlock")
    ResponseEntity<Void> unlock(@PathVariable UUID userId, Principal principal) {
        identityService.unlockUser(userId, resolveOperatorId(principal));
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/{userId}/password")
    ResponseEntity<Void> changePassword(@PathVariable UUID userId,
                                        @Valid @RequestBody ChangePasswordRequest req,
                                        Principal principal) {
        identityService.changePassword(userId, req, resolveOperatorId(principal));
        return ResponseEntity.noContent().build();
    }

    private UUID resolveOperatorId(Principal principal) {
        if (!(principal instanceof Authentication authentication)
            || !(authentication.getPrincipal() instanceof AuthenticatedUserPrincipal user)) {
            throw new BusinessException(ErrorCodes.UNAUTHENTICATED, "请先登录");
        }
        return user.getUserId();
    }
}
