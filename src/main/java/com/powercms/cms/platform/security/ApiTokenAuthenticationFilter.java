package com.powercms.cms.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.identity.ApiTokenService;
import com.powercms.cms.identity.AuthenticatedUserPrincipal;
import com.powercms.cms.identity.DpopProof;
import com.powercms.cms.identity.OAuthTokenService;
import com.powercms.cms.identity.UserLanguageService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Authenticates {@code Authorization: Bearer pcm_...}（API Token）与 {@code pcm_oat_...}（OAuth access token）
 * before HTTP Basic。OAuth access token 认证失败时回退到 API Token（顺序：先 API Token 后 OAuth）。
 * OAuth access token 绑定 DPoP cnf.jkt 时，须携带匹配的 DPoP 证明。
 */
public class ApiTokenAuthenticationFilter extends OncePerRequestFilter {

    private final ApiTokenService tokens;
    private final ObjectProvider<OAuthTokenService> oauthTokens;
    private final ObjectMapper objectMapper;
    private final MessageSource messageSource;
    private final UserLanguageService languages;

    public ApiTokenAuthenticationFilter(ApiTokenService tokens, ObjectProvider<OAuthTokenService> oauthTokens,
                                        ObjectMapper objectMapper, MessageSource messageSource,
                                        UserLanguageService languages) {
        this.tokens = tokens;
        this.oauthTokens = oauthTokens;
        this.objectMapper = objectMapper;
        this.messageSource = messageSource;
        this.languages = languages;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path != null && (path.endsWith("/auth/login")
            || path.endsWith("/auth/backend/login")
            || path.endsWith("/auth/frontend/login")
            || path.endsWith("/auth/logout")
            || path.endsWith("/auth/backend/logout")
            || path.endsWith("/auth/frontend/logout"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || header.length() < 8 || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            chain.doFilter(request, response);
            return;
        }
        String plaintext = header.substring(7).trim();
        AuthenticatedUserPrincipal principal = tokens.authenticate(plaintext, clientIp(request)).orElse(null);
        if (principal == null && oauthTokens.getIfAvailable() != null) {
            principal = oauthTokens.getIfAvailable().authenticate(plaintext, dpopJkt(request)).orElse(null);
        }
        if (principal == null) {
            // OBS-MSRC-03：凭证被拒即 401（带 RFC 6750 invalid_token 挑战），与路径是否 permitAll 无关。
            // TC-M04-004：**过期**与**非法**必须可区分——过期单独给 401 文案与 error_description，
            // 调用方据此知道「该重新签发」而不是「令牌本身有问题」。
            if (isExpiredApiToken(plaintext)) {
                ApiJsonAuthHandlers.expiredToken(request, response, messageSource, languages);
            } else {
                ApiJsonAuthHandlers.invalidToken(request, response, messageSource, languages);
            }
            return;
        }
        UsernamePasswordAuthenticationToken authentication =
            UsernamePasswordAuthenticationToken.authenticated(principal, "N/A", principal.getAuthorities());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        chain.doFilter(request, response);
    }

    /** 该 Bearer 凭证是否**仅因过期**被拒（OAuth 令牌不在 API Token 命名空间内，恒返回 false）。 */
    private boolean isExpiredApiToken(String plaintext) {
        try {
            return Boolean.TRUE.equals(tokens.isExpired(plaintext));
        } catch (RuntimeException ex) {
            return false; // 判定失败按非法凭证处理，不改变 401 结果
        }
    }

    /** DPoP 证明（RFC 9728）验证：无效/缺失返回 null（OAuth 绑定 jkt 的 token 会因不匹配而拒绝）。 */
    private String dpopJkt(HttpServletRequest request) {
        String dpop = request.getHeader("DPoP");
        if (dpop == null || dpop.isBlank()) {
            return null;
        }
        try {
            return DpopProof.verify(dpop, request.getMethod(), fullUrl(request), objectMapper).jkt();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String fullUrl(HttpServletRequest request) {
        StringBuffer url = request.getRequestURL();
        if (request.getQueryString() != null) {
            url.append('?').append(request.getQueryString());
        }
        return url.toString();
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
