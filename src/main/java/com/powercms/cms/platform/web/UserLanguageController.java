package com.powercms.cms.platform.web;

import com.powercms.cms.identity.AuthenticatedUserPrincipal;
import com.powercms.cms.identity.UserLanguageService;
import com.powercms.cms.identity.dto.UpdateLanguageRequest;
import com.powercms.cms.platform.i18n.SupportedLocales;
import com.powercms.cms.platform.i18n.UserLocaleFilter;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.context.MessageSource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.i18n.LocaleContextHolder;

/**
 * 用户语言自助服务（FR-I18N-004）：
 * <ul>
 *   <li>{@code GET /api/v1/me/language} —— 当前生效语言 + 可选语言列表</li>
 *   <li>{@code PUT /api/v1/me/language} —— 持久化个人语言偏好</li>
 * </ul>
 * 切换即时生效（写会话），下次登录仍生效（写 sys_user.language）。
 */
@RestController
@RequestMapping("/api/v1/me/language")
public class UserLanguageController {

    private final UserLanguageService languages;
    private final MessageSource messageSource;

    public UserLanguageController(UserLanguageService languages, MessageSource messageSource) {
        this.languages = languages;
        this.messageSource = messageSource;
    }

    @GetMapping
    Map<String, Object> current(HttpServletRequest request) {
        String effective = effectiveLanguage(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("language", effective);
        body.put("default", SupportedLocales.DEFAULT_TAG);
        body.put("available", SupportedLocales.SUPPORTED.stream()
            .map(locale -> Map.of("tag", SupportedLocales.tag(locale),
                "nativeName", nativeName(locale)))
            .toList());
        return body;
    }

    @PutMapping
    ResponseEntity<Map<String, Object>> update(@Valid @RequestBody UpdateLanguageRequest req,
                                               HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication != null
            && authentication.getPrincipal() instanceof AuthenticatedUserPrincipal principal)) {
            throw new BusinessException(ErrorCodes.UNAUTHENTICATED,
                new com.powercms.cms.platform.i18n.Messages(messageSource).get("error.auth.unauthenticated"));
        }
        UUID userId = principal.getUserId();
        String saved = languages.updateLanguage(userId, req.language());
        request.getSession(true).setAttribute(UserLocaleFilter.SESSION_LANGUAGE_ATTR, saved);
        return ResponseEntity.ok(Map.of(
            "language", saved,
            "message", new com.powercms.cms.platform.i18n.Messages(messageSource)
                .get("i18n.language.updated", saved)));
    }

    private String effectiveLanguage(HttpServletRequest request) {
        Locale context = LocaleContextHolder.getLocale();
        String tag = SupportedLocales.tag(context);
        return SupportedLocales.isSupported(tag) ? tag : SupportedLocales.DEFAULT_TAG;
    }

    private static String nativeName(Locale locale) {
        return locale.getDisplayName(locale);
    }
}
