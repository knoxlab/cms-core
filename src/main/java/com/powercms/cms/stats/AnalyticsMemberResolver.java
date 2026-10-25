package com.powercms.cms.stats;

import com.powercms.cms.identity.AuthenticatedUserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 采集侧会员身份解析（FR-STAT-037）：
 * 仅当请求携带已认证的 FRONTEND 会员会话时返回会员 ID，
 * 会员标识一律由服务端解析，客户端不可上报（防伪造）。
 */
@Component
public class AnalyticsMemberResolver {

    /**
     * 从当前请求的 Spring Security 上下文解析会员 ID。
     * 前台会员登录后会话含 FRONTEND loginMode（见 AuthController），principal 为 AuthenticatedUserPrincipal。
     */
    public UUID currentMemberId(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) return null;
        if (authentication.getPrincipal() instanceof AuthenticatedUserPrincipal p && !p.isApiToken()) {
            return p.getUserId();
        }
        return null;
    }
}
