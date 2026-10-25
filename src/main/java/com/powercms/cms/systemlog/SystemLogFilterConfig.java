package com.powercms.cms.systemlog;

import com.powercms.cms.platform.web.TraceIdFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 系统日志过滤器装配（FR-LOG-TR-001 / FR-LOG-AC-001）：
 * <ul>
 *   <li>{@link TraceIdFilter}：order = {@code Ordered.HIGHEST_PRECEDENCE + 1}，链路最前端，
 *       早于 Security 链与 {@code UserLocaleFilter}（order=90）；</li>
 *   <li>{@link AccessLogFilter}：order = 10，在 traceId 注入后、业务链之前包住整个请求周期，
 *       {@code finally} 中拿到最终状态码与耗时。</li>
 * </ul>
 */
@Configuration
public class SystemLogFilterConfig {

    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilterRegistration(TraceIdFilter filter) {
        FilterRegistrationBean<TraceIdFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/*");
        // FR-LOG-TR-001：注册顺序最早（HIGHEST_PRECEDENCE），先于 UserLocaleFilter(90)
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }

    @Bean
    public TraceIdFilter traceIdFilter() {
        return new TraceIdFilter();
    }

    @Bean
    public AccessLogFilter accessLogFilter(SystemLogPipeline pipeline, SystemLogProperties props) {
        return new AccessLogFilter(pipeline, props);
    }

    @Bean
    public FilterRegistrationBean<AccessLogFilter> accessLogFilterRegistration(AccessLogFilter filter) {
        FilterRegistrationBean<AccessLogFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/*");
        registration.setOrder(10);
        return registration;
    }
}
