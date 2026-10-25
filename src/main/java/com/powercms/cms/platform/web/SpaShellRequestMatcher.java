package com.powercms.cms.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Set;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * 管理端 SPA 壳请求判定（DEF-11-11）。
 *
 * <p>问题：admin-ui 是 React Router 单页应用，客户端路由（/login、/content、/interaction/review…）
 * 都由 index.html 承载。此前 SecurityConfig 只放行 {@code /}、{@code /index.html} 与静态资源，其余
 * 路径走 {@code anyRequest().authenticated()}，于是「未登录打开登录页书签」「会话过期后 F5 刷新深链」
 * 都返回 JSON 401（{@code ApiJsonAuthHandlers}）而不是前端页面；多段路由（如 /interaction/review）
 * 更因 catch-all 只匹配单段而直接 404。
 *
 * <p>判定规则（仅 GET + 浏览器导航，且不是受保护前缀）：
 * <ul>
 *   <li>方法为 GET；</li>
 *   <li>路径最后一段不含 {@code .}（带扩展名的请求属于静态资源，交由资源处理器）；</li>
 *   <li>首段不在 {@link #RESERVED_PREFIXES} 内（API、媒体、文档等真实后端端点前缀）；</li>
 *   <li>Accept 含 {@code text/html}（浏览器导航）或为通配/缺失（{@code *}）；</li>
 * </ul>
 *
 * <p>安全性：放行的只是前端壳 HTML，未授权用户拿到的仍是要求登录的 SPA；所有数据访问都是
 * {@code /api/**}，仍受原有鉴权与站点能力校验约束。
 */
public class SpaShellRequestMatcher implements RequestMatcher {

    /**
     * 由后端负责的路径前缀：这些前缀下的 GET 不是前端路由，不得回退为 SPA 壳，
     * 否则未知 API 路径会返回 HTML（应为 404），媒体/文档路径会被遮蔽。
     */
    public static final Set<String> RESERVED_PREFIXES = Set.of(
        "api", "graphql", "mvc", "oauth", "actuator", "ws", "assets",
        "media-preview", "media-thumb", "media-file",
        "webapps",
        "v3", "swagger-ui");

    /** 已由静态资源映射处理的文件（不参与 SPA 回退判定）。 */
    private static final Set<String> STATIC_FILES = Set.of("/index.html", "/favicon.svg", "/favicon.ico", "/vite.svg");

    @Override
    public boolean matches(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String accept = request.getHeader("Accept");
        if (accept != null && !accept.contains("text/html") && !accept.contains("*/*")) {
            return false;
        }
        return isShellPath(request.getRequestURI());
    }

    /** 路径是否为 SPA 壳（不含请求头/方法判断，供 MVC 转发复用）。 */
    public static boolean isShellPath(String uri) {
        if (uri == null || uri.isEmpty()) {
            return false;
        }
        String path = uri.startsWith("/") ? uri : "/" + uri;
        if (STATIC_FILES.contains(path)) {
            return false;
        }
        String last = path.substring(path.lastIndexOf('/') + 1);
        if (last.contains(".")) {
            return false;
        }
        String first = firstSegment(path);
        return !RESERVED_PREFIXES.contains(first.toLowerCase(Locale.ROOT));
    }

    /** 取路径首段（不含前导斜杠）；根路径返回空串。 */
    public static String firstSegment(String path) {
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        int slash = trimmed.indexOf('/');
        return slash < 0 ? trimmed : trimmed.substring(0, slash);
    }
}
