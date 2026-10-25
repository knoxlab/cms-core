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
 * 模板级一次性预览 Token（FR-AGT-TPL-004）。
 *
 * <p>与内容级预览 Token（{@link ContentPreviewTokenService}）同构：
 * <ul>
 *   <li><b>绑定站点与模板快照</b>：Token 只引用服务端持有的模板源码与样例数据，不信任客户端回传内容；</li>
 *   <li><b>一次性</b>：消费即从内存移除，重复访问返回 {@code FORBIDDEN}；</li>
 *   <li><b>短时效</b>：默认 30 分钟（{@code powercms.preview.template-token-ttl-seconds}，取值 30..86400 秒）；</li>
 *   <li>签名复用站点预览 Token 的 {@code powercms.preview.token-secret}，可在任意节点验证。</li>
 * </ul>
 * 预览**不落库、不影响线上**：快照仅存内存，进程重启即全部失效。
 */
@Service
public class TemplatePreviewTokenService {

    /** 一次性模板预览 Token 载荷前缀。 */
    static final String PREFIX = "tpl";

    private final PreviewTokenService signer;
    private final long ttlSeconds;
    private final String publicBaseUrl;

    /** Token → 预览快照（含失效时刻），过期或消费后移除。 */
    private final Map<String, Stored> store = new ConcurrentHashMap<>();

    TemplatePreviewTokenService(PreviewTokenService signer,
                                @Value("${powercms.preview.template-token-ttl-seconds:1800}") long ttlSeconds,
                                @Value("${powercms.preview.public-base-url:http://localhost:8080}") String publicBaseUrl) {
        this.signer = signer;
        this.ttlSeconds = Math.min(Math.max(ttlSeconds, 30), 86400);
        this.publicBaseUrl = publicBaseUrl == null || publicBaseUrl.isBlank()
            ? "" : publicBaseUrl.replaceAll("/+$", "");
    }

    /** 预览快照：站点 + 模板名 + 源码 + 样例数据 JSON。 */
    public record Snapshot(UUID siteId, String templateName, String source, String sampleDataJson) {}

    /** 签发结果：Token 与失效时刻。 */
    public record Issued(String token, Instant expiresAt) {}

    private record Stored(Snapshot snapshot, Instant expiresAt) {}

    /** 为模板快照签发一次性预览 Token。 */
    public Issued issue(UUID siteId, String templateName, String source, String sampleDataJson) {
        purgeExpired();
        Instant expires = Instant.now().plusSeconds(ttlSeconds);
        String nonce = UUID.randomUUID().toString();
        String payload = PREFIX + "." + siteId + "." + nonce + "." + expires.getEpochSecond();
        String token = payload + "." + signer.sign(payload);
        store.put(token, new Stored(new Snapshot(siteId, templateName, source, sampleDataJson), expires));
        return new Issued(token, expires);
    }

    /** 预览 URL（绝对地址便于直接打开）。 */
    public String previewUrl(String token) {
        return publicBaseUrl + "/api/v1/preview/template?token=" + token;
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    /** 校验并一次性消费 Token：签名不符/过期/重复使用均抛 {@code FORBIDDEN}。 */
    public Snapshot consume(String token) {
        Stored stored = store.get(token);
        if (stored == null || !signatureValid(token)) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "模板预览 Token 无效或已过期");
        }
        store.remove(token);
        if (Instant.now().isAfter(stored.expiresAt())) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "模板预览 Token 无效或已过期");
        }
        return stored.snapshot();
    }

    /** 仅校验签名与在库状态，不消费（供测试与排障）。 */
    public boolean validate(String token) {
        Stored stored = store.get(token);
        return stored != null && !Instant.now().isAfter(stored.expiresAt()) && signatureValid(token);
    }

    /** 在库（未消费且未过期）快照数，供测试与观测。 */
    public int pendingCount() {
        purgeExpired();
        return store.size();
    }

    private boolean signatureValid(String token) {
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 5 || !PREFIX.equals(parts[0])) return false;
            String payload = String.join(".", java.util.Arrays.copyOf(parts, 4));
            return MessageDigest.isEqual(parts[4].getBytes(StandardCharsets.US_ASCII),
                signer.sign(payload).getBytes(StandardCharsets.US_ASCII));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        Iterator<Map.Entry<String, Stored>> it = store.entrySet().iterator();
        while (it.hasNext()) {
            if (now.isAfter(it.next().getValue().expiresAt())) it.remove();
        }
    }
}
