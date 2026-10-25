package com.powercms.cms.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * OAuth 密码学辅助：base64url 编解码、SHA-256（hex/base64url）、PKCE S256、随机凭据生成。
 */
public final class OAuthCrypto {

    private static final SecureRandom RANDOM = new SecureRandom();

    private OAuthCrypto() {}

    public static String sha256Hex(String value) {
        byte[] digest = sha256(value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    /** PKCE S256：base64url( SHA256( code_verifier ) )。 */
    public static String pkceChallenge(String codeVerifier) {
        byte[] digest = sha256(codeVerifier.getBytes(StandardCharsets.UTF_8));
        return base64Url(digest);
    }

    /** RFC 7638 JWK thumbprint：base64url( SHA256( canonicalized JWK ) )。 */
    public static String jwkThumbprint(String canonicalJwk) {
        byte[] digest = sha256(canonicalJwk.getBytes(StandardCharsets.UTF_8));
        return base64Url(digest);
    }

    public static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static byte[] base64UrlDecode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    /** 生成高熵随机凭据：{@code prefix + '_' + hex(16B random)}。 */
    public static String randomToken(String prefix) {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return prefix + "_" + HexFormat.of().formatHex(bytes);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
