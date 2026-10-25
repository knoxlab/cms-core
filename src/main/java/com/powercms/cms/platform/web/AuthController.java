package com.powercms.cms.platform.web;

import com.powercms.cms.audit.AuditService;
import com.powercms.cms.identity.AuthenticatedUserPrincipal;
import com.powercms.cms.identity.IdentityService;
import com.powercms.cms.identity.dto.FrontendLoginRequest;
import com.powercms.cms.identity.dto.LoginRequest;
import com.powercms.cms.site.entity.SiteEntity;
import com.powercms.cms.site.repo.SiteRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

/**
 * 双登录通道（spec 5.4）：后台登录与前台登录使用不同入口，会话记录 loginMode。
 * 后台会话可访问管理 API；前台会话被 {@code LoginModeFilter} 拒绝访问管理 API。
 * {@code POST /auth/login} 为后台登录别名，兼容既有管理端。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String GENERIC_LOGIN_ERROR = "用户名或密码错误";
    private static final String LOGIN_MODE_ATTR = "loginMode";
    private static final String LOGIN_MODE_BACKEND = "BACKEND";
    private static final String LOGIN_MODE_FRONTEND = "FRONTEND";

    private final AuthenticationManager authenticationManager;
    private final IdentityService identityService;
    private final SiteRepository siteRepo;
    private final AuditService audit;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();
    private final SecurityContextLogoutHandler logoutHandler = new SecurityContextLogoutHandler();

    public AuthController(AuthenticationConfiguration authenticationConfiguration,
                          IdentityService identityService,
                          SiteRepository siteRepo,
                          AuditService audit) throws Exception {
        this.authenticationManager = authenticationConfiguration.getAuthenticationManager();
        this.identityService = identityService;
        this.siteRepo = siteRepo;
        this.audit = audit;
    }

    @GetMapping("/me")
    Map<String, Object> me(Principal principal, HttpServletRequest request) {
        if (principal == null) {
            return Map.of("authenticated", false);
        }
        return Map.of("authenticated", true, "username", principal.getName(),
            "loginMode", loginMode(request));
    }

    @GetMapping("/status")
    Map<String, Object> status(HttpServletRequest request) {
        boolean authenticated = request.getRemoteUser() != null;
        String username = authenticated ? request.getRemoteUser() : null;
        return Map.of("authenticated", authenticated,
            "username", username != null ? username : "",
            "loginMode", authenticated ? loginMode(request) : "");
    }

    /** 后台登录别名（兼容既有管理端）。 */
    @PostMapping("/login")
    Map<String, Object> legacyLogin(@Valid @RequestBody LoginRequest req,
                                    HttpServletRequest request,
                                    HttpServletResponse response) {
        return backendLogin(req, request, response);
    }

    @PostMapping("/backend/login")
    Map<String, Object> backendLogin(@Valid @RequestBody LoginRequest req,
                                     HttpServletRequest request,
                                     HttpServletResponse response) {
        Authentication authentication = authenticate(req.username(), req.password());
        UUID userId = userIdOf(authentication);
        String ip = clientIp(request);
        if (userId != null) {
            if (!identityService.hasBackendRole(userId)) {
                audit.write(authentication.getName(), "LOGIN_DENIED_BACKEND", "User",
                    userId.toString(), null, "FAILURE", "reason=NO_BACKEND_ROLE, ip=" + ip);
                throw new BusinessException(ErrorCodes.UNAUTHENTICATED, GENERIC_LOGIN_ERROR);
            }
            if (!identityService.hasBackendAccess(userId)) {
                audit.write(authentication.getName(), "LOGIN_DENIED_BACKEND", "User",
                    userId.toString(), null, "FAILURE", "reason=NO_BACKEND_LAYOUT, ip=" + ip);
                throw new BusinessException(ErrorCodes.NO_BACKEND_LAYOUT, "账号不具备后台工具访问资格");
            }
        }
        establishSession(authentication, request, response, LOGIN_MODE_BACKEND);
        if (userId != null) {
            identityService.recordSuccessfulLogin(userId, ip, LOGIN_MODE_BACKEND);
        }
        audit.write(authentication.getName(), "LOGIN_SUCCESS", "User",
            userId != null ? userId.toString() : authentication.getName(),
            null, "SUCCESS", "mode=BACKEND, ip=" + ip + ", ua=" + userAgent(request));
        return Map.of("authenticated", true, "username", authentication.getName(),
            "loginMode", LOGIN_MODE_BACKEND);
    }

    @PostMapping("/frontend/login")
    Map<String, Object> frontendLogin(@Valid @RequestBody FrontendLoginRequest req,
                                      HttpServletRequest request,
                                      HttpServletResponse response) {
        String blocked = identityService.frontendLoginBlockReason(req.username());
        if (blocked != null) {
            // 待审核拦截与登录失败文案同口径：稳定键 + 参数本地化（FR-IAM-002）。
            throw BusinessException.keyed(ErrorCodes.FORBIDDEN, blocked,
                IdentityService.MSG_LOGIN_PENDING);
        }
        Authentication authentication = authenticate(req.username(), req.password());
        UUID userId = userIdOf(authentication);
        String ip = clientIp(request);
        SiteEntity site = resolveSite(req.siteKey(), request);
        if (userId != null && !identityService.hasFrontendAccess(userId)) {
            audit.write(authentication.getName(), "LOGIN_DENIED_FRONTEND", "User",
                userId.toString(), site != null ? site.getId() : null, "FAILURE",
                "reason=NO_FRONTEND_ROLE, ip=" + ip);
            throw new BusinessException(ErrorCodes.UNAUTHENTICATED, GENERIC_LOGIN_ERROR);
        }
        establishSession(authentication, request, response, LOGIN_MODE_FRONTEND);
        if (userId != null) {
            identityService.recordSuccessfulLogin(userId, ip, LOGIN_MODE_FRONTEND);
        }
        audit.write(authentication.getName(), "LOGIN_SUCCESS", "User",
            userId != null ? userId.toString() : authentication.getName(),
            site != null ? site.getId() : null, "SUCCESS",
            "mode=FRONTEND, ip=" + ip + ", ua=" + userAgent(request));
        return Map.of("authenticated", true, "username", authentication.getName(),
            "loginMode", LOGIN_MODE_FRONTEND,
            "siteKey", site != null ? site.getSiteKey() : null);
    }

    @PostMapping("/logout")
    Map<String, Object> legacyLogout(HttpServletRequest request, HttpServletResponse response) {
        return backendLogout(request, response);
    }

    @PostMapping("/backend/logout")
    Map<String, Object> backendLogout(HttpServletRequest request, HttpServletResponse response) {
        clearSession(request, response);
        return Map.of("ok", true);
    }

    @PostMapping("/frontend/logout")
    Map<String, Object> frontendLogout(HttpServletRequest request, HttpServletResponse response) {
        clearSession(request, response);
        return Map.of("ok", true);
    }

    private Authentication authenticate(String username, String password) {
        try {
            return authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(username, password));
        } catch (AuthenticationException e) {
            // FR-IAM-002：失败文案带剩余尝试次数；账号锁定/停用/待审核/密码过期给准确原因。
            throw identityService.loginFailure(username, e);
        }
    }

    private void establishSession(Authentication authentication, HttpServletRequest request,
                                  HttpServletResponse response, String loginMode) {
        HttpSession session = request.getSession(true);
        session.setAttribute(LOGIN_MODE_ATTR, loginMode);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }

    private void clearSession(HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        logoutHandler.logout(request, response, authentication);
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(LOGIN_MODE_ATTR);
        }
    }

    private SiteEntity resolveSite(String siteKey, HttpServletRequest request) {
        if (siteKey != null && !siteKey.isBlank()) {
            String key = siteKey.trim();
            try {
                return siteRepo.findById(UUID.fromString(key))
                    .or(() -> siteRepo.findBySiteKey(key))
                    .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "站点不存在"));
            } catch (IllegalArgumentException ex) {
                return siteRepo.findBySiteKey(key)
                    .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "站点不存在"));
            }
        }
        String host = request.getHeader("Host");
        if (host == null || host.isBlank()) {
            throw new BusinessException(ErrorCodes.BAD_REQUEST, "前台登录必须携带 siteKey 或 Host 站点上下文");
        }
        String candidate = host.split(":")[0].trim();
        return siteRepo.findBySiteKey(candidate)
            .orElseThrow(() -> new BusinessException(ErrorCodes.BAD_REQUEST, "无法从前台上下文解析站点"));
    }

    private static String loginMode(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object mode = session == null ? null : session.getAttribute(LOGIN_MODE_ATTR);
        return mode instanceof String s ? s : "";
    }

    private static UUID userIdOf(Authentication authentication) {
        return authentication.getPrincipal() instanceof AuthenticatedUserPrincipal user
            ? user.getUserId() : null;
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static String userAgent(HttpServletRequest request) {
        String ua = request.getHeader("User-Agent");
        if (ua == null || ua.isBlank()) return "";
        return ua.length() > 80 ? ua.substring(0, 80) : ua;
    }
}
