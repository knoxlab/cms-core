package com.powercms.cms.platform.security;

import com.powercms.cms.identity.AuthenticatedUserPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginModeFilterTest {

    private final LoginModeFilter filter = new LoginModeFilter(
        new org.springframework.context.support.StaticMessageSource(),
        org.mockito.Mockito.mock(com.powercms.cms.identity.UserLanguageService.class));

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void frontendSessionBlockedFromManagementApi() throws Exception {
        HttpServletRequest request = request("/api/v1/users", true);
        HttpServletResponse response = responseWithWriter();
        FilterChain chain = mock(FilterChain.class);
        SecurityContextHolder.getContext().setAuthentication(sessionAuth());

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(response).setStatus(401);
    }

    @Test
    void frontendSessionAllowedToDeliveryApi() throws Exception {
        HttpServletRequest request = request("/api/v1/delivery/sites/s1/pages", true);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        SecurityContextHolder.getContext().setAuthentication(sessionAuth());

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void backendSessionPassesManagementApi() throws Exception {
        HttpServletRequest request = request("/api/v1/users", false);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        SecurityContextHolder.getContext().setAuthentication(sessionAuth());

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void unauthenticatedPasses() throws Exception {
        HttpServletRequest request = request("/api/v1/users", true);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void apiTokenPassesRegardlessOfSession() throws Exception {
        HttpServletRequest request = request("/api/v1/users", true);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        var details = User.withUsername("svc").password("x").authorities("ROLE_SYSTEM-ADMIN").build();
        var principal = new AuthenticatedUserPrincipal(
            java.util.UUID.randomUUID(), details, java.util.UUID.randomUUID(), null, Set.of("read"));
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(principal, "N/A", details.getAuthorities()));

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void frontendModeDetected() {
        HttpServletRequest request = request("/api/v1/users", true);
        assertThat(LoginModeFilter.isFrontendSession(request)).isTrue();
    }

    private static HttpServletResponse responseWithWriter() throws java.io.IOException {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new java.io.PrintWriter(new java.io.StringWriter()));
        return response;
    }

    private static HttpServletRequest request(String path, boolean frontendSession) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(path);
        if (frontendSession) {
            HttpSession session = mock(HttpSession.class);
            when(session.getAttribute("loginMode")).thenReturn("FRONTEND");
            when(request.getSession(false)).thenReturn(session);
        } else {
            when(request.getSession(false)).thenReturn(null);
        }
        return request;
    }

    private static UsernamePasswordAuthenticationToken sessionAuth() {
        var details = User.withUsername("tester").password("x").authorities("ROLE_USER").build();
        return UsernamePasswordAuthenticationToken.authenticated(
            new AuthenticatedUserPrincipal(java.util.UUID.randomUUID(), details),
            "x", details.getAuthorities());
    }
}
