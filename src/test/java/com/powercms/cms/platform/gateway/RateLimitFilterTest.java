package com.powercms.cms.platform.gateway;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RateLimitFilterTest {

    @Mock HttpServletRequest request;
    @Mock HttpServletResponse response;
    @Mock FilterChain chain;

    @AfterEach
    void tearDown() {
        RegistryHolder.set(null);
    }

    private static org.springframework.context.support.StaticMessageSource testMessageSource() {
        org.springframework.context.support.StaticMessageSource source =
            new org.springframework.context.support.StaticMessageSource();
        source.addMessage("error.rate.limited", java.util.Locale.forLanguageTag("zh-CN"),
            "请求过于频繁，请稍后重试");
        return source;
    }

    private void publicDeliveryUri() {
        when(request.getRequestURI()).thenReturn("/api/v1/delivery/sites/x/contents");
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
    }

    @Test
    void allowsRequestsUnderLimitThenBlocksWith429AndRetryAfter() throws Exception {
        publicDeliveryUri();
        StringWriter sink = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(sink));
        RateLimitFilter filter = new RateLimitFilter(2, testMessageSource());

        filter.doFilter(request, response, chain);
        filter.doFilter(request, response, chain);
        verify(chain, times(2)).doFilter(request, response);

        filter.doFilter(request, response, chain);
        verify(response).setStatus(429);
        verify(response).setHeader("Retry-After", "60");
        assertThat(sink.toString()).contains("RATE_LIMITED");
        verify(chain, times(2)).doFilter(any(), any());
    }

    @Test
    void windowEvictsHitsOlderThanSixtySeconds() {
        RateLimitFilter.Window window = new RateLimitFilter.Window();
        long now = System.currentTimeMillis();
        assertThat(window.tryAcquire(now - RateLimitFilter.WINDOW_MS - 1, 1)).isTrue();
        assertThat(window.expired(now)).isTrue();
        assertThat(window.tryAcquire(now, 1)).isTrue();
        assertThat(window.size(now)).isEqualTo(1);
    }

    @Test
    void nonPublicPathsAreNotFiltered() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/v1/sites/x/contents");
        new RateLimitFilter(1, testMessageSource()).doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
    }

    @Test
    void graphqlPathIsRateLimited() throws Exception {
        when(request.getRequestURI()).thenReturn("/graphql");
        when(request.getRemoteAddr()).thenReturn("10.0.0.7");
        StringWriter sink = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(sink));
        FilterChain spyChain = mock(FilterChain.class);
        RateLimitFilter filter = new RateLimitFilter(1, testMessageSource());

        filter.doFilter(request, response, spyChain);
        filter.doFilter(request, response, spyChain);

        verify(response).setStatus(429);
        verify(spyChain, times(1)).doFilter(any(), any());
    }
}
