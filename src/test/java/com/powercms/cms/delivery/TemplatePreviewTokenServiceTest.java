package com.powercms.cms.delivery;

import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.common.error.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * FR-AGT-TPL-004：一次性模板预览 Token——绑定站点与模板快照、短时效、一次性消费、服务端签名。
 */
class TemplatePreviewTokenServiceTest {

    private final UUID siteId = UUID.randomUUID();

    private TemplatePreviewTokenService tokens;

    @BeforeEach
    void setUp() {
        PreviewTokenService signer = new PreviewTokenService(mock(ContentItemRepository.class));
        signer.setSigningSecret("unit-test-preview-secret-at-least-32-chars-long");
        tokens = new TemplatePreviewTokenService(signer, 1800, "https://cms.example.com/");
    }

    @Test
    void issuesTokenBindingSiteAndSnapshot() {
        TemplatePreviewTokenService.Issued issued =
            tokens.issue(siteId, "detail.ftl", "<h1>${(content.title)!''}</h1>", "{\"content\":{\"title\":\"Hi\"}}");

        assertThat(tokens.validate(issued.token())).isTrue();
        TemplatePreviewTokenService.Snapshot snapshot = tokens.consume(issued.token());
        assertThat(snapshot.siteId()).isEqualTo(siteId);
        assertThat(snapshot.templateName()).isEqualTo("detail.ftl");
        assertThat(snapshot.source()).contains("content.title");
        assertThat(snapshot.sampleDataJson()).contains("Hi");
        assertThat(issued.expiresAt()).isAfter(java.time.Instant.now());
    }

    @Test
    void previewUrlIsAbsoluteAndStableForSameToken() {
        TemplatePreviewTokenService.Issued issued =
            tokens.issue(siteId, "list.ftl", "<p>ok</p>", null);
        assertThat(tokens.previewUrl(issued.token()))
            .isEqualTo("https://cms.example.com/api/v1/preview/template?token=" + issued.token());
    }

    @Test
    void tokenIsConsumedExactlyOnce() {
        String token = tokens.issue(siteId, "list.ftl", "<p>ok</p>", null).token();
        assertThat(tokens.consume(token).templateName()).isEqualTo("list.ftl");
        assertThatThrownBy(() -> tokens.consume(token))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("无效或已过期");
        assertThat(tokens.pendingCount()).isZero();
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = tokens.issue(siteId, "list.ftl", "<p>ok</p>", null).token();
        String tampered = token.substring(0, token.length() - 2) + "xy";
        assertThat(tokens.validate(tampered)).isFalse();
        assertThatThrownBy(() -> tokens.consume(tampered)).isInstanceOf(BusinessException.class);
        assertThat(tokens.pendingCount()).isEqualTo(1);
    }

    @Test
    void anotherSecretCannotValidate() {
        String token = tokens.issue(siteId, "list.ftl", "<p>ok</p>", null).token();
        PreviewTokenService other = new PreviewTokenService(mock(ContentItemRepository.class));
        other.setSigningSecret("another-preview-secret-also-at-least-32-chars");
        TemplatePreviewTokenService otherService =
            new TemplatePreviewTokenService(other, 1800, "http://localhost");
        assertThat(otherService.validate(token)).isFalse();
    }

    @Test
    void ttlIsClampedToSafeRange() {
        PreviewTokenService signer = new PreviewTokenService(mock(ContentItemRepository.class));
        signer.setSigningSecret("unit-test-preview-secret-at-least-32-chars-long");
        assertThat(new TemplatePreviewTokenService(signer, 1, "http://localhost").ttlSeconds()).isEqualTo(30);
        assertThat(new TemplatePreviewTokenService(signer, 999_999, "http://localhost").ttlSeconds()).isEqualTo(86400);
    }
}
