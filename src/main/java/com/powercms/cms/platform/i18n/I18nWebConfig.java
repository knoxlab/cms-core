package com.powercms.cms.platform.i18n;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.servlet.LocaleResolver;

import java.util.Locale;

/**
 * i18n 装配：语言解析过滤器以普通 Servlet Filter 注册（order=90，晚于 Security 链），
 * 保证解析时认证上下文可用。资源包由 {@link I18nConfig} 提供。
 *
 * <p>同时注册同名 {@code localeResolver}（替换 Boot 默认的 AcceptHeaderLocaleResolver）：
 * FrameworkServlet 在进入控制器前会用 LocaleResolver 结果覆盖 LocaleContextHolder，
 * 若不覆盖同规则，UserLocaleFilter 设置的 X-Language/会话/用户偏好会在 servlet 层被
 * Accept-Language（或系统默认 zh_CN）冲掉。
 */
@Configuration
public class I18nWebConfig {

    private static final String RESOLVED_LOCALE_ATTR = I18nWebConfig.class.getName() + ".RESOLVED_LOCALE";

    @Bean
    public UserLocaleFilter userLocaleFilter(com.powercms.cms.identity.UserLanguageService languages) {
        return new UserLocaleFilter(languages);
    }

    @Bean
    public LocaleResolver localeResolver(com.powercms.cms.identity.UserLanguageService languages) {
        return new LocaleResolver() {
            @Override
            public Locale resolveLocale(HttpServletRequest request) {
                Object cached = request.getAttribute(RESOLVED_LOCALE_ATTR);
                if (cached instanceof Locale locale) return locale;
                Locale locale = UserLocaleFilter.resolve(request, languages);
                request.setAttribute(RESOLVED_LOCALE_ATTR, locale);
                return locale;
            }

            @Override
            public void setLocale(HttpServletRequest request, HttpServletResponse response, Locale locale) {
                LocaleContextHolder.setLocale(locale);
            }
        };
    }

    @Bean
    public FilterRegistrationBean<UserLocaleFilter> userLocaleFilterRegistration(UserLocaleFilter filter) {
        FilterRegistrationBean<UserLocaleFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/*");
        registration.setOrder(90);
        return registration;
    }
}

