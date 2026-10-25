package com.powercms.cms.platform.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 链路标识贯通（FR-LOG-TR-001～004，评审 R-03 前置项）：
 * <ol>
 *   <li>读取入站头 {@code X-PowerCMS-Trace-Id}（上游网关/前置代理透传），缺失则生成
 *       UUID（去横线小写）；</li>
 *   <li>写入 {@code MDC["traceId"]} —— 运行日志图案、{@code ManagementAuditInterceptor}、
 *       {@code AccessLogFilter} 与异常响应体统一取用；</li>
 *   <li>响应头回写同一值 {@code X-PowerCMS-Trace-Id}，作为用户报障的统一凭据
 *       （FR-LOG-AC-008，不另设 requestId）。</li>
 * </ol>
 *
 * <p>注册顺序 {@value #ORDER}（{@code Ordered.HIGHEST_PRECEDENCE} + 1 的守序，早于
 * {@code UserLocaleFilter} 的 order=90 与 Security 链），保证链上所有组件都能读到
 * 同一 traceId。{@code finally} 中移除 MDC（FR-LOG-TR-002），避免线程复用串号。</p>
 *
 * <p>既有 {@code ManagementAuditInterceptor} / {@code PageRenderController} 的
 * {@code UUID.randomUUID()} 兜底保留为防御性写法（FR-LOG-TR-003），正常情况下不再触发。</p>
 */
public class TraceIdFilter extends OncePerRequestFilter {

    /** MDC 键与响应头名的唯一定义处；与 logging.pattern.level 的 %X{traceId} 对齐。 */
    public static final String MDC_KEY = "traceId";
    public static final String HEADER = "X-PowerCMS-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String traceId = incoming != null && !incoming.isBlank() && incoming.length() <= 64
            ? incoming.trim()
            : UUID.randomUUID().toString().replace("-", "").toLowerCase(java.util.Locale.ROOT);
        try {
            MDC.put(MDC_KEY, traceId);
            response.setHeader(HEADER, traceId);
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
