package com.powercms.cms.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/**
 * DPoP（RFC 9728）证明验证：解析 DPoP JWT，从 header.jwk 提取公钥并验证签名（ES256/RS256），
 * 校验 htm/htu/iat 绑定请求，计算 RFC 7638 thumbprint（jkt）。
 * 使用 JDK 自带 crypto，无第三方 JOSE 依赖。
 */
public final class DpopProof {

    /** DPoP 证明 JWT 分段（header.payload.signature）。 */
    public record Verified(String jkt, String jti, String htm, String htu) {}

    private DpopProof() {}

    /**
     * 验证 DPoP 证明。
     *
     * @param dpopHeader    DPoP 请求头值（JWT）
     * @param httpMethod    实际请求方法（htm 须一致）
     * @param httpUrl       实际请求 URL（htu 须一致）
     * @param objectMapper  JSON 解析器
     */
    public static Verified verify(String dpopHeader, String httpMethod, String httpUrl,
                                  ObjectMapper objectMapper) {
        if (dpopHeader == null || dpopHeader.isBlank()) {
            throw new BusinessException(ErrorCodes.BAD_REQUEST, "缺少 DPoP 证明");
        }
        String[] parts = dpopHeader.split("\\.");
        if (parts.length != 3) {
            throw new BusinessException(ErrorCodes.BAD_REQUEST, "DPoP 证明格式非法");
        }
        JsonNode header = decodeJson(parts[0], objectMapper, "DPoP header");
        JsonNode payload = decodeJson(parts[1], objectMapper, "DPoP payload");

        String typ = header.path("typ").asText("");
        if (!"dpop+jwt".equals(typ)) {
            throw new BusinessException(ErrorCodes.BAD_REQUEST, "DPoP typ 须为 dpop+jwt");
        }
        String alg = header.path("alg").asText("");
        JsonNode jwk = header.path("jwk");
        if (jwk.isMissingNode() || !jwk.isObject()) {
            throw new BusinessException(ErrorCodes.BAD_REQUEST, "DPoP 缺少 jwk");
        }

        String htm = payload.path("htm").asText("");
        String htu = payload.path("htu").asText("");
        if (!htm.equalsIgnoreCase(httpMethod)) {
            throw new BusinessException(ErrorCodes.BAD_REQUEST, "DPoP htm 与请求方法不一致");
        }
        if (!htu.equalsIgnoreCase(httpUrl)) {
            throw new BusinessException(ErrorCodes.BAD_REQUEST, "DPoP htu 与请求地址不一致");
        }
        long iat = payload.path("iat").asLong(0);
        long now = System.currentTimeMillis() / 1000L;
        if (Math.abs(now - iat) > 300) {
            throw new BusinessException(ErrorCodes.BAD_REQUEST, "DPoP iat 超出允许时间窗");
        }

        PublicKey publicKey = publicKey(alg, jwk);
        byte[] signingInput = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
        byte[] signature = OAuthCrypto.base64UrlDecode(parts[2]);
        if (!verifySignature(alg, publicKey, signingInput, signature)) {
            throw new BusinessException(ErrorCodes.BAD_REQUEST, "DPoP 签名无效");
        }

        String jkt = OAuthCrypto.jwkThumbprint(canonicalJwk(jwk));
        return new Verified(jkt, payload.path("jti").asText(""), htm, htu);
    }

    private static boolean verifySignature(String alg, PublicKey key, byte[] data, byte[] signature) {
        try {
            String jca = switch (alg) {
                case "ES256" -> "SHA256withECDSA";
                case "RS256" -> "SHA256withRSA";
                default -> throw new BusinessException(ErrorCodes.BAD_REQUEST, "不支持的 DPoP 算法：" + alg);
            };
            Signature verifier = Signature.getInstance(jca);
            verifier.initVerify(key);
            verifier.update(data);
            return verifier.verify(signature);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            return false;
        }
    }

    private static PublicKey publicKey(String alg, JsonNode jwk) {
        try {
            return switch (alg) {
                case "ES256" -> ecPublicKey(jwk);
                case "RS256" -> rsaPublicKey(jwk);
                default -> throw new BusinessException(ErrorCodes.BAD_REQUEST, "不支持的 DPoP 算法：" + alg);
            };
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCodes.BAD_REQUEST, "DPoP jwk 非法：" + e.getMessage());
        }
    }

    private static PublicKey ecPublicKey(JsonNode jwk) throws Exception {
        String crv = jwk.path("crv").asText("");
        if (!"P-256".equals(crv)) {
            throw new BusinessException(ErrorCodes.BAD_REQUEST, "DPoP ES256 须使用 P-256");
        }
        BigInteger x = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.path("x").asText()));
        BigInteger y = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.path("y").asText()));
        java.security.AlgorithmParameters params =
            java.security.AlgorithmParameters.getInstance("EC");
        params.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec spec = params.getParameterSpec(ECParameterSpec.class);
        ECPoint point = new ECPoint(x, y);
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, spec));
    }

    private static PublicKey rsaPublicKey(JsonNode jwk) throws Exception {
        BigInteger n = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.path("n").asText()));
        BigInteger e = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.path("e").asText()));
        return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e));
    }

    /** RFC 7638：按必需成员字母序生成规范化 JWK。 */
    private static String canonicalJwk(JsonNode jwk) {
        String kty = jwk.path("kty").asText("");
        StringBuilder sb = new StringBuilder("{");
        switch (kty) {
            case "EC" -> {
                sb.append("\"crv\":\"").append(jwk.path("crv").asText()).append('"');
                sb.append(",\"kty\":\"EC\"");
                sb.append(",\"x\":\"").append(jwk.path("x").asText()).append('"');
                sb.append(",\"y\":\"").append(jwk.path("y").asText()).append('"');
            }
            case "RSA" -> {
                sb.append("\"e\":\"").append(jwk.path("e").asText()).append('"');
                sb.append(",\"kty\":\"RSA\"");
                sb.append(",\"n\":\"").append(jwk.path("n").asText()).append('"');
            }
            default -> throw new BusinessException(ErrorCodes.BAD_REQUEST, "不支持的 JWK kty：" + kty);
        }
        return sb.append('}').toString();
    }

    private static JsonNode decodeJson(String segment, ObjectMapper objectMapper, String what) {
        try {
            return objectMapper.readTree(OAuthCrypto.base64UrlDecode(segment));
        } catch (Exception e) {
            throw new BusinessException(ErrorCodes.BAD_REQUEST, what + " 非法");
        }
    }
}
