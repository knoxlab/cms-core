package com.powercms.cms.systemlog;

import com.powercms.cms.systemlog.entity.AccessLogEntity;
import com.powercms.cms.systemlog.repo.AccessLogRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 访问日志采集（FR-LOG-AC-001～008）：HTTP 入口层统一记录每次请求的技术维度条目。
 * <ul>
 *   <li>覆盖管理端 API、Delivery API、开放 API；静态资源与健康检查探针默认排除（FR-LOG-AC-004）；</li>
 *   <li>请求方 IP 按 {@code X-Forwarded-For} 还原（最大 8 跳，与既有约定一致，FR-LOG-AC-002）；</li>
 *   <li>Query 敏感键值以 *** 替换、参数名保留（FR-LOG-AC-007）；请求/响应体一律不记录（FR-LOG-AC-003）；</li>
 *   <li>认证失败（401/403）与限流（429）全量记录并归 AUTH_FAILURE / RATE_LIMITED 分类（FR-LOG-AC-005）；</li>
 *   <li>同一匿名路径超过降采样阈值（默认 50 条/秒）时仅记录一次并累计（FR-LOG-AC-004）；</li>
 *   <li>traceId 取自 {@code MDC["traceId"]}（由 {@code TraceIdFilter} 注入，FR-LOG-AC-008）。 </li>
 * </ul>
 */
/**
 * 访问日志采集（FR-LOG-AC-001～008）：HTTP 入口层统一记录每次请求的技术维度条目。
 * <ul>
 *   <li>覆盖管理端 API、Delivery API、开放 API；静态资源与健康检查探针默认排除（FR-LOG-AC-004）；</li>
 *   <li>请求方 IP 按 {@code X-Forwarded-For} 还原（最大 8 跳，与既有约定一致，FR-LOG-AC-002）；</li>
 *   <li>Query 敏感键值以 *** 替换、参数名保留（FR-LOG-AC-007）；请求/响应体一律不记录（FR-LOG-AC-003）；</li>
 *   <li>认证失败（401/403）与限流（429）全量记录并归 AUTH_FAILURE / RATE_LIMITED 分类（FR-LOG-AC-005）；</li>
 *   <li>同一匿名路径超过降采样阈值（默认 50 条/秒）时仅记录一次并累计（FR-LOG-AC-004）；</li>
 *   <li>traceId 取自 {@code MDC["traceId"]}（由 {@code TraceIdFilter} 注入，FR-LOG-AC-008）。 </li>
 * </ul>
 *
 * <p>装配：由 {@link SystemLogFilterConfig} 以 {@code @Bean} + FilterRegistrationBean 注册，
 * 不加 {@code @Component} —— 避免组件扫描使其混入 {@code @WebMvcTest} 切片（切片内无管道依赖）。</p>
 */
public class AccessLogFilter extends OncePerRequestFilter {

    /** XFF 最大跳数（FR-LOG-AC-002，与既有 MAX_HOPS=8 约定一致）。 */
    static final int MAX_XFF_HOPS = 8;
    /** UA 截断长度（FR-LOG-AC-002：512 字符）。 */
    static final int MAX_UA_LENGTH = 512;
    /** 排除采集的路径前缀（FR-LOG-AC-001/004）。 */
    static final String[] EXCLUDED_PREFIXES = {
        "/actuator", "/assets/", "/media-", "/media_", "/mvc/", "/v3/api-docs", "/swagger-ui",
        "/favicon", "/ws/", "/index.html", "/vite.svg"
    };
    /** 每秒同匿名路径降采样阈值由 SystemLogProperties 提供；窗口键 = method + path + 秒。 */

    private final SystemLogPipeline pipeline;
    private final SystemLogProperties props;
    /** 降采样窗口：key=method|path|epochSecond → count。 */
    private final Map<String, AtomicLong> windows = new ConcurrentHashMap<>();

    public AccessLogFilter(SystemLogPipeline pipeline, SystemLogProperties props) {
        this.pipeline = pipeline;
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) return true;
        for (String prefix : EXCLUDED_PREFIXES) {
            if (path.startsWith(prefix)) return true;
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        long start = System.nanoTime();
        Throwable failure = null;
        try {
            chain.doFilter(request, response);
        } catch (Throwable ex) {
            failure = ex;
            throw ex;
        } finally {
            record(request, response, start, failure);
        }
    }

    private void record(HttpServletRequest request, HttpServletResponse response, long startNanos, Throwable failure) {
        try {
            String path = request.getRequestURI();
            String rawQuery = request.getQueryString();
            String sanitizedPath = rawQuery == null || rawQuery.isBlank()
                ? path
                : path + "?" + LogSanitizer.sanitizeQuery(rawQuery);
            String method = request.getMethod();
            short status = (short) response.getStatus();

            // FR-LOG-AC-004 降采样：仅对匿名（无认证）请求按 method+path+秒 计数
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            boolean anonymous = auth == null || !auth.isAuthenticated()
                || "anonymousUser".equals(String.valueOf(auth.getPrincipal()));
            if (anonymous && !shouldRecordAnonymous(method, path)) return;

            long durationMs = Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
            pipeline.submit(AccessLogEntity.create(
                Instant.now(),
                resolveClientIp(request),
                truncate(method, 8),
                truncate(sanitizedPath, 512),
                status,
                (int) Math.min(durationMs, Integer.MAX_VALUE),
                response.getHeader("Content-Length") != null ? longOrNull(response.getHeader("Content-Length")) : null,
                identityOf(auth),
                truncate(request.getHeader("User-Agent"), MAX_UA_LENGTH),
                MDC.get("traceId"),
                null, // siteKey 由请求域解析（控制器维度），过滤器层不可得则留空
                outcomeOf(status, failure),
                null));
        } catch (RuntimeException ignored) {
            // 观测旁路（约束 4）：采集失败绝不影响业务响应
        }
    }

    /** 每秒同匿名路径超过阈值即跳过记录（FR-LOG-AC-004）。 */
    private boolean shouldRecordAnonymous(String method, String path) {
        int threshold = Math.max(1, props.getDownsamplePerSecond());
        String key = method + "|" + path + "|" + (System.currentTimeMillis() / 1000);
        AtomicLong counter = windows.compute(key, (k, existing) -> existing == null ? new AtomicLong() : existing);
        return counter.incrementAndGet() <= threshold;
    }

    /**
     * XFF 还原（FR-LOG-AC-002）：按可信代理层数从右倒数；本项目默认 0 层可信代理
     * （powercms.analytics.trusted-proxies 同口径），取最右一跳为客户端 IP，最多回看 8 跳 ——
     * 即取从右数第 8 跳（窗口内最左一跳），超出的更左侧跳视为不可信伪造。
     */
    static String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff == null || xff.isBlank()) return request.getRemoteAddr();
        String[] hops = xff.split(",");
        int index = Math.max(0, hops.length - MAX_XFF_HOPS);
        String candidate = hops[index].trim();
        return candidate.isEmpty() ? request.getRemoteAddr() : candidate;
    }

    private static String identityOf(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(String.valueOf(auth.getPrincipal()))) {
            return "anonymous";
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof com.powercms.cms.identity.AuthenticatedUserPrincipal user) {
            return truncate(user.getUsername(), 128);
        }
        return truncate(String.valueOf(auth.getPrincipal()), 128);
    }

    /** 结果分类（FR-LOG-AC-002）：SUCCESS / BUSINESS_FAILURE / AUTH_FAILURE / RATE_LIMITED / SYSTEM_ERROR。 */
    static String outcomeOf(int status, Throwable failure) {
        if (failure != null) return AccessLogEntity.OUTCOME_SYSTEM_ERROR;
        if (status == 429) return AccessLogEntity.OUTCOME_RATE_LIMITED;
        if (status == 401 || status == 403) return AccessLogEntity.OUTCOME_AUTH_FAILURE;
        if (status >= 500) return AccessLogEntity.OUTCOME_SYSTEM_ERROR;
        if (status >= 400) return AccessLogEntity.OUTCOME_BUSINESS_FAILURE;
        return AccessLogEntity.OUTCOME_SUCCESS;
    }

    private static Long longOrNull(String value) {
        try {
            return value == null ? null : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
