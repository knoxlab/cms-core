package com.powercms.cms.identity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** OAuth 密码学辅助：PKCE S256、base64url、随机凭据。 */
class OAuthCryptoTest {

    @Test
    void pkceChallengeIsBase64UrlSha256() {
        String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        String challenge = OAuthCrypto.pkceChallenge(verifier);
        // RFC 7636 附录 B 标准示例
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", challenge);
    }

    @Test
    void base64UrlRoundTrips() {
        byte[] bytes = "hello world".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String encoded = OAuthCrypto.base64Url(bytes);
        assertTrue(!encoded.contains("+") && !encoded.contains("/") && !encoded.contains("="));
        assertEquals("hello world", new String(OAuthCrypto.base64UrlDecode(encoded)));
    }

    @Test
    void randomTokenIsUniqueAndPrefixed() {
        String a = OAuthCrypto.randomToken("pcm_oat");
        String b = OAuthCrypto.randomToken("pcm_oat");
        assertTrue(a.startsWith("pcm_oat_"));
        assertNotEquals(a, b);
    }

    @Test
    void sha256HexIsStable() {
        assertEquals(64, OAuthCrypto.sha256Hex("secret").length());
        assertEquals(OAuthCrypto.sha256Hex("secret"), OAuthCrypto.sha256Hex("secret"));
    }
}
