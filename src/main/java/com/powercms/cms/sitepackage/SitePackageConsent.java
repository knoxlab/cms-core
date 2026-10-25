package com.powercms.cms.sitepackage;

import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 导入确认令牌（FR-PACKAGE-005「用户未确认冲突策略不得导入」）。
 *
 * <p>令牌无状态：{@code base64url(packageDigest|strategy|expiresAt) + "." + hmac}。
 * 服务端不需要会话存储即可证明「运维人员已经看过该包的冲突报告并选择了策略」，
 * 且令牌绑定包摘要，换包/改包即失效。</p>
 */
final class SitePackageConsent {

    private static final String SEPARATOR = "|";

    private SitePackageConsent() {}

    static String issue(String packageDigest, String strategy, SitePackageProperties props) {
        long expiresAt = System.currentTimeMillis() + props.getConsentTtlSeconds() * 1000L;
        String payload = payload(packageDigest, strategy, expiresAt);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
            + "." + signature(payload, props);
    }

    /** 校验令牌并返回其中携带的策略；无效/过期/与当前包或策略不匹配时抛出 409。 */
    static String verify(String token, String packageDigest, String strategy, SitePackageProperties props) {
        if (token == null || token.isBlank()) {
            throw invalid("缺少冲突确认令牌（conflictsToken）");
        }
        int dot = token.lastIndexOf('.');
        if (dot <= 0) throw invalid("冲突确认令牌格式非法");
        String encoded = token.substring(0, dot);
        String mac = token.substring(dot + 1);
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw invalid("冲突确认令牌无法解码");
        }
        if (!SitePackageSignature.constantTimeEquals(mac, signature(payload, props))) {
            throw invalid("冲突确认令牌签名校验失败");
        }
        String[] parts = payload.split("\\" + SEPARATOR, -1);
        if (parts.length != 3) throw invalid("冲突确认令牌内容非法");
        long expiresAt;
        try {
            expiresAt = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            throw invalid("冲突确认令牌有效期非法");
        }
        if (System.currentTimeMillis() > expiresAt) {
            throw invalid("冲突确认令牌已过期，请重新预检");
        }
        if (!SitePackageSignature.constantTimeEquals(parts[0], packageDigest)) {
            throw invalid("冲突确认令牌与当前站点包不匹配（包内容已变化）");
        }
        if (strategy != null && !SitePackageSignature.constantTimeEquals(parts[1], strategy)) {
            throw invalid("冲突确认令牌与所选策略不一致");
        }
        return parts[1];
    }

    private static String payload(String packageDigest, String strategy, long expiresAt) {
        return packageDigest + SEPARATOR + strategy + SEPARATOR + expiresAt;
    }

    private static String signature(String payload, SitePackageProperties props) {
        String secret = props.getConsentSecret();
        if (secret == null || secret.isBlank()) {
            throw new BusinessException(ErrorCodes.INTERNAL_ERROR, "站点包确认令牌密钥未配置");
        }
        return SitePackageSignature.sign(java.util.Map.of("payload", payload), secret);
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCodes.SITE_PACKAGE_CONSENT_INVALID, message);
    }
}
