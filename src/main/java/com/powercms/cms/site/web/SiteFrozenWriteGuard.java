package com.powercms.cms.site.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.site.entity.SiteEntity;
import com.powercms.cms.site.repo.SiteRepository;
import com.powercms.common.error.ApiError;
import com.powercms.common.error.ErrorCodes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 站点冻结写门禁（FR-SITE-004 冻结语义；TC-M06-005 / TC-M18-011）。
 *
 * <p>站点 {@code FROZEN} 后，其下的**写操作**（内容、媒体、发布单、可视化模板、权限、检索重建等）
 * 一律以 423 + {@code SITE_FROZEN} 拒绝，读操作不受影响；此前全仓只有统计采集入口
 * （{@code AnalyticsCollectService}）做了该判定，冻结站点的内容/媒体/发布/模板仍可写入。</p>
 *
 * <p>收敛到 Web 层单一关口而非散落各 Service 的原因：门禁是**站点级**横切约束，放在一起才能保证
 * 「任何新写端点自动继承冻结语义」，也避免各服务构造器为同一个守卫反复膨胀。站点生命周期端点
 * （freeze / archive / unarchive）需要在冻结态下执行，故显式放行。</p>
 */
@Configuration
public class SiteFrozenWriteGuard implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(SiteFrozenWriteGuard.class);

    /** 冻结态下仍须可用的站点生命周期端点（冻结 → 归档 → 解冻）。 */
    private static final Set<String> LIFECYCLE_SUFFIXES = Set.of("/freeze", "/archive", "/unarchive");

    private final SiteRepository siteRepo;
    private final ObjectMapper om;

    public SiteFrozenWriteGuard(SiteRepository siteRepo, ObjectMapper om) {
        this.siteRepo = siteRepo;
        this.om = om;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new Guard(siteRepo, om)).addPathPatterns("/api/v1/sites/**");
    }

    static final class Guard implements HandlerInterceptor {
        private final SiteRepository siteRepo;
        private final ObjectMapper om;

        Guard(SiteRepository siteRepo, ObjectMapper om) {
            this.siteRepo = siteRepo;
            this.om = om;
        }

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
            String method = request.getMethod();
            if (HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method)
                || HttpMethod.OPTIONS.matches(method)) {
                return true;
            }
            String path = request.getRequestURI();
            if (LIFECYCLE_SUFFIXES.stream().anyMatch(path::endsWith)) return true;

            @SuppressWarnings("unchecked")
            Map<String, String> variables =
                (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
            String raw = variables == null ? null : variables.get("siteId");
            UUID siteId = parse(raw);
            if (siteId == null) return true;

            SiteEntity site = siteRepo.findById(siteId).orElse(null);
            if (site == null || !SiteEntity.STATUS_FROZEN.equals(site.getStatus())) return true;

            log.debug("frozen site write rejected site={} {} {}", siteId, method, path);
            writeLocked(response);
            return false;
        }

        private void writeLocked(HttpServletResponse response) throws java.io.IOException {
            String traceId = MDC.get("traceId");
            if (traceId == null || traceId.isBlank()) traceId = UUID.randomUUID().toString();
            response.setStatus(423);
            response.setCharacterEncoding("UTF-8");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            om.writeValue(response.getWriter(),
                ApiError.of(ErrorCodes.SITE_FROZEN, "站点已冻结，禁止写入操作", traceId));
        }

        private static UUID parse(String value) {
            if (value == null || value.isBlank()) return null;
            try {
                return UUID.fromString(value);
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }
    }
}
