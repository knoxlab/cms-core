package com.powercms.cms.identity.web;

import com.powercms.cms.identity.AuthenticatedUserPrincipal;
import com.powercms.cms.identity.OAuthClientService;
import com.powercms.cms.identity.dto.CreateOAuthClientRequest;
import com.powercms.cms.identity.dto.OAuthClientCreatedResponse;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

/**
 * OAuth 客户端管理（FR-AGT-MCP-013 / FR-AGT-SEC-006）：注册/吊销，仅 system-admin。
 * client_secret 仅在创建响应中返回一次明文。
 */
@RestController
@RequestMapping("/api/v1/oauth/clients")
class OAuthClientController {

    private final OAuthClientService clients;

    OAuthClientController(OAuthClientService clients) {
        this.clients = clients;
    }

    @PreAuthorize("hasRole('system-admin') and @interactiveSessionGuard.requireInteractive(authentication)")
    @PostMapping
    ResponseEntity<OAuthClientCreatedResponse> create(@Valid @RequestBody CreateOAuthClientRequest req,
                                                      Principal principal) {
        OAuthClientCreatedResponse created = clients.register(operatorId(principal), req);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PreAuthorize("hasRole('system-admin') and @interactiveSessionGuard.requireInteractive(authentication)")
    @PostMapping("/{clientId}/revoke")
    ResponseEntity<Void> revoke(@PathVariable String clientId, Principal principal) {
        clients.revoke(clientId, operatorId(principal));
        return ResponseEntity.noContent().build();
    }

    /** 管理端客户端列表（FR-AGT-MCP-013）：不含任何机密，仅 system-admin。 */
    @PreAuthorize("hasRole('system-admin') and @interactiveSessionGuard.requireInteractive(authentication)")
    @GetMapping
    List<OAuthClientService.OAuthClientAdminView> list() {
        return clients.listAll();
    }

    private UUID operatorId(Principal principal) {
        if (!(principal instanceof Authentication authentication)
            || !(authentication.getPrincipal() instanceof AuthenticatedUserPrincipal user)) {
            throw new BusinessException(ErrorCodes.UNAUTHENTICATED, "请先登录");
        }
        return user.getUserId();
    }
}
