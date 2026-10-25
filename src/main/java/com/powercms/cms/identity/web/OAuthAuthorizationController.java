package com.powercms.cms.identity.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.identity.AuthenticatedUserPrincipal;
import com.powercms.cms.identity.McpScopes;
import com.powercms.cms.identity.OAuthClientService;
import com.powercms.cms.identity.OAuthTokenService;
import com.powercms.cms.identity.entity.OAuthClientEntity;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * OAuth 2.1 授权端点 + consent 页（FR-AGT-MCP-013，identity §9.5.1）。
 * 交互式授权码流程：校验客户端/redirect_uri/scope/resource/PKCE → 展示 consent 页 → 用户确认签发授权码。
 */
@RestController
public class OAuthAuthorizationController {

    private final OAuthClientService clients;
    private final OAuthTokenService tokens;
    private final ObjectMapper objectMapper;

    public OAuthAuthorizationController(OAuthClientService clients, OAuthTokenService tokens,
                                        ObjectMapper objectMapper) {
        this.clients = clients;
        this.tokens = tokens;
        this.objectMapper = objectMapper;
    }

    @PreAuthorize("isAuthenticated() and @interactiveSessionGuard.requireInteractive(authentication)")
    @GetMapping("/oauth/authorize")
    public ResponseEntity<String> authorize(@RequestParam("client_id") String clientId,
                                            @RequestParam("redirect_uri") String redirectUri,
                                            @RequestParam(value = "response_type", defaultValue = "code") String responseType,
                                            @RequestParam(value = "scope", required = false) String scope,
                                            @RequestParam(value = "state", required = false) String state,
                                            @RequestParam(value = "resource", required = false) String resource,
                                            @RequestParam(value = "code_challenge", required = false) String codeChallenge,
                                            @RequestParam(value = "code_challenge_method", required = false) String codeChallengeMethod) {
        OAuthClientEntity client = validateAuthorize(clientId, redirectUri, responseType, scope, resource,
            codeChallenge, codeChallengeMethod);
        List<String> scopes = parseScopes(scope);
        String resolvedResource = resource == null || resource.isBlank() ? OAuthTokenService.MCP_RESOURCE : resource;

        String html = consentPage(client.getClientId(), client.getName(), scopes, resolvedResource,
            redirectUri, state, codeChallenge, codeChallengeMethod);
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }

    @PreAuthorize("isAuthenticated() and @interactiveSessionGuard.requireInteractive(authentication)")
    @PostMapping("/oauth/authorize")
    public ResponseEntity<Void> approve(@RequestParam("client_id") String clientId,
                                        @RequestParam("redirect_uri") String redirectUri,
                                        @RequestParam(value = "response_type", defaultValue = "code") String responseType,
                                        @RequestParam(value = "scope", required = false) String scope,
                                        @RequestParam(value = "state", required = false) String state,
                                        @RequestParam(value = "resource", required = false) String resource,
                                        @RequestParam(value = "code_challenge", required = false) String codeChallenge,
                                        @RequestParam(value = "code_challenge_method", required = false) String codeChallengeMethod,
                                        @RequestParam(value = "approve", defaultValue = "false") boolean approve) {
        OAuthClientEntity client = validateAuthorize(clientId, redirectUri, responseType, scope, resource,
            codeChallenge, codeChallengeMethod);
        if (!approve) {
            return redirect(redirectUri + "?error=access_denied" + stateParam(state));
        }
        UUID userId = currentUserId();
        List<String> scopes = clients.scopesAllowed(client, parseScopes(scope));
        String resolvedResource = resource == null || resource.isBlank() ? OAuthTokenService.MCP_RESOURCE : resource;
        String code = tokens.issueAuthorizationCode(client, userId, redirectUri, scopes, resolvedResource,
            codeChallenge, codeChallengeMethod);
        return redirect(redirectUri + "?code=" + code + stateParam(state));
    }

    private OAuthClientEntity validateAuthorize(String clientId, String redirectUri, String responseType,
                                                String scope, String resource, String codeChallenge,
                                                String codeChallengeMethod) {
        if (clientId == null || clientId.isBlank() || redirectUri == null || redirectUri.isBlank()) {
            throw new BusinessException(ErrorCodes.BAD_REQUEST, "缺少 client_id 或 redirect_uri");
        }
        if (!"code".equals(responseType)) {
            throw new BusinessException(ErrorCodes.BAD_REQUEST, "仅支持 response_type=code");
        }
        OAuthClientEntity client = clients.authenticateById(clientId);
        if (!registeredRedirect(client, redirectUri)) {
            throw new BusinessException(ErrorCodes.BAD_REQUEST, "redirect_uri 未注册");
        }
        // 请求 scope 须在客户端授权范围内
        clients.scopesAllowed(client, parseScopes(scope));
        // RFC 8707：resource 若提供须指向 /api/mcp
        if (resource != null && !resource.isBlank() && !OAuthTokenService.MCP_RESOURCE.equals(resource)) {
            throw new BusinessException(ErrorCodes.BAD_REQUEST, "resource 不受支持：" + resource);
        }
        // PKCE：method 若提供须为 S256
        if (codeChallengeMethod != null && !codeChallengeMethod.isBlank()
            && !"S256".equalsIgnoreCase(codeChallengeMethod)) {
            throw new BusinessException(ErrorCodes.BAD_REQUEST, "仅支持 code_challenge_method=S256");
        }
        return client;
    }

    private boolean registeredRedirect(OAuthClientEntity client, String redirectUri) {
        List<String> uris = parseJsonArray(client.getRedirectUris());
        return uris.contains(redirectUri);
    }

    private List<String> parseJsonArray(String json) {
        try {
            return objectMapper.readValue(json,
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> parseScopes(String scope) {
        if (scope == null || scope.isBlank()) return List.of(McpScopes.READ);
        return java.util.Arrays.stream(scope.trim().split("\\s+"))
            .map(s -> s.trim().toLowerCase(Locale.ROOT))
            .filter(McpScopes::isMcpScope)
            .distinct()
            .toList();
    }

    private UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUserPrincipal p) {
            return p.getUserId();
        }
        throw new BusinessException(ErrorCodes.UNAUTHENTICATED, "请先登录");
    }

    private static ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
    }

    private static String stateParam(String state) {
        return state == null || state.isBlank() ? "" : "&state=" + state;
    }

    private String consentPage(String clientId, String clientName, List<String> scopes, String resource,
                               String redirectUri, String state, String codeChallenge, String codeChallengeMethod) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>授权确认</title></head><body>");
        sb.append("<h2>授权确认</h2>");
        sb.append("<p>应用 <strong>").append(escape(clientName)).append("</strong>（").append(escape(clientId))
            .append("）请求访问 PowerCMS12 的以下能力：</p>");
        sb.append("<ul>");
        for (String s : scopes) {
            sb.append("<li>").append(escape(s)).append("</li>");
        }
        sb.append("</ul>");
        sb.append("<p>资源：<code>").append(escape(resource)).append("</code></p>");
        sb.append("<form method=\"post\" action=\"/oauth/authorize\">");
        sb.append("<input type=\"hidden\" name=\"client_id\" value=\"").append(escape(clientId)).append("\">");
        sb.append("<input type=\"hidden\" name=\"redirect_uri\" value=\"").append(escape(redirectUri)).append("\">");
        sb.append("<input type=\"hidden\" name=\"state\" value=\"").append(escape(state == null ? "" : state)).append("\">");
        sb.append("<input type=\"hidden\" name=\"resource\" value=\"").append(escape(resource)).append("\">");
        sb.append("<input type=\"hidden\" name=\"scope\" value=\"").append(escape(String.join(" ", scopes))).append("\">");
        sb.append("<input type=\"hidden\" name=\"code_challenge\" value=\"")
            .append(escape(codeChallenge == null ? "" : codeChallenge)).append("\">");
        sb.append("<input type=\"hidden\" name=\"code_challenge_method\" value=\"")
            .append(escape(codeChallengeMethod == null ? "" : codeChallengeMethod)).append("\">");
        sb.append("<button type=\"submit\" name=\"approve\" value=\"true\">允许</button>");
        sb.append("<button type=\"submit\" name=\"approve\" value=\"false\">拒绝</button>");
        sb.append("</form>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;");
    }
}
