package com.powercms.cms.identity.permission;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;

/** 用可信代理层数还原客户端 IP。trusted-proxies 为 0 时忽略 X-Forwarded-For。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class AccessRequestFilter extends OncePerRequestFilter {

    private final int trustedProxies;

    public AccessRequestFilter(@Value("${powercms.analytics.trusted-proxies:0}") int trustedProxies) {
        this.trustedProxies = Math.max(0, trustedProxies);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        String ip = clientIp(request.getHeader("X-Forwarded-For"), request.getRemoteAddr(), trustedProxies);
        AccessRequestContext.bind(ip, Instant.now());
        AccessCacheScope.reset();
        try {
            filterChain.doFilter(request, response);
        } finally {
            AccessRequestContext.clear();
            AccessCacheScope.clear();
        }
    }

    /** 与统计模块同一口径：可信代理为 0 时忽略 X-Forwarded-For。 */
    static String clientIp(String xForwardedFor, String remoteAddr, int trustedProxies) {
        if (xForwardedFor == null || xForwardedFor.isBlank() || trustedProxies <= 0) {
            return remoteAddr;
        }
        String[] hops = xForwardedFor.split(",");
        int usable = hops.length - trustedProxies;
        if (usable <= 0) return remoteAddr;
        String candidate = hops[usable - 1].trim();
        return candidate.isEmpty() ? remoteAddr : candidate;
    }
}
