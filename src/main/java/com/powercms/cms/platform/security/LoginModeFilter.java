package com.powercms.cms.platform.security;

import com.powercms.cms.identity.AuthenticatedUserPrincipal;
import com.powercms.cms.identity.UserLanguageService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.MessageSource;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 登录模式门禁（spec 3.6 / 5.4 / 9.8）：前台会话（loginMode=FRONTEND）不得调用管理 API，
 * 一律 401 UNAUTHENTICATED（不泄露身份细节）。API Token 与管理面 Basic 不受影响。
 */
public class LoginModeFilter extends OncePerRequestFilter {

    public static final String LOGIN_MODE_ATTR = "loginMode";
    static final String MODE_FRONTEND = "FRONTEND";

    private final MessageSource messageSource;
    private final UserLanguageService languages;

    public LoginModeFilter(MessageSource messageSource, UserLanguageService languages) {
        this.messageSource = messageSource;
        this.languages = languages;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null || !path.startsWith("/api/v1/")) return true;
        return path.startsWith("/api/v1/auth/")
            || path.startsWith("/api/v1/meta")
            || path.equals("/api/v1/me")
            || path.startsWith("/api/v1/delivery/")
            || path.startsWith("/api/v1/preview/")
            || path.startsWith("/api/v1/public/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
            boolean apiToken = authentication.getPrincipal() instanceof AuthenticatedUserPrincipal p
                && p.isApiToken();
            if (!apiToken && isFrontendSession(request)) {
                ApiJsonAuthHandlers.unauthenticated(request, response, messageSource, languages);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    static boolean isFrontendSession(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object mode = session == null ? null : session.getAttribute(LOGIN_MODE_ATTR);
        return MODE_FRONTEND.equals(mode);
    }
}
