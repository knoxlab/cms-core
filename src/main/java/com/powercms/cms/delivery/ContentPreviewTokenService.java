package com.powercms.cms.delivery;

import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内容级一次性预览 Token（FR-AGT-FLOW-001）。
 *
 * <p>与站点级预览 Token 的区别：
 * <ul>
 *   <li><b>绑定内容与版本</b>：Token 载荷含 siteId/contentId/version，只能预览指定内容的指定版本；</li>
 *   <li><b>一次性</b>：消费后立即失效，重复访问返回 {@code FORBIDDEN}（避免预览链接外泄后被反复拉取）；</li>
 *   <li><b>短时效</b>：默认 30 分钟（{@code powercms.preview.content-token-ttl-seconds}，取值 30..86400 秒）；</li>
 *   <li>签名复用站点预览 Token 的 {@code powercms.preview.token-secret}，可在任意节点验证。</li>
 * </ul>
 */
@Service
public class ContentPreviewTokenService {

    /** 一次性预览 Token 载荷前缀。 */
    static final String PREFIX = "pcv";

    private final PreviewTokenService signer;
    private final long ttlSeconds;
    private final String publicBaseUrl;

    /** 已消费 Token → 失效时刻，过期后惰性回收（不入持久库，重启即全部失效）。 */
    private final Map<String, Instant> consumed = new ConcurrentHashMap<>();

    ContentPreviewTokenService(PreviewTokenService signer,
                               @Value("${powercms.preview.content-token-ttl-seconds:1800}") long ttlSeconds,
                               @Value("${powercms.preview.public-base-url:http://localhost:8080}") String publicBaseUrl) {
        this.signer = signer;
        this.ttlSeconds = Math.min(Math.max(ttlSeconds, 30), 86400);
        this.publicBaseUrl = publicBaseUrl == null || publicBaseUrl.isBlank()
            ? "" : publicBaseUrl.replaceAll("/+$", "");
    }

    /** Token 载荷：站点 + 内容 + 版本 + 失效时刻。 */
    public record Target(UUID siteId, UUID contentId, int version) {}

    /** 签发结果：Token 与失效时刻。 */
    public record Issued(String token, Instant expiresAt) {}

    /** 为指定内容的指定版本签发一次性预览 Token。 */
    public Issued issue(UUID siteId, UUID contentId, int version) {
        Instant expires = Instant.now().plusSeconds(ttlSeconds);
        String payload = PREFIX + "." + siteId + "." + contentId + "." + version + "." + expires.getEpochSecond();
        return new Issued(payload + "." + signer.sign(payload), expires);
    }

    /** 预览 URL（供 Agent 工具与接口返回，绝对地址便于直接打开）。 */
    public String previewUrl(String token) {
        return publicBaseUrl + "/api/v1/preview/content-item?token=" + token;
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    /**
     * 校验并一次性消费 Token：签名不符/过期/重复使用均抛 {@link BusinessException}（{@code FORBIDDEN}）。
     */
    public Target consume(String token) {
        Target target = validate(token);
        Instant previous = consumed.putIfAbsent(token, Instant.now().plusSeconds(ttlSeconds));
        purgeExpired();
        if (previous != null) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "预览链接已使用（一次性 Token），请重新获取");
        }
        return target;
    }

    /** 仅校验签名与时效，不消费（供测试与排障）。 */
    public Target validate(String token) {
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 6 || !PREFIX.equals(parts[0])) throw new IllegalArgumentException();
            String payload = String.join(".", java.util.Arrays.copyOf(parts, 5));
            if (!MessageDigest.isEqual(parts[5].getBytes(StandardCharsets.US_ASCII),
                    signer.sign(payload).getBytes(StandardCharsets.US_ASCII))) {
                throw new IllegalArgumentException();
            }
            if (Long.parseLong(parts[4]) <= Instant.now().getEpochSecond()) throw new IllegalArgumentException();
            return new Target(UUID.fromString(parts[1]), UUID.fromString(parts[2]), Integer.parseInt(parts[3]));
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "内容预览 Token 无效或已过期");
        }
    }

    /** 已消费 Token 数（含未过期，供测试与观测）。 */
    public int consumedCount() {
        return consumed.size();
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        Iterator<Map.Entry<String, Instant>> it = consumed.entrySet().iterator();
        while (it.hasNext()) {
            if (now.isAfter(it.next().getValue())) it.remove();
        }
    }
}
