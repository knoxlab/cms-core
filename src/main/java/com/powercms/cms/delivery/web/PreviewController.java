package com.powercms.cms.delivery.web;

import com.powercms.cms.audit.AuditService;
import com.powercms.cms.delivery.PreviewTokenService;
import com.powercms.cms.delivery.PageRenderService;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/** Preview SDK API with short-lived, site-scoped signed tokens. */
@RestController
@RequestMapping("/api/v1/preview")
class PreviewController {
    private static final Logger log = LoggerFactory.getLogger(PreviewController.class);

    private final PreviewTokenService service;
    private final PageRenderService pageRenderer;
    private final AuditService audit;
    private final String allowedHost;
    private final String frameAncestor;

    @org.springframework.beans.factory.annotation.Autowired
    PreviewController(PreviewTokenService service, PageRenderService pageRenderer,
                      AuditService audit,
                      @Value("${powercms.preview.allowed-host:}") String allowedHost,
                      @Value("${powercms.preview.admin-frame-ancestor:'self'}") String adminFrameAncestor) {
        this.service = service;
        this.pageRenderer = pageRenderer;
        this.audit = audit;
        this.allowedHost = normalizeHost(allowedHost);
        this.frameAncestor = normalizeFrameAncestor(adminFrameAncestor);
    }

    PreviewController(PreviewTokenService service, String allowedHost, String adminFrameAncestor) {
        this(service, null, null, allowedHost, adminFrameAncestor);
    }

    /** 单测直构入口（无审计协作方）：{@code (service, pageRenderer, allowedHost, frameAncestor)}。 */
    PreviewController(PreviewTokenService service, PageRenderService pageRenderer,
                      String allowedHost, String adminFrameAncestor) {
        this(service, pageRenderer, null, allowedHost, adminFrameAncestor);
    }

    /**
     * TC-M30-008：预览令牌缺失/篡改/伪造/过期一律拒绝并**落审计**。
     *
     * <p>{@code ManagementAuditInterceptor} 显式跳过 {@code /api/v1/preview/}（预览是匿名可达的
     * 交付面），因此拒绝事件必须在控制器内自行写入，否则安全事件无从追溯。缺参抛
     * {@code UNAUTHENTICATED}（401）、签名或有效期不合法抛 {@code FORBIDDEN}（403）。</p>
     *
     * @return 通过校验的站点 ID
     */
    private UUID requireSiteId(String token) {
        if (token == null || token.isBlank()) {
            deny(null, "缺少预览 Token");
            throw new BusinessException(ErrorCodes.UNAUTHENTICATED, "缺少预览 Token");
        }
        try {
            return service.validateToken(token);
        } catch (BusinessException ex) {
            deny(null, ex.getMessage());
            throw ex;
        }
    }

    /** 拒绝事件审计：action 含 preview、result=DENIED，供审计检索与告警使用。 */
    private void deny(UUID siteId, String reason) {
        if (audit == null) return; // 单测直构：无审计协作方
        try {
            audit.write(AuditService.currentActor(), "PREVIEW_ACCESS_DENIED", "PreviewToken",
                null, siteId, "DENIED", "预览访问被拒绝：" + reason);
        } catch (RuntimeException ex) {
            log.warn("预览拒绝审计写入失败 reason={}", reason, ex);
        }
    }
    @PostMapping("/token")
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    PreviewTokenService.PreviewToken generateToken(@RequestParam UUID siteId) {
        return service.generateToken(siteId);
    }

    @GetMapping(value = "/page", produces = "text/html")
    ResponseEntity<String> page(@RequestParam(required = false) String token, @RequestParam String route,
                                @RequestParam(required = false) String mode,
                                HttpServletRequest request) {
        if (!allowedHost.isBlank() && !request.getServerName().equalsIgnoreCase(allowedHost))
            return ResponseEntity.status(403).build();
        UUID siteId = requireSiteId(token);
        boolean editMode = "edit".equalsIgnoreCase(mode);
        try {
            var rendered = pageRenderer.renderPreview(siteId, route, editMode);
            String html = editMode ? injectBridgeScript(rendered.html()) : rendered.html();
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("Content-Security-Policy", previewPageCsp())
                .header("Referrer-Policy", "no-referrer")
                .header("X-Content-Type-Options", "nosniff")
                .header("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
                .header("X-Robots-Tag", "noindex, nofollow")
                .body(html);
        } catch (RuntimeException ex) {
            String message = ex.getMessage() == null ? "预览渲染失败" : ex.getMessage();
            String html = "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"/>"
                + "<title>预览失败</title></head><body><h1>预览失败</h1><pre>"
                + message.replace("<", "&lt;").replace(">", "&gt;")
                + "</pre></body></html>";
            return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .header("Content-Security-Policy", previewPageCsp())
                .header("Referrer-Policy", "no-referrer")
                .header("X-Content-Type-Options", "nosniff")
                .header("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
                .header("X-Robots-Tag", "noindex, nofollow")
                .body(html);
        }
    }
    /** 可视化编辑器页面内桥接脚本；仅 editMode 预览页会引用。 */
    @GetMapping(value = "/uve-bridge.js", produces = "text/javascript;charset=UTF-8")
    ResponseEntity<String> uveBridgeScript() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options", "nosniff")
            .body(loadBridgeScript());
    }

    /** Vendored TinyMCE assets for edit-mode inline WYSIWYG; avoids Maven WebJar mirror issues. */
    @GetMapping("/tinymce/**")
    ResponseEntity<org.springframework.core.io.Resource> tinymceAsset(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String marker = "/api/v1/preview/tinymce/";
        int at = uri.indexOf(marker);
        if (at < 0) return ResponseEntity.notFound().build();
        String path = uri.substring(at + marker.length());
        if (path.isBlank() || path.contains("..") || path.startsWith("/") || path.contains("\\")) {
            return ResponseEntity.notFound().build();
        }
        var resource = new org.springframework.core.io.ClassPathResource("/uve/tinymce/" + path);
        if (!resource.exists() || !resource.isReadable()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options", "nosniff")
            .contentType(org.springframework.http.MediaType.parseMediaType(contentTypeFor(path)))
            .body(resource);
    }

    private static String contentTypeFor(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".js")) return "text/javascript;charset=UTF-8";
        if (lower.endsWith(".css")) return "text/css;charset=UTF-8";
        if (lower.endsWith(".woff2")) return "font/woff2";
        if (lower.endsWith(".woff")) return "font/woff";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".md")) return "text/plain;charset=UTF-8";
        return "application/octet-stream";
    }

    private static volatile String bridgeScriptCache;

    private static String loadBridgeScript() {
        String cached = bridgeScriptCache;
        if (cached != null) return cached;
        try (var in = PreviewController.class.getResourceAsStream("/uve/bridge.js")) {
            if (in == null) throw new IllegalStateException("classpath:/uve/bridge.js 缺失");
            String text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            bridgeScriptCache = text;
            return text;
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    private static String injectBridgeScript(String html) {
        String tag = "<script src=\"/api/v1/preview/tinymce/tinymce.min.js\" defer></script>"
            + "<script src=\"/api/v1/preview/uve-bridge.js\" defer></script>";
        int idx = html.toLowerCase(java.util.Locale.ROOT).lastIndexOf("</body>");
        return idx >= 0 ? html.substring(0, idx) + tag + html.substring(idx) : html + tag;
    }

    /**
     * Theme previews may contain inline author CSS and remote HTTPS images. Scripts remain same-origin
     * only, and no-referrer prevents the signed preview URL from leaking to image hosts.
     */
    private String previewPageCsp() {
        return "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' https: data:; font-src 'self' data:; connect-src 'self'; "
            + "object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors " + frameAncestor;
    }

    @GetMapping("/content")
    ResponseEntity<List<PreviewTokenService.PreviewContent>> listDraftContent(
            @RequestParam(required = false) String token, HttpServletRequest request) {
        if (!allowedHost.isBlank() && !request.getServerName().equalsIgnoreCase(allowedHost)) {
            return ResponseEntity.status(403).build();
        }
        requireSiteId(token); // 缺参（401）与非法/过期（403）同样落拒绝审计
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .header("Content-Security-Policy",
                "default-src 'none'; connect-src 'self'; frame-ancestors " + frameAncestor)
            .header("Referrer-Policy", "no-referrer")
            .header("X-Content-Type-Options", "nosniff")
            .header("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
            .header("X-Robots-Tag", "noindex, nofollow")
            .body(service.listDraftContent(token));
    }

    private static String normalizeHost(String configured) {
        if (configured == null || configured.isBlank()) return "";
        String value = configured.trim();
        try {
            URI uri = value.contains("://") ? URI.create(value) : URI.create("//" + value);
            if (uri.getHost() == null) throw new IllegalArgumentException();
            return uri.getHost();
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("powercms.preview.allowed-host must be a host or absolute URL", ex);
        }
    }

    /** 支持空格分隔的多个来源，如 "'self' http://localhost:5173"；'none' 只能单独使用。 */
    private static String normalizeFrameAncestor(String configured) {
        String value = configured == null ? "" : configured.trim();
        if (value.isBlank()) return "'none'";
        String[] tokens = value.split("\\s+");
        if (tokens.length > 1 && List.of(tokens).contains("'none'"))
            throw new IllegalStateException("powercms.preview.admin-frame-ancestor: 'none' 不能与其他来源同时配置");
        StringBuilder normalized = new StringBuilder();
        for (String token : tokens) {
            if (normalized.length() > 0) normalized.append(' ');
            normalized.append(normalizeFrameAncestorToken(token));
        }
        return normalized.toString();
    }

    private static String normalizeFrameAncestorToken(String token) {
        if ("'none'".equals(token) || "'self'".equals(token)) return token;
        try {
            URI uri = URI.create(token);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || (uri.getPath() != null && !uri.getPath().isEmpty()))
                throw new IllegalArgumentException();
            return uri.getScheme().toLowerCase() + "://" + uri.getAuthority();
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                "powercms.preview.admin-frame-ancestor must be 'self', 'none', or HTTP(S) origins", ex);
        }
    }
}
