package com.powercms.cms.sitepackage;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 摘要与签名原语（FR-PACKAGE-004、§8.3）：规范化清单必须与键序无关，
 * 否则「打包机」与「校验器」会得出不同签名。
 */
class SitePackageSignatureTest {

    @Test
    void canonicalFormIsIndependentOfKeyOrder() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("packageId", "com.example.a");
        a.put("version", "1.0.0");
        a.put("checksums", Map.of("models/a.yaml", "sha256:aa"));

        Map<String, Object> b = new LinkedHashMap<>();
        b.put("checksums", Map.of("models/a.yaml", "sha256:aa"));
        b.put("version", "1.0.0");
        b.put("packageId", "com.example.a");

        assertThat(SitePackageSignature.canonicalize(a)).isEqualTo(SitePackageSignature.canonicalize(b));
    }

    @Test
    void signatureIgnoredByItsOwnCanonicalForm() {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("packageId", "com.example.b");
        base.put("version", "1.0.0");
        String withoutSignature = SitePackageSignature.canonicalize(base);

        Map<String, Object> withSignature = new LinkedHashMap<>(base);
        withSignature.put("signature", Map.of("algorithm", "hmac-sha256", "value", "deadbeef"));
        assertThat(SitePackageSignature.canonicalize(withSignature)).isEqualTo(withoutSignature);
    }

    @Test
    void signatureDetectsAnyFieldChange() {
        String secret = "unit-test-secret";
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("packageId", "com.example.c");
        manifest.put("version", "1.0.0");
        manifest.put("checksums", Map.of("models/c.yaml", "sha256:cc"));
        String signature = SitePackageSignature.sign(manifest, secret);

        assertThat(SitePackageSignature.constantTimeEquals(signature,
            SitePackageSignature.sign(manifest, secret))).isTrue();

        Map<String, Object> tampered = new LinkedHashMap<>(manifest);
        tampered.put("version", "1.0.1");
        assertThat(SitePackageSignature.constantTimeEquals(signature,
            SitePackageSignature.sign(tampered, secret))).isFalse();

        assertThat(SitePackageSignature.constantTimeEquals(signature,
            SitePackageSignature.sign(manifest, "other-secret"))).isFalse();
    }

    @Test
    void fileDigestIsStableAndPrefixed() {
        String digest = SitePackageSignature.fileDigest("hello".getBytes());
        assertThat(digest).startsWith("sha256:").hasSize(71);
        assertThat(digest).isEqualTo(SitePackageSignature.fileDigest("hello".getBytes()));
        assertThat(digest).isNotEqualTo(SitePackageSignature.fileDigest("hello!".getBytes()));
    }

    @Test
    void builderInjectsChecksumsForEveryFile() {
        Map<String, byte[]> files = SitePackageFixtures.files("build_probe");
        Map<String, Object> withChecksums = SitePackageBuilder.withChecksums(
            new LinkedHashMap<>(Map.of("packageId", "com.example.d")), files);
        @SuppressWarnings("unchecked")
        Map<String, Object> checksums = (Map<String, Object>) withChecksums.get("checksums");
        assertThat(checksums.keySet()).containsExactlyInAnyOrderElementsOf(files.keySet());
        assertThat(checksums.values()).allSatisfy(v -> assertThat(String.valueOf(v)).startsWith("sha256:"));
    }

    @Test
    void consentTokenBindsDigestAndStrategy() {
        SitePackageProperties props = new SitePackageProperties();
        props.setConsentSecret("unit-test-consent-secret");
        props.setConsentTtlSeconds(60);

        String token = SitePackageConsent.issue("digest-a", "RENAME", props);
        assertThat(SitePackageConsent.verify(token, "digest-a", "RENAME", props)).isEqualTo("RENAME");

        assertRejected(() -> SitePackageConsent.verify(token, "digest-b", "RENAME", props));
        assertRejected(() -> SitePackageConsent.verify(token, "digest-a", "OVERWRITE", props));
        assertRejected(() -> SitePackageConsent.verify("forged.token", "digest-a", "RENAME", props));

        SitePackageProperties expired = new SitePackageProperties();
        expired.setConsentSecret("unit-test-consent-secret");
        expired.setConsentTtlSeconds(-1);
        String staleToken = SitePackageConsent.issue("digest-a", "RENAME", expired);
        assertRejected(() -> SitePackageConsent.verify(staleToken, "digest-a", "RENAME", props));
    }

    private static void assertRejected(Runnable action) {
        org.assertj.core.api.Assertions.assertThatThrownBy(action::run)
            .isInstanceOf(com.powercms.common.error.BusinessException.class)
            .satisfies(ex -> assertThat(((com.powercms.common.error.BusinessException) ex).getCode())
                .isEqualTo(com.powercms.common.error.ErrorCodes.SITE_PACKAGE_CONSENT_INVALID));
    }
}
