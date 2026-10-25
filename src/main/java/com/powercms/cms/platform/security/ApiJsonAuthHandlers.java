package com.powercms.cms.platform.security;

import com.powercms.cms.platform.i18n.Messages;
import com.powercms.cms.platform.i18n.SupportedLocales;
import com.powercms.cms.platform.i18n.UserLocaleFilter;
import com.powercms.common.error.ErrorCodes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.context.MessageSource;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.util.Locale;
import java.util.UUID;

/**
 * 认证/授权 JSON 响应（FR-I18N-002）：消息按请求语言本地化。
 * 401/403 在 Security 链（order -100）内渲染并短路后续过滤器，
 * {@code UserLocaleFilter}（order 90）尚未执行，因此按过滤器同一规则
 * 直接从请求解析语言，不依赖 {@code LocaleContextHolder}。
 */
final class ApiJsonAuthHandlers {

    /**
     * RFC 6750 §3 挑战头：未携带（或未提供）凭证。
     */
    static final String BEARER_CHALLENGE = "Bearer";

    /**
     * RFC 6750 §3.1 挑战头：携带了凭证但校验失败。
     * <p>OBS-MSRC-03：{@link ApiTokenAuthenticationFilter} 在请求携带 {@code Authorization: Bearer …}
     * 且 token 无效/过期时立即回 401（该行为与 Spring Security {@code BearerTokenAuthenticationFilter}
     * 及 RFC 6750 一致：凭证错误优先于路径是否公开，不区分 {@code permitAll} 与受保护路径）。
     * 此前 401 不带挑战头，调用方无法区分「这条路径需要认证」与「你带的 token 失效了」，
     * 故这里按标准补齐 {@code error="invalid_token"}，便于自助定位。</p>
     */
    static final String BEARER_INVALID_TOKEN_CHALLENGE = "Bearer error=\"invalid_token\"";

    /**
     * RFC 6750 §3.1：凭证**已过期**——同为 {@code invalid_token} 错误码，但错误描述点明过期，
     * 使调用方能把「该重新签发」与「令牌本身非法」区分开（TC-M04-004）。
     */
    static final String BEARER_EXPIRED_TOKEN_CHALLENGE =
        "Bearer error=\"invalid_token\", error_description=\"The access token expired\"";

    private ApiJsonAuthHandlers() {}

    static AuthenticationEntryPoint unauthenticated(MessageSource messageSource,
                                                    com.powercms.cms.identity.UserLanguageService languages) {
        Messages messages = new Messages(messageSource);
        return (request, response, ex) -> {
            Locale locale = localeOf(request, languages);
            write(request, response, 401, ErrorCodes.UNAUTHENTICATED, locale,
                messages.get("error.auth.unauthenticated", locale), BEARER_CHALLENGE);
        };
    }

    static AccessDeniedHandler forbidden(MessageSource messageSource,
                                         com.powercms.cms.identity.UserLanguageService languages) {
        Messages messages = new Messages(messageSource);
        return (request, response, ex) -> {
            Locale locale = localeOf(request, languages);
            write(request, response, 403, ErrorCodes.PERMISSION_DENIED, locale,
                messages.get("error.permission.denied", locale), null);
        };
    }

    /**
     * 未认证（会话/登录模式不可用于当前 API 等场景）：按「未提供可用凭证」口径回挑战头。
     */
    static void unauthenticated(HttpServletRequest request, HttpServletResponse response,
                                MessageSource messageSource,
                                com.powercms.cms.identity.UserLanguageService languages) throws IOException {
        unauthorize(request, response, messageSource, languages, BEARER_CHALLENGE);
    }

    /**
     * 携带的 token 校验失败（RFC 6750 {@code invalid_token}）。仅由 {@link ApiTokenAuthenticationFilter}
     * 在凭证被拒时调用；路径是否为 {@code permitAll} 不影响该语义。
     */
    static void invalidToken(HttpServletRequest request, HttpServletResponse response,
                             MessageSource messageSource,
                             com.powercms.cms.identity.UserLanguageService languages) throws IOException {
        unauthorize(request, response, messageSource, languages, BEARER_INVALID_TOKEN_CHALLENGE);
    }

    /**
     * 携带的 token **已过期**（TC-M04-004）：仍为 401，但消息与挑战头都点明「过期」，
     * 与「非法/伪造」区分开。仅由 {@link ApiTokenAuthenticationFilter} 在过期分支调用。
     */
    static void expiredToken(HttpServletRequest request, HttpServletResponse response,
                             MessageSource messageSource,
                             com.powercms.cms.identity.UserLanguageService languages) throws IOException {
        if (response.isCommitted()) return;
        Locale locale = localeOf(request, languages);
        write(request, response, 401, ErrorCodes.UNAUTHENTICATED, locale,
            new Messages(messageSource).get("error.auth.token.expired", locale),
            BEARER_EXPIRED_TOKEN_CHALLENGE);
    }

    private static void unauthorize(HttpServletRequest request, HttpServletResponse response,
                                    MessageSource messageSource,
                                    com.powercms.cms.identity.UserLanguageService languages,
                                    String challenge) throws IOException {
        Locale locale = localeOf(request, languages);
        write(request, response, 401, ErrorCodes.UNAUTHENTICATED, locale,
            new Messages(messageSource).get("error.auth.unauthenticated", locale), challenge);
    }

    private static Locale localeOf(HttpServletRequest request,
                                   com.powercms.cms.identity.UserLanguageService languages) {
        // 401/403 在 Security 链（order -100）内渲染，UserLocaleFilter（order 90）尚未执行；
        // LocaleContextHolder 此时只含 Tomcat 线程池继承来的脏状态（InheritableThreadLocal），
        // 不可信任，必须完全以当前请求为准解析。
        return UserLocaleFilter.resolve(request, languages);
    }

    private static void write(HttpServletRequest request, HttpServletResponse response,
                              int status, String code, Locale locale, String message,
                              String wwwAuthenticate)
        throws IOException {
        if (response.isCommitted()) return;
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Content-Language", SupportedLocales.tag(locale));
        if (wwwAuthenticate != null) {
            response.setHeader("WWW-Authenticate", wwwAuthenticate);
        }
        String traceId = MDC.get("traceId");
        if (traceId == null || traceId.isBlank()) traceId = UUID.randomUUID().toString();
        response.getWriter().write("{\"code\":\"" + json(code)
            + "\",\"message\":\"" + json(message)
            + "\",\"traceId\":\"" + json(traceId)
            + "\",\"details\":[]}");
    }

    private static String json(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
