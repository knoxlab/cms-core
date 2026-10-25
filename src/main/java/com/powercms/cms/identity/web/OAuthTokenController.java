package com.powercms.cms.identity.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.identity.DpopProof;
import com.powercms.cms.identity.OAuthClientService;
import com.powercms.cms.identity.OAuthTokenService;
import com.powercms.cms.identity.entity.OAuthClientEntity;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * OAuth 2.1 token 端点 + 吊销端点（FR-AGT-MCP-013）。
 * 支持 client_secret_basic / client_secret_post 客户端认证，authorization_code / refresh_token grant，
 * 可选 DPoP（RFC 9728）证明验证与 jkt 绑定。
 */
@RestController
public class OAuthTokenController {

    private final OAuthClientService clients;
    private final OAuthTokenService tokens;
    private final ObjectMapper objectMapper;

    public OAuthTokenController(OAuthClientService clients, OAuthTokenService tokens, ObjectMapper objectMapper) {
        this.clients = clients;
        this.tokens = tokens;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/oauth/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public OAuthTokenService.TokenResponse token(
            HttpServletRequest request,
            @RequestHeader(value = "DPoP", required = false) String dpop,
            @RequestParam("grant_type") String grantType,
            @RequestParam(value = "client_id", required = false) String clientId,
            @RequestParam(value = "client_secret", required = false) String clientSecret,
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "redirect_uri", required = false) String redirectUri,
            @RequestParam(value = "code_verifier", required = false) String codeVerifier,
            @RequestParam(value = "refresh_token", required = false) String refreshToken) {

        OAuthClientEntity client = authenticateClient(request, clientId, clientSecret);
        String jkt = verifyDpop(dpop, request);

        return switch (grantType) {
            case "authorization_code" -> tokens.exchangeAuthorizationCode(
                client, code, redirectUri, codeVerifier, jkt);
            case "refresh_token" -> tokens.refresh(client, refreshToken, jkt);
            default -> throw new BusinessException(ErrorCodes.BAD_REQUEST, "不支持的 grant_type：" + grantType);
        };
    }

    @PostMapping(value = "/oauth/revoke", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public void revoke(HttpServletRequest request,
                       @RequestHeader(value = "DPoP", required = false) String dpop,
                       @RequestParam(value = "client_id", required = false) String clientId,
                       @RequestParam(value = "client_secret", required = false) String clientSecret,
                       @RequestParam("token") String token) {
        authenticateClient(request, clientId, clientSecret);
        verifyDpop(dpop, request);
        tokens.revoke(token);
    }

    private OAuthClientEntity authenticateClient(HttpServletRequest request, String clientId, String clientSecret) {
        String basic = request.getHeader("Authorization");
        if (basic != null && basic.regionMatches(true, 0, "Basic ", 0, 6)) {
            String decoded = new String(Base64.getDecoder().decode(basic.substring(6).trim()),
                StandardCharsets.UTF_8);
            int idx = decoded.indexOf(':');
            if (idx > 0) {
                return clients.authenticate(decoded.substring(0, idx), decoded.substring(idx + 1));
            }
        }
        return clients.authenticate(clientId, clientSecret);
    }

    /** DPoP 证明（RFC 9728）可选：若提供则验证并返回 jkt，否则返回 null。 */
    private String verifyDpop(String dpop, HttpServletRequest request) {
        if (dpop == null || dpop.isBlank()) {
            return null;
        }
        DpopProof.Verified verified = DpopProof.verify(dpop, request.getMethod(), fullUrl(request), objectMapper);
        return verified.jkt();
    }

    private static String fullUrl(HttpServletRequest request) {
        StringBuffer url = request.getRequestURL();
        if (request.getQueryString() != null) {
            url.append('?').append(request.getQueryString());
        }
        return url.toString();
    }
}
