package com.powercms.cms.platform.web;

import com.powercms.cms.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

@Configuration
public class ManagementAuditInterceptor implements WebMvcConfigurer {
    private final AuditService auditService;
    public ManagementAuditInterceptor(AuditService auditService) { this.auditService = auditService; }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new Recorder(auditService));
    }

    static final class Recorder implements HandlerInterceptor {
        private static final Logger log = LoggerFactory.getLogger(Recorder.class);
        private final AuditService auditService;
        Recorder(AuditService auditService) { this.auditService = auditService; }
        @Override public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                               Object handler, Exception exception) {
            // DEF-M12-014：业务侧在处理过程中挂载的前后值说明（如「状态 DRAFT → IN_REVIEW」），
            // 这里一次性取出并清除——无论下面是否提前返回，都不把 detail 留给线程池里的下一个请求。
            String detail = com.powercms.cms.audit.AuditDetail.getAndClear();
            if (!(handler instanceof HandlerMethod method) || !method.getBeanType().getName().contains(".web.")) return;
            String path = request.getRequestURI();
            if (path.startsWith("/api/v1/delivery/") || path.startsWith("/api/v1/preview/")
                || path.startsWith("/api/v1/auth/") || path.equals("/api/v1/meta")) return;
            Authentication auth = (Authentication) request.getUserPrincipal();
            if (auth == null || !auth.isAuthenticated()) return;
            String actor = auth.getName();
            Object principal = auth.getPrincipal();
            if (principal instanceof com.powercms.cms.identity.AuthenticatedUserPrincipal user) actor = user.getUserId().toString();
            @SuppressWarnings("unchecked")
            Map<String, String> variables = (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
            String objectId = first(variables, "id", "userId", "roleId", "contentId", "projectId", "releaseId", "targetId");
            UUID siteId = parseUuid(variables == null ? null : variables.get("siteId"));
            String traceId = MDC.get("traceId");
            if (traceId == null) traceId = request.getHeader("X-Trace-Id");
            if (traceId == null) traceId = UUID.randomUUID().toString();
            String result = exception == null && response.getStatus() < 400 ? "SUCCESS" : "FAILURE";
            try {
                auditService.write(actor, request.getMethod() + " " + method.getMethod().getName(),
                    method.getBeanType().getSimpleName(), objectId, siteId, result, traceId, detail);
            } catch (RuntimeException auditFailure) {
                log.error("Management audit write failed action={} objectType={}",
                    request.getMethod(), method.getBeanType().getSimpleName(), auditFailure);
            }
        }
        private String first(Map<String, String> values, String... keys) {
            if (values == null) return null;
            for (String key : keys) if (values.containsKey(key)) return values.get(key);
            return null;
        }
        private UUID parseUuid(Object value) { try { return value == null ? null : UUID.fromString(value.toString()); } catch (IllegalArgumentException e) { return null; } }
    }
}
