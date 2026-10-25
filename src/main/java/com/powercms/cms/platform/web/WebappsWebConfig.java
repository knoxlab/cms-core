package com.powercms.cms.platform.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * 站点应用（{@code /webapps/**}）相关的 Web 基础设施注册。
 */
@Configuration
public class WebappsWebConfig {

    /**
     * 注册 API 路径改写过滤器：必须先于 {@code springSecurityFilterChain}（顺序 -100）执行，
     * 使 {@code /webapps/{siteKey}/api/**} 的请求以 {@code /api/**} 身份通过安全匹配。
     */
    @Bean
    @Order(-200)
    public FilterRegistrationBean<WebappsApiRewriteFilter> webappsApiRewriteFilter() {
        var registration = new FilterRegistrationBean<>(new WebappsApiRewriteFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(-200);
        registration.setName("webappsApiRewriteFilter");
        return registration;
    }
}
