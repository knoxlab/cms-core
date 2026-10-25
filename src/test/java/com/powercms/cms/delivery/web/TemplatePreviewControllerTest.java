package com.powercms.cms.delivery.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.delivery.FreeMarkerSandbox;
import com.powercms.cms.delivery.TemplatePreviewTokenService;
import com.powercms.common.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * FR-AGT-TPL-004：一次性模板预览端点——沙箱渲染样例数据、一次性消费、严格响应头。
 */
class TemplatePreviewControllerTest {

    private final TemplatePreviewTokenService tokens = mock(TemplatePreviewTokenService.class);
    private final FreeMarkerSandbox sandbox = new FreeMarkerSandbox();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private TemplatePreviewController controller(String allowedHost) {
        return new TemplatePreviewController(tokens, sandbox, objectMapper, allowedHost);
    }

    @Test
    void rendersSnapshotWithSampleDataAndStrictHeaders() {
        UUID siteId = UUID.randomUUID();
        when(tokens.consume("tok-1")).thenReturn(new TemplatePreviewTokenService.Snapshot(
            siteId, "detail.ftl", "<h1>${(content.title)!''}</h1>", "{\"content\":{\"title\":\"Hello\"}}"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("preview.example");

        var response = controller("preview.example").template("tok-1", request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("<h1>Hello</h1>");
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(response.getHeaders().getFirst("Content-Security-Policy")).startsWith("sandbox");
        assertThat(response.getHeaders().getFirst("X-Robots-Tag")).isEqualTo("noindex, nofollow");
        assertThat(response.getHeaders().getFirst("Referrer-Policy")).isEqualTo("no-referrer");
        verify(tokens).consume("tok-1");
    }

    @Test
    void missingSampleDataRendersWithEmptyModel() {
        when(tokens.consume("tok-2")).thenReturn(new TemplatePreviewTokenService.Snapshot(
            UUID.randomUUID(), "page.ftl", "<p>static</p>", null));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("preview.example");

        var response = controller("").template("tok-2", request);

        assertThat(response.getBody()).contains("<p>static</p>");
    }

    @Test
    void rejectsHostMismatchBeforeConsumingToken() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("evil.example");

        var response = controller("https://preview.example:8443").template("tok-3", request);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(tokens);
    }

    @Test
    void propagatesInvalidTokenAsForbidden() {
        when(tokens.consume("bad")).thenThrow(new BusinessException(
            com.powercms.common.error.ErrorCodes.FORBIDDEN, "模板预览 Token 无效或已过期"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("preview.example");

        assertThatThrownBy(() -> controller("").template("bad", request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("无效或已过期");
    }
}
