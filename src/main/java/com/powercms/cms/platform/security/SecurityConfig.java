package com.powercms.cms.platform.security;

import com.powercms.cms.identity.IdentityProperties;
import com.powercms.cms.identity.UserLanguageService;
import com.powercms.cms.platform.web.SpaShellRequestMatcher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;

/**
 * Session cookie login is the primary management-plane auth; HTTP Basic remains optional for development.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final IdentityProperties identity;
    private final ApiTokenAuthenticationFilter tokenFilter;
    private final LoginModeFilter loginModeFilter;
    private final MessageSource messageSource;
    private final UserLanguageService languages;

    public SecurityConfig(ObjectProvider<IdentityProperties> identity,
                          ObjectProvider<ApiTokenAuthenticationFilter> tokenFilter,
                          ObjectProvider<LoginModeFilter> loginModeFilter,
                          MessageSource messageSource,
                          UserLanguageService languages) {
        this.identity = identity.getIfAvailable(IdentityProperties::new);
        this.tokenFilter = tokenFilter.getIfAvailable();
        this.loginModeFilter = loginModeFilter.getIfAvailable();
        this.messageSource = messageSource;
        this.languages = languages;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // DEF-M31-006：GraphQL 站点级端点位于 /graphql/sites/{siteId}，此前只放行了精确路径
            // /graphql，带路径变量的站点查询会被 CsrfFilter 以 403 PERMISSION_DENIED 拒绝（POST 无
            // CSRF token）。/graphql/** 同时覆盖 /graphql 与 /graphql/sites/**（GraphQL 端点自身
            // 无 Cookie 会话鉴权面，CSRF 防护对它没有实际增益）。
            .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**", "/graphql", "/graphql/**", "/oauth/**"))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .requestCache(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(ApiJsonAuthHandlers.unauthenticated(messageSource, languages))
                .accessDeniedHandler(ApiJsonAuthHandlers.forbidden(messageSource, languages)))
            .headers(headers -> headers
                .frameOptions(frameOptions -> frameOptions.disable())
                .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                    new NegatedRequestMatcher(new OrRequestMatcher(
                        PathPatternRequestMatcher.withDefaults().matcher(GET, "/api/v1/preview/page"),
                        PathPatternRequestMatcher.withDefaults().matcher(GET, "/api/v1/preview/template"),
                        PathPatternRequestMatcher.withDefaults().matcher(GET, "/media-preview/**")
                    )),
                    new XFrameOptionsHeaderWriter())))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/index.html", "/assets/**", "/favicon.svg", "/favicon.ico", "/vite.svg").permitAll()
                .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers("/api/v1/meta", "/api/v1/auth/me", "/api/v1/auth/status",
                    "/api/v1/organization/settings/public/branding").permitAll()
                .requestMatchers(POST,
                    "/api/v1/auth/login", "/api/v1/auth/logout",
                    "/api/v1/auth/backend/login", "/api/v1/auth/backend/logout",
                    "/api/v1/auth/frontend/login", "/api/v1/auth/frontend/logout").permitAll()
                .requestMatchers(POST, "/api/v1/sites/*/search/public/rebuild").hasRole("system-admin")
                .requestMatchers("/api/v1/delivery/render-cache/**").hasRole("system-admin")
                .requestMatchers("/api/v1/delivery/**", "/mvc/sites/**", "/graphql", "/graphql/sites/**").permitAll()
                .requestMatchers("/oauth/authorize").authenticated()
                .requestMatchers("/oauth/token", "/oauth/revoke").permitAll()
                .requestMatchers("/media-preview/**", "/media-thumb/**", "/media-file/**").permitAll()
                // 站点应用（React/Vue 前端产物按站点 Key 部署在 webapps/{siteKey}/）是匿名公开的站点前台，
                // 数据面仍走受鉴权约束的 /api/**（其 /webapps/{siteKey}/api/** 相对寻址由改写过滤器归一）。
                .requestMatchers("/webapps/**").permitAll()
                .requestMatchers(POST, "/api/v1/preview/token").authenticated()
                .requestMatchers(GET, "/api/v1/preview/content", "/api/v1/preview/content-item", "/api/v1/preview/page", "/api/v1/preview/template", "/api/v1/preview/uve-bridge.js", "/api/v1/preview/tinymce/**").permitAll()
                .requestMatchers("/api/v1/public/sites/*/forms/**").permitAll()
                // FR-SEARCH-004：访客检索（全文 / 过滤 / 排序 / 游标分页 / 建议）必须对**匿名访客**开放。
                // 放行的理由：只读、默认只含 PUBLISHED、按 publicIndex 白名单，
                // 敏感字段既不进访客索引也不作为可排序/过滤维度，可见性再由对象级 ACL 权威过滤。
                // 写面（重建索引）不在此列，仍收敛到 system-admin。
                .requestMatchers(GET,
                    "/api/v1/public/sites/*/search/capabilities",
                    "/api/v1/public/sites/*/search/suggest").permitAll()
                .requestMatchers("/ws/**").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                // DEF-11-11：管理端 SPA 客户端路由直达/刷新（GET 浏览器导航，非 API 前缀）放行，
                // 未登录时由前端跳转 /login；数据接口仍在 /api/** 下受鉴权与站点能力约束。
                .requestMatchers(new SpaShellRequestMatcher()).permitAll()
                .anyRequest().authenticated());
        if (identity.isHttpBasicEnabled()) {
            http.httpBasic(basic -> basic.authenticationEntryPoint(
                ApiJsonAuthHandlers.unauthenticated(messageSource, languages)));
        } else {
            http.httpBasic(AbstractHttpConfigurer::disable);
        }
        if (tokenFilter != null) {
            http.addFilterBefore(tokenFilter, UsernamePasswordAuthenticationFilter.class);
        }
        if (loginModeFilter != null) {
            http.addFilterBefore(loginModeFilter, UsernamePasswordAuthenticationFilter.class);
        }
        return http.build();
    }

    /**
     * Password storage follows FR-IAM-003 and uses the delegated bcrypt-compatible encoder.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
