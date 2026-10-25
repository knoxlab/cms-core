package com.powercms.cms.platform.web;

import com.powercms.cms.identity.AuthenticatedUserPrincipal;
import com.powercms.cms.identity.UserLanguageService;
import com.powercms.cms.identity.dto.UpdateLanguageRequest;
import com.powercms.cms.platform.i18n.I18nConfig;
import com.powercms.cms.platform.i18n.UserLocaleFilter;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserLanguageControllerTest {

    @Mock UserLanguageService languages;
    @Mock HttpServletRequest request;

    private UserLanguageController controller;

    @BeforeEach
    void setUp() {
        controller = new UserLanguageController(languages, new I18nConfig().messageSource());
        LocaleContextHolder.resetLocaleContext();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        LocaleContextHolder.resetLocaleContext();
        SecurityContextHolder.clearContext();
    }

    @Test
    void currentReturnsEffectiveLanguageAndSixOptions() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("ja-JP"));

        Map<String, Object> body = controller.current(request);

        assertThat(body.get("language")).isEqualTo("ja-JP");
        assertThat(body.get("default")).isEqualTo("zh-CN");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> available = (List<Map<String, String>>) body.get("available");
        assertThat(available).extracting(m -> m.get("tag"))
            .containsExactly("zh-CN", "en-US", "ja-JP", "ko-KR", "fr-FR", "es-ES");
        assertThat(available).allSatisfy(option -> assertThat(option.get("nativeName")).isNotBlank());
    }

    @Test
    void currentFallsBackToZhCnWhenHolderLocaleIsUnsupported() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("de-DE"));
        assertThat(controller.current(request).get("language")).isEqualTo("zh-CN");
    }

    @Test
    void updatePersistsPreferenceWritesSessionAndLocalizesMessage() {
        UUID userId = UUID.randomUUID();
        authenticate(userId);
        LocaleContextHolder.setLocale(Locale.forLanguageTag("en-US"));
        when(languages.updateLanguage(userId, "fr")).thenReturn("fr-FR");
        HttpSession session = mock(HttpSession.class);
        when(request.getSession(true)).thenReturn(session);

        ResponseEntity<Map<String, Object>> response =
            controller.update(new UpdateLanguageRequest("fr"), request);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).containsEntry("language", "fr-FR");
        assertThat(response.getBody().get("message").toString())
            .isEqualTo("Language preference saved: fr-FR");
        verify(session).setAttribute(UserLocaleFilter.SESSION_LANGUAGE_ATTR, "fr-FR");
    }

    @Test
    void updateWithoutPrincipalIsUnauthenticated() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("zh-CN"));
        assertThatThrownBy(() -> controller.update(new UpdateLanguageRequest("en-US"), request))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> {
                BusinessException be = (BusinessException) ex;
                assertThat(be.getCode()).isEqualTo(ErrorCodes.UNAUTHENTICATED);
                assertThat(be.getMessage()).isEqualTo("请先登录");
            });
    }

    private static void authenticate(UUID userId) {
        var details = User.withUsername("tester").password("x").authorities("ROLE_USER").build();
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(
                new AuthenticatedUserPrincipal(userId, details),
                "x", details.getAuthorities()));
    }
}
