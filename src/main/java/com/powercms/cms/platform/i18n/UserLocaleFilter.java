package com.powercms.cms.platform.i18n;

import com.powercms.cms.identity.AuthenticatedUserPrincipal;
import com.powercms.cms.identity.UserLanguageService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;

/**
 * 请求语言解析（FR-I18N-003），优先级从高到低：
 * <ol>
 *   <li>{@code X-Language} 请求头 —— 前端显式指定（管理端语言切换器，受白名单约束）</li>
 *   <li>会话属性 {@code language} —— PUT /me/language 后记住的本浏览器偏好</li>
 *   <li>登录用户 {@code sys_user.language} 持久化偏好</li>
 *   <li>{@code Accept-Language} —— 浏览器语言，取白名单内首个匹配项</li>
 *   <li>组织默认语言 {@code organization_settings.default_language}</li>
 * </ol>
 * 均未命中回落 zh-CN。结果写入 {@link LocaleContextHolder} 供业务/异常处理使用，
 * 并反射到响应头 {@code Content-Language}。Security 链内 401/403 由
 * {@code ApiJsonAuthHandlers} 按相同规则自解析，不依赖本过滤器顺序。
 */
public class UserLocaleFilter extends OncePerRequestFilter {

    /** 请求级显式语言头（受控白名单，避免开放任意 locale 导致资源包探测）。 */
    public static final String LANGUAGE_HEADER = "X-Language";

    /** 会话内记住的语言标签；PUT /me/language 成功后写入。 */
    public static final String SESSION_LANGUAGE_ATTR = UserLanguageService.class.getName() + ".LANGUAGE";

    private static final String MDC_LOCALE = "locale";

    private final UserLanguageService languages;

    public UserLocaleFilter(UserLanguageService languages) {
        this.languages = languages;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path != null && (path.startsWith("/media-") || path.startsWith("/actuator")
            || path.startsWith("/mvc/") || path.startsWith("/v3/api-docs") || path.startsWith("/swagger-ui"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        try {
            Locale locale = resolve(request);
            LocaleContextHolder.setLocale(locale);
            response.setHeader("Content-Language", SupportedLocales.tag(locale));
            MDC.put(MDC_LOCALE, SupportedLocales.tag(locale));
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_LOCALE);
            LocaleContextHolder.resetLocaleContext();
        }
    }

    /** 任意请求（含无 Spring 上下文的场景）按相同规则解析语言，供 Security 处理器复用。 */
    public static Locale resolve(HttpServletRequest request, UserLanguageService languages) {
        String header = request.getHeader(LANGUAGE_HEADER);
        if (StringUtils.hasText(header)) {
            Locale parsed = SupportedLocales.parse(header);
            if (parsed != null) return parsed;
        }
        if (request.getSession(false) != null) {
            Object remembered = request.getSession(false).getAttribute(SESSION_LANGUAGE_ATTR);
            if (remembered instanceof String tag) {
                Locale parsed = SupportedLocales.parse(tag);
                if (parsed != null) return parsed;
            }
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
            && authentication.getPrincipal() instanceof AuthenticatedUserPrincipal principal) {
            Locale preference = languages.localeOf(principal.getUserId());
            if (preference != null) return preference;
        }
        Locale accepted = acceptedLanguage(request);
        if (accepted != null) return accepted;
        return SupportedLocales.parseOrDefault(
            languages != null ? languages.organizationDefaultLanguage() : null);
    }

    private Locale resolve(HttpServletRequest request) {
        return resolve(request, languages);
    }

    private static Locale acceptedLanguage(HttpServletRequest request) {
        String header = request.getHeader("Accept-Language");
        if (!StringUtils.hasText(header)) return null;
        for (String part : header.split(",")) {
            String candidate = part.split(";")[0].trim();
            Locale parsed = SupportedLocales.parse(candidate);
            if (parsed != null) return parsed;
        }
        return null;
    }
}
