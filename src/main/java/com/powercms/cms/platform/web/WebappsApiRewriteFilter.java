package com.powercms.cms.platform.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 站点应用 API 路径透明改写（{@code /webapps/{siteKey}/api/** → /api/**}）。
 *
 * <p>站点应用部署在 {@code /webapps/{siteKey}/} 下（{@link SiteAppController}）。浏览器访问站点应用时，
 * 以应用基路径相对寻址的 API 调用（{@code fetch("api/v1/...")} 或 {@code new URL("api/v1/...", import.meta.url)}
 * 之类，最终落在 {@code /webapps/{siteKey}/api/...}）由本过滤器在<b>进入 Spring Security 之前</b>改写为
 * {@code /api/...}：请求因此获得与直接调用 {@code /api/**} 完全一致的鉴权、站点能力与限流语义，
 * 站点应用无需硬编码 API 根路径即可自动匹配。</p>
 *
 * <p>注册顺序 {@code -200}，先于 {@code springSecurityFilterChain}（{@code -100}），故安全匹配与
 * MVC 路由看到的都是改写后的路径。</p>
 */
public class WebappsApiRewriteFilter extends OncePerRequestFilter {

    /** {@code /webapps/{siteKey}/api} 或 {@code /webapps/{siteKey}/api/{rest}} */
    private static final Pattern SITE_APP_API = Pattern.compile("^/webapps/[^/]+/(api(?:/.*)?)$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String ctx = request.getContextPath() == null ? "" : request.getContextPath();
        String uri = request.getRequestURI();
        String path = (!ctx.isEmpty() && uri.startsWith(ctx)) ? uri.substring(ctx.length()) : uri;
        Matcher m = SITE_APP_API.matcher(path);
        if (!m.matches()) {
            chain.doFilter(request, response);
            return;
        }
        String rewritten = "/" + m.group(1); // /api 或 /api/...
        chain.doFilter(new RewrittenRequest(request, ctx + rewritten, rewritten), response);
    }

    /** 只覆盖路由相关方法的请求包装；其余原样委托。 */
    private static final class RewrittenRequest extends HttpServletRequestWrapper {
        private final String requestUri;
        private final String servletPath;

        RewrittenRequest(HttpServletRequest request, String requestUri, String servletPath) {
            super(request);
            this.requestUri = requestUri;
            this.servletPath = servletPath;
        }

        @Override public String getRequestURI() { return requestUri; }
        @Override public String getServletPath() { return servletPath; }
        @Override public String getPathInfo() { return null; }
    }
}
