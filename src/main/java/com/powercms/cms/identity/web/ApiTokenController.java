package com.powercms.cms.identity.web;

import com.powercms.cms.identity.ApiTokenService;
import com.powercms.cms.identity.AuthenticatedUserPrincipal;
import com.powercms.cms.identity.dto.ApiTokenCreatedResponse;
import com.powercms.cms.identity.dto.ApiTokenResponse;
import com.powercms.cms.identity.dto.CreateApiTokenRequest;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
class ApiTokenController {

    private final ApiTokenService tokens;

    ApiTokenController(ApiTokenService tokens) {
        this.tokens = tokens;
    }

    @PreAuthorize("isAuthenticated() and @interactiveSessionGuard.requireInteractive(authentication)")
    @PostMapping("/me/tokens")
    ResponseEntity<ApiTokenCreatedResponse> create(@Valid @RequestBody CreateApiTokenRequest req,
                                                   Principal principal) {
        ApiTokenCreatedResponse created = tokens.create(operatorId(principal), req);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PreAuthorize("isAuthenticated() and @interactiveSessionGuard.requireInteractive(authentication)")
    @GetMapping("/me/tokens")
    List<ApiTokenResponse> list(Principal principal) {
        return tokens.listMine(operatorId(principal));
    }

    @PreAuthorize("isAuthenticated() and @interactiveSessionGuard.requireInteractive(authentication)")
    @DeleteMapping("/me/tokens/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id, Principal principal) {
        tokens.delete(id, operatorId(principal));
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("hasRole('system-admin') and @interactiveSessionGuard.requireInteractive(authentication)")
    @PostMapping("/admin/tokens/{id}/revoke")
    ResponseEntity<Void> revoke(@PathVariable UUID id, Principal principal) {
        tokens.revoke(id, operatorId(principal));
        return ResponseEntity.noContent().build();
    }

    /** 管理端全量 Token 列表（FR-AGT-MCP-004）：仅 system-admin。 */
    @PreAuthorize("hasRole('system-admin') and @interactiveSessionGuard.requireInteractive(authentication)")
    @GetMapping("/admin/tokens")
    List<ApiTokenService.AdminApiTokenView> listAll() {
        return tokens.listAllForAdmin();
    }

    private UUID operatorId(Principal principal) {
        if (!(principal instanceof Authentication authentication)
            || !(authentication.getPrincipal() instanceof AuthenticatedUserPrincipal user)) {
            throw new BusinessException(ErrorCodes.UNAUTHENTICATED, "请先登录");
        }
        return user.getUserId();
    }
}
