package com.powercms.cms.sitepackage;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 站点包摘要与签名（FR-PACKAGE-002/004、§8.3「清单签名、文件摘要和导入报告必须关联同一 packageDigest」）。
 *
 * <p>签名对象是**规范化清单**（去掉 {@code signature} 字段后按键名递归排序的紧凑 JSON）；
 * 摘要对象是包内每个文件的 SHA-256。签名算法为 HMAC-SHA256 + {@code keyId} 引用的密钥，
 * 因此生产可替换为 KMS/证书体系而无需改动清单结构。</p>
 */
final class SitePackageSignature {

    static final String ALGORITHM = "hmac-sha256";
    static final String ALGORITHM_NONE = "none";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SitePackageSignature() {}

    /** 单个文件摘要：{@code sha256:<hex>}。 */
    static String fileDigest(byte[] data) {
        return "sha256:" + sha256Hex(data);
    }

    static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 规范化清单字符串：递归按键名排序的紧凑 JSON，数值统一为整型/浮点原始形式。
     * 签名与校验必须使用同一算法，否则跨实现会漂移。
     */
    static String canonicalize(Map<String, Object> manifest) {
        Map<String, Object> copy = new TreeMap<>();
        manifest.forEach((k, v) -> { if (!"signature".equals(k)) copy.put(k, v); });
        try {
            return MAPPER.writeValueAsString(normalize(copy));
        } catch (Exception e) {
            throw new IllegalStateException("清单规范化失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object normalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), normalize(v)));
            return sorted;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> list = new ArrayList<>();
            for (Object item : collection) list.add(normalize(item));
            return list;
        }
        return value;
    }

    /** 计算签名值（十六进制 HMAC-SHA256）。 */
    static String sign(Map<String, Object> manifest, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonicalize(manifest).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("签名计算失败", e);
        }
    }

    /** 构造签名对象（写包时使用）。 */
    static Map<String, Object> buildSignature(Map<String, Object> manifest, String keyId, String secret) {
        return Map.of(
            "algorithm", ALGORITHM,
            "keyId", keyId,
            "manifestDigest", "sha256:" + sha256Hex(canonicalize(manifest).getBytes(StandardCharsets.UTF_8)),
            "value", sign(manifest, secret));
    }

    /** 恒定时间比较，避免签名校验被计时侧信道利用。 */
    static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
