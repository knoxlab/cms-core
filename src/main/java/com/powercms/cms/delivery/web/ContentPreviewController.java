package com.powercms.cms.delivery.web;

import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.content.repo.ContentVersionRepository;
import com.powercms.cms.delivery.ContentPreviewTokenService;
import com.powercms.cms.delivery.PreviewTokenService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 一次性内容预览端点（FR-AGT-FLOW-001）。
 *
 * <p>{@code GET /api/v1/preview/content-item?token=...}：Token 仅可消费一次。
 * 已配置路由的内容（页面）跳转到页面预览渲染；无路由内容（如媒体、数据条目）返回只读快照页，
 * 展示 Token 绑定的**那个版本**的字段值，避免 Agent 只能通过描述确认产出。
 */
@RestController
@RequestMapping("/api/v1/preview")
class ContentPreviewController {

    private final ContentPreviewTokenService tokens;
    private final PreviewTokenService pageTokens;
    private final ContentItemRepository itemRepo;
    private final ContentVersionRepository versionRepo;
    private final String allowedHost;

    ContentPreviewController(ContentPreviewTokenService tokens, PreviewTokenService pageTokens,
                             ContentItemRepository itemRepo, ContentVersionRepository versionRepo,
                             @Value("${powercms.preview.allowed-host:}") String allowedHost) {
        this.tokens = tokens;
        this.pageTokens = pageTokens;
        this.itemRepo = itemRepo;
        this.versionRepo = versionRepo;
        this.allowedHost = allowedHost == null ? "" : allowedHost.trim();
    }

    @GetMapping(value = "/content-item", produces = "text/html")
    ResponseEntity<String> contentItem(@RequestParam String token, HttpServletRequest request) {
        if (!allowedHost.isBlank() && !request.getServerName().equalsIgnoreCase(normalizeHost(allowedHost))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        ContentPreviewTokenService.Target target = tokens.consume(token);
        Optional<ContentItemEntity> item = itemRepo.findById(target.contentId())
            .filter(x -> x.getSiteId().equals(target.siteId()));
        if (item.isEmpty()) return notFoundHtml();
        ContentItemEntity content = item.get();
        String route = content.getRoutePath();
        if (route != null && !route.isBlank()) {
            String pageToken = pageTokens.generateToken(target.siteId()).token();
            String location = "/api/v1/preview/page?token=" + pageToken
                + "&route=" + URLEncoder.encode(route, StandardCharsets.UTF_8);
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
        }
        String fields = versionRepo.findByContentItemIdAndVersion(content.getId(), target.version())
            .map(v -> v.getFieldValues()).orElse("{}");
        return htmlSnapshot(content, target.version(), fields);
    }

    private ResponseEntity<String> notFoundHtml() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).cacheControl(CacheControl.noStore())
            .header("X-Robots-Tag", "noindex, nofollow")
            .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'")
            .body("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"/>"
                + "<title>内容不存在</title></head><body><h1>内容不存在或已被删除</h1>"
                + "<p>预览 Token 绑定的内容在当前站点不可用。</p></body></html>");
    }

    private ResponseEntity<String> htmlSnapshot(ContentItemEntity content, int version, String fields) {
        String html = "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"/>"
            + "<title>" + HtmlUtils.htmlEscape(content.getTitle()) + " · 预览（一次性）</title>"
            + "<style>body{font-family:system-ui,-apple-system,sans-serif;margin:24px;color:#1f1f1f}"
            + "pre{background:#f5f5f5;padding:12px;border-radius:6px;overflow:auto}"
            + "code{background:#f0f0f0;padding:1px 4px;border-radius:4px}</style>"
            + "</head><body>"
            + "<h1>" + HtmlUtils.htmlEscape(content.getTitle()) + "</h1>"
            + "<p>内容 ID：<code>" + content.getId() + "</code>　版本：<code>v" + version + "</code>　状态：<code>"
            + HtmlUtils.htmlEscape(content.getStatus()) + "</code></p>"
            + "<p>该链接为一次性预览 Token，刷新后即失效；未配置路由的内容以版本快照展示。</p>"
            + "<h2>版本字段值</h2><pre>" + HtmlUtils.htmlEscape(fields) + "</pre>"
            + "</body></html>";
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'self'")
            .header("Referrer-Policy", "no-referrer")
            .header("X-Content-Type-Options", "nosniff")
            .header("X-Robots-Tag", "noindex, nofollow")
            .body(html);
    }

    private static String normalizeHost(String configured) {
        String value = configured.trim();
        try {
            URI uri = value.contains("://") ? URI.create(value) : URI.create("//" + value);
            if (uri.getHost() == null) throw new IllegalArgumentException();
            return uri.getHost();
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("powercms.preview.allowed-host must be a host or absolute URL", ex);
        }
    }
}
