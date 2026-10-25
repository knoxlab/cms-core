package com.powercms.cms.identity.web;

import com.powercms.cms.identity.AuthenticatedUserPrincipal;
import com.powercms.cms.identity.IdentityService;
import com.powercms.cms.identity.dto.AssignRoleRequest;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.UUID;

@RestController
@PreAuthorize("isAuthenticated() and @interactiveSessionGuard.requireInteractive(authentication)")
@RequestMapping("/api/v1/users/{userId}/roles")
class UserRoleController {

    private final IdentityService identityService;

    UserRoleController(IdentityService identityService) {
        this.identityService = identityService;
    }

    @PostMapping
    ResponseEntity<Void> assign(@PathVariable UUID userId,
                                @Valid @RequestBody AssignRoleRequest req,
                                Principal principal) {
        identityService.assignRole(userId, req, operatorId(principal));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{roleId}")
    ResponseEntity<Void> revoke(@PathVariable UUID userId,
                                @PathVariable UUID roleId,
                                @RequestParam(required = false) UUID siteId,
                                Principal principal) {
        identityService.revokeRole(userId, roleId, siteId, operatorId(principal));
        return ResponseEntity.noContent().build();
    }

    private UUID operatorId(Principal principal) {
        if (!(principal instanceof Authentication authentication)
            || !(authentication.getPrincipal() instanceof AuthenticatedUserPrincipal user)) {
            throw new BusinessException(ErrorCodes.UNAUTHENTICATED, "请先登录");
        }
        return user.getUserId();
    }
}
