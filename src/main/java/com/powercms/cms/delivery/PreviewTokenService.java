package com.powercms.cms.delivery;

import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/** Stateless, site-scoped preview tokens that can be verified on any application node. */
@Service
@Transactional(readOnly = true)
public class PreviewTokenService {
    private final ContentItemRepository itemRepo;
    private byte[] signingSecret;

    PreviewTokenService(ContentItemRepository itemRepo) { this.itemRepo = itemRepo; }

    @Value("${powercms.preview.token-secret:dev-only-change-this-preview-secret-32chars}")
    void setSigningSecret(String secret) {
        if (secret == null || secret.isBlank() || secret.length() < 32)
            throw new IllegalStateException("powercms.preview.token-secret must be at least 32 characters");
        signingSecret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public record PreviewToken(String token, Instant expiresAt) {}
    public record PreviewContent(UUID id, String title, String status, int currentVersion, Instant createdAt) {
        public static PreviewContent from(ContentItemEntity item) {
            return new PreviewContent(item.getId(), item.getTitle(), item.getStatus(), item.getCurrentVersion(), item.getCreatedAt());
        }
    }

    /** Mints a token with a fifteen-minute lifetime. */
    public PreviewToken generateToken(UUID siteId) {
        Instant expires = Instant.now().plusSeconds(900);
        String payload = siteId + "." + expires.getEpochSecond();
        return new PreviewToken(payload + "." + sign(payload), expires);
    }

    public String issueSubmissionToken(UUID siteId, String formCode, String audience, long ttlSeconds) {
        long exp = Instant.now().plusSeconds(Math.max(30, Math.min(ttlSeconds, 900))).getEpochSecond();
        String payload = "form." + siteId + "." + formCode + "." + audience + "." + exp;
        return payload + "." + sign(payload);
    }

    public boolean validateSubmissionToken(String token, UUID siteId, String formCode, String audience) {
        try {
            String[] p = token.split("\\.", -1);
            if (p.length != 6 || !"form".equals(p[0]) || !siteId.toString().equals(p[1]) || !formCode.equals(p[2]) || !audience.equals(p[3])) return false;
            String payload = String.join(".", java.util.Arrays.copyOf(p, 5));
            return Long.parseLong(p[4]) > Instant.now().getEpochSecond() && MessageDigest.isEqual(p[5].getBytes(StandardCharsets.US_ASCII), sign(payload).getBytes(StandardCharsets.US_ASCII));
        } catch (Exception ex) { return false; }
    }

    public UUID validateToken(String token) {
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3) throw new IllegalArgumentException();
            String payload = parts[0] + "." + parts[1];
            if (!MessageDigest.isEqual(parts[2].getBytes(StandardCharsets.US_ASCII), sign(payload).getBytes(StandardCharsets.US_ASCII)))
                throw new IllegalArgumentException();
            if (!Instant.ofEpochSecond(Long.parseLong(parts[1])).isAfter(Instant.now())) throw new IllegalArgumentException();
            return UUID.fromString(parts[0]);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "预览 Token 无效或已过期");
        }
    }

    public List<PreviewContent> listDraftContent(String token) {
        return itemRepo.findBySiteIdAndStatus(validateToken(token), "DRAFT").stream().map(PreviewContent::from).toList();
    }

    /** HMAC-SHA256 签名（含 {@link ContentPreviewTokenService} 的一次性内容预览 Token 复用）。 */
    String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) { throw new IllegalStateException("Unable to sign preview token", ex); }
    }
}
