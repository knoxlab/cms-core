package com.powercms.cms.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.powercms.common.error.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DPoP（RFC 9728）证明验证：ES256/RS256 验签、htm/htu/iat 校验、jkt（RFC 7638）计算。 */
class DpopProofTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final String HTU = "https://cms.example.com/oauth/token";

    @Test
    void verifiesEs256ProofAndComputesJkt() throws Exception {
        KeyPair kp = ecKeyPair();
        ECPublicKey pub = (ECPublicKey) kp.getPublic();
        String x = OAuthCrypto.base64Url(fixed(pub.getW().getAffineX(), 32));
        String y = OAuthCrypto.base64Url(fixed(pub.getW().getAffineY(), 32));

        ObjectNode jwk = objectMapper.createObjectNode();
        jwk.put("kty", "EC");
        jwk.put("crv", "P-256");
        jwk.put("x", x);
        jwk.put("y", y);

        String jwt = signDpop(kp, "ES256", jwk, "POST", HTU);
        DpopProof.Verified verified = DpopProof.verify(jwt, "POST", HTU, objectMapper);

        assertEquals("POST", verified.htm());
        assertEquals(HTU, verified.htu());
        assertEquals(expectedJkt(jwk), verified.jkt());
        assertTrue(!verified.jti().isBlank());
    }

    @Test
    void verifiesRs256Proof() throws Exception {
        KeyPair kp = rsaKeyPair();
        RSAPublicKey pub = (RSAPublicKey) kp.getPublic();
        ObjectNode jwk = objectMapper.createObjectNode();
        jwk.put("kty", "RSA");
        jwk.put("n", OAuthCrypto.base64Url(pub.getModulus().toByteArray()));
        jwk.put("e", OAuthCrypto.base64Url(pub.getPublicExponent().toByteArray()));

        String jwt = signDpop(kp, "RS256", jwk, "POST", HTU);
        DpopProof.Verified verified = DpopProof.verify(jwt, "POST", HTU, objectMapper);
        assertEquals(expectedJkt(jwk), verified.jkt());
    }

    @Test
    void rejectsMethodMismatch() throws Exception {
        KeyPair kp = ecKeyPair();
        ECPublicKey pub = (ECPublicKey) kp.getPublic();
        ObjectNode jwk = objectMapper.createObjectNode();
        jwk.put("kty", "EC");
        jwk.put("crv", "P-256");
        jwk.put("x", OAuthCrypto.base64Url(fixed(pub.getW().getAffineX(), 32)));
        jwk.put("y", OAuthCrypto.base64Url(fixed(pub.getW().getAffineY(), 32)));
        String jwt = signDpop(kp, "ES256", jwk, "POST", HTU);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> DpopProof.verify(jwt, "GET", HTU, objectMapper));
        assertTrue(ex.getMessage().contains("htm"));
    }

    @Test
    void rejectsTamperedSignature() throws Exception {
        KeyPair kp = ecKeyPair();
        ECPublicKey pub = (ECPublicKey) kp.getPublic();
        ObjectNode jwk = objectMapper.createObjectNode();
        jwk.put("kty", "EC");
        jwk.put("crv", "P-256");
        jwk.put("x", OAuthCrypto.base64Url(fixed(pub.getW().getAffineX(), 32)));
        jwk.put("y", OAuthCrypto.base64Url(fixed(pub.getW().getAffineY(), 32)));
        String jwt = signDpop(kp, "ES256", jwk, "POST", HTU);

        // 篡改 payload（换 htu）后签名失效
        String[] parts = jwt.split("\\.");
        String tamperedPayload = OAuthCrypto.base64Url(
            ("{\"jti\":\"x\",\"htm\":\"POST\",\"htu\":\"https://evil/\",\"iat\":" + now() + "}")
                .getBytes(StandardCharsets.UTF_8));
        String tampered = parts[0] + "." + tamperedPayload + "." + parts[2];

        assertThrows(BusinessException.class,
            () -> DpopProof.verify(tampered, "POST", "https://evil/", objectMapper));
    }

    // ---- helpers ----

    private String signDpop(KeyPair kp, String alg, ObjectNode jwk, String htm, String htu) throws Exception {
        ObjectNode header = objectMapper.createObjectNode();
        header.put("typ", "dpop+jwt");
        header.put("alg", alg);
        header.set("jwk", jwk);
        String headerB64 = OAuthCrypto.base64Url(objectMapper.writeValueAsBytes(header));

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("jti", OAuthCrypto.randomToken("dpop"));
        payload.put("htm", htm);
        payload.put("htu", htu);
        payload.put("iat", now());
        String payloadB64 = OAuthCrypto.base64Url(objectMapper.writeValueAsBytes(payload));

        String signingInput = headerB64 + "." + payloadB64;
        String jca = "ES256".equals(alg) ? "SHA256withECDSA" : "SHA256withRSA";
        Signature signer = Signature.getInstance(jca);
        signer.initSign(kp.getPrivate());
        signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        String sigB64 = OAuthCrypto.base64Url(signer.sign());
        return signingInput + "." + sigB64;
    }

    private String expectedJkt(ObjectNode jwk) {
        String canonical = jwk.path("kty").asText().equals("EC")
            ? "{\"crv\":\"" + jwk.path("crv").asText() + "\",\"kty\":\"EC\",\"x\":\"" + jwk.path("x").asText()
                + "\",\"y\":\"" + jwk.path("y").asText() + "\"}"
            : "{\"e\":\"" + jwk.path("e").asText() + "\",\"kty\":\"RSA\",\"n\":\"" + jwk.path("n").asText() + "\"}";
        return OAuthCrypto.jwkThumbprint(canonical);
    }

    private static long now() {
        return System.currentTimeMillis() / 1000L;
    }

    private static KeyPair ecKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        return gen.generateKeyPair();
    }

    private static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
    }

    private static byte[] fixed(BigInteger value, int length) {
        byte[] bytes = value.toByteArray();
        if (bytes.length == length) return bytes;
        byte[] out = new byte[length];
        if (bytes.length > length) {
            System.arraycopy(bytes, bytes.length - length, out, 0, length);
        } else {
            System.arraycopy(bytes, 0, out, length - bytes.length, bytes.length);
        }
        return out;
    }
}
