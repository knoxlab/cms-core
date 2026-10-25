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
 * FR-AGT-FLOW-001：一次性内容预览 Token——绑定站点/内容/版本、短时效、一次性消费。
 */
class ContentPreviewTokenServiceTest {

    private final UUID siteId = UUID.randomUUID();
    private final UUID contentId = UUID.randomUUID();

    private ContentPreviewTokenService tokens;

    @BeforeEach
    void setUp() {
        PreviewTokenService signer = new PreviewTokenService(mock(ContentItemRepository.class));
        signer.setSigningSecret("unit-test-preview-secret-at-least-32-chars-long");
        tokens = new ContentPreviewTokenService(signer, 1800, "https://cms.example.com/");
    }

    @Test
    void issuesTokenBindingSiteContentAndVersion() {
        ContentPreviewTokenService.Issued issued = tokens.issue(siteId, contentId, 3);
        ContentPreviewTokenService.Target target = tokens.validate(issued.token());
        assertThat(target.siteId()).isEqualTo(siteId);
        assertThat(target.contentId()).isEqualTo(contentId);
        assertThat(target.version()).isEqualTo(3);
        assertThat(issued.expiresAt()).isAfter(java.time.Instant.now());
    }

    @Test
    void previewUrlIsAbsoluteAndIdempotentForSameToken() {
        ContentPreviewTokenService.Issued issued = tokens.issue(siteId, contentId, 1);
        assertThat(tokens.previewUrl(issued.token()))
            .isEqualTo("https://cms.example.com/api/v1/preview/content-item?token=" + issued.token());
    }

    @Test
    void tokenIsConsumedExactlyOnce() {
        String token = tokens.issue(siteId, contentId, 1).token();
        assertThat(tokens.consume(token).contentId()).isEqualTo(contentId);
        assertThatThrownBy(() -> tokens.consume(token))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("已使用");
        assertThat(tokens.consumedCount()).isEqualTo(1);
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = tokens.issue(siteId, contentId, 1).token();
        String tampered = token.substring(0, token.length() - 2) + "xy";
        assertThatThrownBy(() -> tokens.validate(tampered))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("无效");
    }

    @Test
    void anotherSecretCannotValidate() {
        String token = tokens.issue(siteId, contentId, 1).token();
        PreviewTokenService other = new PreviewTokenService(mock(ContentItemRepository.class));
        other.setSigningSecret("another-preview-secret-also-at-least-32-chars");
        ContentPreviewTokenService otherService = new ContentPreviewTokenService(other, 1800, "http://localhost");
        assertThatThrownBy(() -> otherService.validate(token)).isInstanceOf(BusinessException.class);
    }

    @Test
    void ttlIsClampedToSafeRange() {
        PreviewTokenService signer = new PreviewTokenService(mock(ContentItemRepository.class));
        signer.setSigningSecret("unit-test-preview-secret-at-least-32-chars-long");
        assertThat(new ContentPreviewTokenService(signer, 1, "http://localhost").ttlSeconds()).isEqualTo(30);
        assertThat(new ContentPreviewTokenService(signer, 999_999, "http://localhost").ttlSeconds()).isEqualTo(86400);
    }
}
