package com.powercms.cms.platform.web;

import com.powercms.cms.delivery.AnalyticsInjector;
import com.powercms.cms.site.repo.SiteRepository;
import com.powercms.cms.sitepackage.WebappsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

/**
 * 站点应用静态服务（{@code /webapps/{siteKey}/**}）。
 *
 * <p>站点包导入时 React/Vue 前端产物被部署到 {@code webapps/{siteKey}/}（{@link WebappsService}），
 * 本控制器把该目录直接对外服务：真实文件按静态资源返回，客户端路由（无扩展名路径）回退
 * {@code index.html}（SPA catch-all）。</p>
 *
 * <p><b>API 路径自动匹配</b>：服务 {@code index.html} 时向 {@code &lt;head&gt;} 注入启动配置——
 * {@code window.__POWERCMS_SITE_ID__}（站点 UUID，应用读取后即可拼出站点 API 路径）、
 * {@code window.__POWERCMS_SITE_KEY__}、{@code window.__POWERCMS_API_BASE__}（默认 {@code /api/v1}）。
 * 应用无需手工携带 {@code ?siteId=} 或硬编码 API 根；以应用基路径相对寻址的调用
 * （如 {@code /webapps/{siteKey}/api/...}）另有 {@link WebappsApiRewriteFilter} 透明改写到 {@code /api/...}。</p>
 *
 * <p><b>统计采集</b>：站点启用统计采集组件时（{@link AnalyticsInjector#renderSiteAppTag}），
 * 同时向 {@code <head>} 注入第一方采集脚本（analytics.js + 配置），使站点应用的页面浏览 /
 * 交互事件进入后台统计分析；片段排在应用模块脚本之前，保证应用挂载时采集接口已就绪。</p>
 */
@RestController
public class SiteAppController {

    private final WebappsService webapps;
    private final SiteRepository siteRepo;
    private final AnalyticsInjector analyticsInjector;

    public SiteAppController(WebappsService webapps, SiteRepository siteRepo, AnalyticsInjector analyticsInjector) {
        this.webapps = webapps;
        this.siteRepo = siteRepo;
        this.analyticsInjector = analyticsInjector;
    }

    @GetMapping({"/webapps/{siteKey}", "/webapps/{siteKey}/**"})
    public ResponseEntity<Resource> serve(@PathVariable String siteKey, HttpServletRequest request)
            throws NoResourceFoundException {
        var site = siteRepo.findBySiteKey(siteKey).orElse(null);
        if (!webapps.isEnabled() || site == null) {
            throw notFound(request);
        }
        Path dir = webapps.siteDir(siteKey);
        String relative = relativePath(request);
        // 目录入口无尾斜杠 → 302 补斜杠（静态服务器惯例）：index.html 的资源引用是相对路径
        // （Vite base './'），以 /webapps/{siteKey} 为文档基会把 ./assets/** 解析到
        // /webapps/assets/**（被当成 siteKey，404 返回 JSON 破坏 MIME）；查询参数随重定向保留。
        if (relative.isEmpty() && !request.getRequestURI().endsWith("/")) {
            Path entry = resolveOrNull(dir, "index.html");
            if (entry == null || !Files.isRegularFile(entry)) {
                throw notFound(request);
            }
            String query = request.getQueryString();
            String location = request.getRequestURI() + "/"
                + (query == null || query.isBlank() ? "" : "?" + query);
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
        }
        Path file = resolveOrNull(dir, relative);
        if (file == null || !Files.isRegularFile(file)) {
            // 客户端路由（无扩展名）回退 SPA 入口；带扩展名的缺失资源保持 404
            String last = relative.substring(relative.lastIndexOf('/') + 1);
            if (!last.isBlank() && last.contains(".")) {
                throw notFound(request);
            }
            file = resolveOrNull(dir, "index.html");
            if (file == null || !Files.isRegularFile(file)) {
                throw notFound(request);
            }
        }
        try {
            boolean entry = file.getFileName().toString().equalsIgnoreCase("index.html");
            MediaType type = mediaType(file);
            CacheControl cache = entry
                ? CacheControl.noCache()
                : (relative.startsWith("assets/")
                    ? CacheControl.maxAge(Duration.ofDays(365)).cachePublic()
                    : CacheControl.maxAge(Duration.ofHours(1)).cachePublic());
            if (entry) {
                String trackerTag = analyticsInjector.renderSiteAppTag(site.getId()).orElse(null);
                byte[] html = injectBootConfig(Files.readAllBytes(file), site.getId().toString(), siteKey, trackerTag);
                return ResponseEntity.ok().contentType(type).cacheControl(cache)
                    .body(new ByteArrayResource(html));
            }
            return ResponseEntity.ok().contentType(type).cacheControl(cache)
                .contentLength(Files.size(file)).body(new FileSystemResource(file));
        } catch (Exception e) {
            throw notFound(request);
        }
    }

    private static NoResourceFoundException notFound(HttpServletRequest request) {
        // 与 SpaForwardController 同语义：404 统一错误体，不用 ResponseStatusException（会被兜底当 500）
        return new NoResourceFoundException(HttpMethod.GET, request.getRequestURI());
    }

    /** 请求路径中 {@code /webapps/{siteKey}} 之后的相对部分（已解码，无前导斜杠）。 */
    private static String relativePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String ctx = request.getContextPath() == null ? "" : request.getContextPath();
        if (!ctx.isEmpty() && uri.startsWith(ctx)) {
            uri = uri.substring(ctx.length());
        }
        // 去掉 /webapps/{siteKey} 前缀两段（先去前导斜杠）
        String rest = uri.startsWith("/") ? uri.substring(1) : uri;
        for (int i = 0; i < 2; i++) {
            int slash = rest.indexOf('/');
            rest = slash < 0 ? "" : rest.substring(slash + 1);
        }
        return UriUtils.decode(rest, StandardCharsets.UTF_8);
    }

    private Path resolveOrNull(Path dir, String relative) {
        if (relative.isBlank()) {
            return dir.resolve("index.html").normalize();
        }
        try {
            return webapps.resolveSafe(dir, relative);
        } catch (SecurityException e) {
            return null;
        }
    }

    private static MediaType mediaType(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".html") || name.endsWith(".htm")) {
            return new MediaType("text", "html", StandardCharsets.UTF_8);
        }
        return MediaTypeFactory.getMediaType(file.getFileName().toString())
            .orElse(MediaType.APPLICATION_OCTET_STREAM);
    }

    /** 注入启动配置：站点标识 + API 根，使站点应用自动匹配站点 API 路径；可选统计采集片段。 */
    static byte[] injectBootConfig(byte[] indexHtml, String siteId, String siteKey, String analyticsTag) {
        String html = new String(indexHtml, StandardCharsets.UTF_8);
        String boot = "<script>window.__POWERCMS_SITE_ID__=\"" + escape(siteId)
            + "\";window.__POWERCMS_SITE_KEY__=\"" + escape(siteKey)
            + "\";window.__POWERCMS_API_BASE__=\"/api/v1\";</script>";
        // 统计采集片段紧跟启动配置、位于 </head> 之前：head 里的 defer 脚本先于 body 里的
        // 应用模块脚本执行，应用挂载时 window.powercmsAnalytics 已可用。
        String injected = boot + (analyticsTag == null ? "" : analyticsTag);
        int headClose = indexOfIgnoreCase(html, "</head>");
        String out = headClose >= 0
            ? html.substring(0, headClose) + injected + html.substring(headClose)
            : injected + html;
        return out.getBytes(StandardCharsets.UTF_8);
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static int indexOfIgnoreCase(String haystack, String needle) {
        return haystack.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT));
    }
}
