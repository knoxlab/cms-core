package com.powercms.cms.delivery.web;

import com.powercms.cms.delivery.PreviewTokenService;
import com.powercms.cms.delivery.PageRenderService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PreviewControllerSecurityTest {
    private final PreviewTokenService service = mock(PreviewTokenService.class);
    private final PageRenderService pageRenderer = mock(PageRenderService.class);

    @Test
    void tokenMintRequiresSiteReadPermission() throws Exception {
        PreAuthorize authorization = PreviewController.class
            .getDeclaredMethod("generateToken", java.util.UUID.class)
            .getAnnotation(PreAuthorize.class);
        assertThat(authorization).isNotNull();
        assertThat(authorization.value())
            .isEqualTo("@siteAuthorization.check(authentication, #siteId, 'read')");
    }

    @Test
    void contentResponseHasStrictHeadersAndAllowsConfiguredAdminOriginToFrame() {
        when(service.listDraftContent(anyString())).thenReturn(List.of());
        PreviewController controller = new PreviewController(
            service, "https://preview.example:8443", "https://admin.example:444");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("preview.example");

        var response = controller.listDraftContent("signed", request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(response.getHeaders().getFirst("Content-Security-Policy"))
            .isEqualTo("default-src 'none'; connect-src 'self'; frame-ancestors https://admin.example:444");
        assertThat(response.getHeaders()).doesNotContainKey("X-Frame-Options");
        assertThat(response.getHeaders().getFirst("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeaders().getFirst("Permissions-Policy"))
            .isEqualTo("camera=(), microphone=(), geolocation=()");
        assertThat(response.getHeaders().getFirst("X-Robots-Tag")).isEqualTo("noindex, nofollow");
    }

    @Test
    void pagePreviewAllowsThemeCssAndHttpsImagesWithoutLeakingTheSignedUrl() {
        var siteId = java.util.UUID.randomUUID();
        var pageId = java.util.UUID.randomUUID();
        when(service.validateToken("signed")).thenReturn(siteId);
        when(pageRenderer.renderPreview(siteId, "/", true))
            .thenReturn(new PageRenderService.RenderedPage(
                pageId, "/", "TravelLux", "templates/travel-home.ftl",
                "<html><body><main>Travel</main></body></html>", "DRAFT", 1));
        PreviewController controller = new PreviewController(
            service, pageRenderer, "", "https://admin.example:444");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("preview.example");

        var response = controller.page("signed", "/", "edit", request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst("Content-Security-Policy"))
            .isEqualTo(
                "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                    + "img-src 'self' https: data:; font-src 'self' data:; connect-src 'self'; "
                    + "object-src 'none'; base-uri 'none'; form-action 'self'; "
                    + "frame-ancestors https://admin.example:444");
        assertThat(response.getHeaders().getFirst("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.getBody())
            .contains("/api/v1/preview/tinymce/tinymce.min.js")
            .contains("/api/v1/preview/uve-bridge.js")
            .containsSubsequence("tinymce.min.js", "uve-bridge.js");
    }

    @Test
    void configuredPreviewUrlHostIsValidatedWithoutItsPort() {
        PreviewController controller = new PreviewController(
            service, "https://preview.example:8443", "'self'");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("other.example");

        assertThat(controller.listDraftContent("signed", request).getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(service);
    }
}
