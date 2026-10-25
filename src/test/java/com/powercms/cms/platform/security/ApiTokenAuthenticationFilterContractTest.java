package com.powercms.cms.platform.security;

import com.powercms.cms.identity.AuthenticatedUserPrincipal;
import com.powercms.cms.identity.ApiTokenService;
import com.powercms.cms.identity.OAuthTokenService;
import com.powercms.cms.identity.UserLanguageService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OBS-MSRC-03 回归：{@code Authorization: Bearer …} 的**语义归属**。
 *
 * <p>观察：数据源同步把 {@code Authorization} 头发到 CMS 自身的 {@code permitAll} 静态路径
 * （{@code /assets/**}）时拿到 401，一度被怀疑是"permitAll 失灵"。定位结论：{@link ApiTokenAuthenticationFilter}
 * 是 **Servlet 过滤器**，在授权判定之前执行；只要请求带了 Bearer 凭证而凭证校验失败，就直接 401，
 * 与路径是否 {@code permitAll} 无关。这与 Spring Security 的 {@code BearerTokenAuthenticationFilter}
 * 及 RFC 6750（凭证错误优先，401 + {@code WWW-Authenticate}）一致，属**有意行为**而非缺陷。</p>
 *
 * <p>本用例把该语义钉死，并要求 401 携带 {@code error="invalid_token"} 挑战头，使调用方能区分
 * 「本路径需要认证」与「手里的 token 失效了」（此前两者响应完全一致，是这次误判的直接原因）。</p>
 */
class ApiTokenAuthenticationFilterContractTest {

    private static ApiTokenAuthenticationFilter filter(ApiTokenService tokens, ObjectProvider<OAuthTokenService> oauth) {
        StaticMessageSource messages = new StaticMessageSource();
        messages.addMessage("error.auth.unauthenticated", java.util.Locale.SIMPLIFIED_CHINESE, "认证失败");
        return new ApiTokenAuthenticationFilter(tokens, oauth, new com.fasterxml.jackson.databind.ObjectMapper(),
            messages, mock(UserLanguageService.class));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<OAuthTokenService> noOAuth() {
        ObjectProvider<OAuthTokenService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @Test
    void invalidBearerOnPublicStaticPathStillFailsWithInvalidTokenChallenge() throws Exception {
        ApiTokenService tokens = mock(ApiTokenService.class);
        when(tokens.authenticate(anyString(), any())).thenReturn(Optional.empty());
        ApiTokenAuthenticationFilter filter = filter(tokens, noOAuth());

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/assets/nested/chunk.js");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer definitely-not-a-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate"))
            .isEqualTo(ApiJsonAuthHandlers.BEARER_INVALID_TOKEN_CHALLENGE);
        assertThat(response.getContentAsString()).contains("\"code\":\"UNAUTHENTICATED\"");
        // 凭证被拒时不进入后续链（授权判定根本没机会执行——这正是 permitAll 路径也 401 的机制）。
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void missingAuthorizationHeaderPassesThroughSoPublicPathsStayPublic() throws Exception {
        ApiTokenService tokens = mock(ApiTokenService.class);
        ApiTokenAuthenticationFilter filter = filter(tokens, noOAuth());

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/assets/nested/chunk.js");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("WWW-Authenticate")).isNull();
        // 无凭证时不得去查 token（避免把公开路径变成鉴权路径）。
        verify(tokens, never()).authenticate(anyString(), any());
    }

    @Test
    void validBearerAuthenticatesAndContinues() throws Exception {
        SecurityContextHolder.clearContext();
        ApiTokenService tokens = mock(ApiTokenService.class);
        AuthenticatedUserPrincipal principal = new AuthenticatedUserPrincipal(
            java.util.UUID.randomUUID(),
            User.withUsername("tester").password("n/a").authorities("ROLE_ADMIN").build());
        when(tokens.authenticate(anyString(), any())).thenReturn(Optional.of(principal));
        ApiTokenAuthenticationFilter filter = filter(tokens, noOAuth());

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/contents");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer pcm_valid");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(any(), any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        SecurityContextHolder.clearContext();
    }

    /**
     * 挑战头两级语义：未携带凭证 vs 携带但被拒。
     */
    @Test
    void challengeHeadersDistinguishMissingFromRejected() throws Exception {
        org.springframework.context.support.StaticMessageSource messages =
            new org.springframework.context.support.StaticMessageSource();
        messages.addMessage("error.auth.unauthenticated", java.util.Locale.SIMPLIFIED_CHINESE, "认证失败");
        UserLanguageService languages = mock(UserLanguageService.class);

        MockHttpServletResponse entryPointResponse = new MockHttpServletResponse();
        ApiJsonAuthHandlers.unauthenticated(messages, languages)
            .commence(new MockHttpServletRequest("GET", "/api/v1/contents"), entryPointResponse, null);
        assertThat(entryPointResponse.getHeader("WWW-Authenticate"))
            .isEqualTo(ApiJsonAuthHandlers.BEARER_CHALLENGE);

        MockHttpServletResponse rejectedResponse = new MockHttpServletResponse();
        ApiJsonAuthHandlers.invalidToken(new MockHttpServletRequest("GET", "/assets/x.js"),
            rejectedResponse, messages, languages);
        assertThat(rejectedResponse.getHeader("WWW-Authenticate"))
            .isEqualTo(ApiJsonAuthHandlers.BEARER_INVALID_TOKEN_CHALLENGE);
    }

    @Test
    void forbiddenResponseCarriesNoBearerChallenge() throws Exception {
        org.springframework.context.support.StaticMessageSource messages =
            new org.springframework.context.support.StaticMessageSource();
        messages.addMessage("error.permission.denied", java.util.Locale.SIMPLIFIED_CHINESE, "无权限");
        MockHttpServletResponse response = new MockHttpServletResponse();

        ApiJsonAuthHandlers.forbidden(messages, mock(UserLanguageService.class))
            .handle(new MockHttpServletRequest("GET", "/api/v1/contents"), response, null);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader("WWW-Authenticate")).isNull();
    }
}
