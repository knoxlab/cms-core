package com.powercms.cms.delivery.web;

import com.powercms.cms.audit.AuditService;
import com.powercms.cms.identity.permission.AccessCacheScope;
import com.powercms.cms.delivery.DeliveryCachePolicy;
import com.powercms.cms.delivery.PageRenderService;
import com.powercms.cms.delivery.navigation.UrlRewriteService;
import com.powercms.cms.identity.AuthenticatedUserPrincipal;
import com.powercms.cms.site.entity.SiteEntity;
import com.powercms.cms.site.repo.SiteRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Locale;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/delivery")
class PageRenderController {
    private final PageRenderService service;
    private final UrlRewriteService rewrites;
    private final SiteRepository sites;
    private final String loginPath;
    private AuditService audit;

    PageRenderController(PageRenderService service, UrlRewriteService rewrites, SiteRepository sites,
                         @Value("${powercms.delivery.login-path:/login}") String loginPath) {
        this.service = service;
        this.rewrites = rewrites;
        this.sites = sites;
        this.loginPath = loginPath == null || loginPath.isBlank() ? "/login" : loginPath;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setAudit(AuditService audit) {
        this.audit = audit;
    }

    @GetMapping(value = "/sites/{siteId}/pages", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> page(@PathVariable UUID siteId, @RequestParam String route, HttpServletRequest request, Principal principal, Locale locale) {
        try {
            var query = new LinkedHashMap<String, Object>();
            request.getParameterMap().forEach((k, v) -> { if (!"route".equals(k) && query.size() < 32) query.put(k, v.length == 0 ? "" : v[0]); });
            String deliveryRoute = rewrites.rewrite(siteId, route, false);
            var rendered = service.renderPublished(siteId, deliveryRoute, request.getMethod(), request.getRequestURI(), query, locale, principal == null ? null : principal.getName(), null);
            return ResponseEntity.ok().header("X-PowerCMS-Page-Id", rendered.id().toString())
                .header("X-PowerCMS-Page-Version", String.valueOf(rendered.version()))
                .header("Cache-Control", cacheControl(principal))
                .header("Vary", "Cookie")
                .body(rendered.html());
        } catch (BusinessException | IllegalArgumentException ex) {
            boolean denied = ex instanceof BusinessException be && ErrorCodes.PERMISSION_DENIED.equals(be.getCode());
            if (denied && audit != null) {
                String actor = principal == null || principal.getName() == null ? "anonymous" : principal.getName();
                audit.write(actor, "PAGE_ACCESS_DENIED", "PAGE", route, siteId, "DENIED", ex.getMessage());
            }
            boolean notFound = ex instanceof BusinessException be && ErrorCodes.NOT_FOUND.equals(be.getCode());
            if (denied && guidance(siteId)) {
                if (identified(principal)) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .header("Cache-Control", "no-store")
                        .body("""
                            <!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8"/><title>无权访问</title></head>
                            <body><h1>无权访问</h1><p>当前账号不能查看该页面。</p></body></html>
                            """);
                }
                String target = loginPath + "?redirect=" + URLEncoder.encode(route, StandardCharsets.UTF_8);
                return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, target)
                    .header("Cache-Control", "no-store")
                    .build();
            }
            if (notFound || denied) {
                String notFoundHtml = """
                    <!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8"/><title>页面不存在</title></head>
                    <body><h1>页面不存在</h1><p>您访问的页面不存在或已移除。</p></body></html>
                    """;
                return ResponseEntity.status(404)
                    .header("Cache-Control", "no-store")
                    .body(notFoundHtml);
            }
            String traceId = MDC.get("traceId");
            if (traceId == null || traceId.isBlank()) traceId = UUID.randomUUID().toString();
            String html = """
                <!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8"/><title>页面不可用</title></head>
                <body><h1>页面暂时无法显示</h1><p>请稍后重试。若问题持续，请联系站点管理员。</p>
                <p><small>traceId=%s</small></p></body></html>
                """.formatted(traceId);
            return ResponseEntity.status(500)
                .header("Cache-Control", "no-store")
                .header("X-PowerCMS-Trace-Id", traceId)
                .body(html);
        }
    }

    private boolean guidance(UUID siteId) {
        return sites.findById(siteId).map(SiteEntity::isAccessGuidanceEnabled).orElse(false);
    }

    private static boolean identified(Principal principal) {
        if (principal instanceof Authentication auth
            && auth.getPrincipal() instanceof AuthenticatedUserPrincipal) {
            return true;
        }
        var current = SecurityContextHolder.getContext().getAuthentication();
        return current != null && current.getPrincipal() instanceof AuthenticatedUserPrincipal;
    }

    private static String cacheControl(Principal principal) {
        if (!identified(principal)) {
            return AccessCacheScope.shared()
                ? DeliveryCachePolicy.anonymousPublic()
                : DeliveryCachePolicy.unshared();
        }
        return DeliveryCachePolicy.memberPrivate();
    }
}
