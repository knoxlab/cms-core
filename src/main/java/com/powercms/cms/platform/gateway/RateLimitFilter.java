package com.powercms.cms.platform.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 公开交付 API 速率限制（FR-API-006）：按客户端 IP 的滑动窗口计数，
 * 超限返回 429 并带 Retry-After；命中时递增 RATE_LIMITED 指标。
 * 限流消息按请求语言本地化（FR-I18N-002）。
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    static final String METRIC_LIMITED = "powercms.ratelimit.limited";
    static final int WINDOW_MS = 60_000;

    private final int limitPerMinute;
    private final MessageSource messageSource;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    RateLimitFilter(@Value("${powercms.gateway.rate-limit-per-minute:300}") int limitPerMinute,
                    MessageSource messageSource) {
        this.limitPerMinute = Math.max(1, limitPerMinute);
        this.messageSource = messageSource;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/v1/delivery/")
            && !path.startsWith("/api/v1/public/")
            && !path.equals("/graphql");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long now = System.currentTimeMillis();
        String key = clientKey(request);
        Window window = windows.compute(key, (k, existing) ->
            (existing == null || existing.expired(now)) ? new Window() : existing);
        if (!window.tryAcquire(now, limitPerMinute)) {
            RegistryHolder.incrementLimited();
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", "60");
            response.setContentType("application/json;charset=UTF-8");
            String message = messageSource.getMessage("error.rate.limited", null,
                "请求过于频繁，请稍后重试", LocaleContextHolder.getLocale());
            response.getWriter().write("{\"code\":\"RATE_LIMITED\",\"message\":\"" + json(message) + "\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private static String json(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return comma > 0 ? forwarded.substring(0, comma).trim() : forwarded.trim();
        }
        return request.getRemoteAddr();
    }

    /** 单窗口实例只被一个客户端键持有；tryAcquire 内部加锁保证并发正确。 */
    static final class Window {
        private final Deque<Long> hits = new ArrayDeque<>();

        boolean expired(long now) {
            synchronized (hits) {
                evictOlderThan(now);
                return hits.isEmpty();
            }
        }

        boolean tryAcquire(long now, int limit) {
            synchronized (hits) {
                evictOlderThan(now);
                if (hits.size() >= limit) return false;
                hits.addLast(now);
                return true;
            }
        }

        int size(long now) {
            synchronized (hits) {
                evictOlderThan(now);
                return hits.size();
            }
        }

        private void evictOlderThan(long now) {
            while (!hits.isEmpty() && now - hits.peekFirst() >= WINDOW_MS) {
                hits.pollFirst();
            }
        }
    }
}
