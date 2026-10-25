package com.powercms.cms.platform.i18n;

import com.powercms.cms.identity.AuthenticatedUserPrincipal;
import com.powercms.cms.identity.UserLanguageService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserLocaleFilterTest {

    @Mock UserLanguageService languages;
    @Mock HttpServletRequest request;
    @Mock HttpServletResponse response;
    @Mock FilterChain chain;

    private UserLocaleFilter filter;

    @BeforeEach
    void setUp() {
        filter = new UserLocaleFilter(languages);
        LocaleContextHolder.resetLocaleContext();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        LocaleContextHolder.resetLocaleContext();
        SecurityContextHolder.clearContext();
    }

    @Test
    void headerBeatsSession() throws Exception {
        when(request.getHeader(UserLocaleFilter.LANGUAGE_HEADER)).thenReturn("fr-FR");
        HttpSession session = mock(HttpSession.class);
        when(session.getAttribute(UserLocaleFilter.SESSION_LANGUAGE_ATTR)).thenReturn("ja-JP");
        when(request.getSession(false)).thenReturn(session);

        filter.doFilter(request, response, chain);

        verify(response).setHeader("Content-Language", "fr-FR");
        verify(chain).doFilter(request, response);
        assertThat(LocaleContextHolder.getLocale()).isEqualTo(Locale.getDefault());
    }

    @Test
    void sessionBeatsUserPreference() {
        when(request.getHeader(UserLocaleFilter.LANGUAGE_HEADER)).thenReturn(null);
        HttpSession session = mock(HttpSession.class);
        when(session.getAttribute(UserLocaleFilter.SESSION_LANGUAGE_ATTR)).thenReturn("ko-KR");
        when(request.getSession(false)).thenReturn(session);

        Locale resolved = UserLocaleFilter.resolve(request, languages);
        assertThat(SupportedLocales.tag(resolved)).isEqualTo("ko-KR");
    }

    @Test
    void userPreferenceBeatsAcceptLanguage() {
        when(request.getHeader(UserLocaleFilter.LANGUAGE_HEADER)).thenReturn(null);
        when(request.getSession(false)).thenReturn(null);
        UUID userId = UUID.randomUUID();
        authenticate(userId);
        when(languages.localeOf(userId)).thenReturn(Locale.forLanguageTag("ja-JP"));
        when(request.getHeader("Accept-Language")).thenReturn("en-US");

        Locale resolved = UserLocaleFilter.resolve(request, languages);
        assertThat(SupportedLocales.tag(resolved)).isEqualTo("ja-JP");
    }

    @Test
    void acceptLanguageBeatsOrganizationDefault() {
        when(request.getHeader(UserLocaleFilter.LANGUAGE_HEADER)).thenReturn(null);
        when(request.getSession(false)).thenReturn(null);
        when(request.getHeader("Accept-Language")).thenReturn("es-MX,en;q=0.8");
        when(languages.organizationDefaultLanguage()).thenReturn("zh-CN");

        Locale resolved = UserLocaleFilter.resolve(request, languages);
        assertThat(SupportedLocales.tag(resolved)).isEqualTo("es-ES");
    }

    @Test
    void organizationDefaultThenZhCn() {
        when(request.getHeader(UserLocaleFilter.LANGUAGE_HEADER)).thenReturn(null);
        when(request.getSession(false)).thenReturn(null);
        when(request.getHeader("Accept-Language")).thenReturn(null);
        when(languages.organizationDefaultLanguage()).thenReturn("en");

        Locale resolved = UserLocaleFilter.resolve(request, languages);
        assertThat(SupportedLocales.tag(resolved)).isEqualTo("en-US");

        when(languages.organizationDefaultLanguage()).thenReturn(null);
        Locale fallback = UserLocaleFilter.resolve(request, languages);
        assertThat(SupportedLocales.tag(fallback)).isEqualTo("zh-CN");
    }

    @Test
    void invalidHeaderIsIgnored() {
        when(request.getHeader(UserLocaleFilter.LANGUAGE_HEADER)).thenReturn("de-DE");
        when(request.getSession(false)).thenReturn(null);
        when(request.getHeader("Accept-Language")).thenReturn(null);
        when(languages.organizationDefaultLanguage()).thenReturn(null);

        Locale resolved = UserLocaleFilter.resolve(request, languages);
        assertThat(SupportedLocales.tag(resolved)).isEqualTo("zh-CN");
    }

    @Test
    void skipsMediaActuatorAndDocsPaths() {
        when(request.getRequestURI()).thenReturn("/media-play");
        assertThat(filter.shouldNotFilter(request)).isTrue();
        when(request.getRequestURI()).thenReturn("/actuator/health");
        assertThat(filter.shouldNotFilter(request)).isTrue();
        when(request.getRequestURI()).thenReturn("/swagger-ui/index.html");
        assertThat(filter.shouldNotFilter(request)).isTrue();
        when(request.getRequestURI()).thenReturn("/api/v1/me/language");
        assertThat(filter.shouldNotFilter(request)).isFalse();
    }

    private static void authenticate(UUID userId) {
        var details = User.withUsername("tester").password("x").authorities("ROLE_USER").build();
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(
                new AuthenticatedUserPrincipal(userId, details),
                "x", details.getAuthorities()));
    }
}
