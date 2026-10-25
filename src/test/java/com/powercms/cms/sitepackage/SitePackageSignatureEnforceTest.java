package com.powercms.cms.sitepackage;

import com.powercms.cms.identity.IdentityService;
import com.powercms.cms.site.repo.SiteRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 签名策略 ENFORCE 下的导入行为（FR-PACKAGE-004「生产默认阻断未签名包」）。
 *
 * <p>{@code SitePackageProperties#signaturePolicy} 的代码默认值即 {@code ENFORCE}，
 * 此处用属性覆盖显式回到生产语义，验证：签名有效放行、缺失阻断、篡改阻断。</p>
 */
@SpringBootTest(properties = {
    "powercms.site-package.signature-policy=ENFORCE",
    "powercms.site-package.signing-keys.dev-2026=dev-only-change-this-site-package-signing-key"
})
@Transactional
class SitePackageSignatureEnforceTest {

    @Autowired private IdentityService identityService;
    @Autowired private SitePackageService service;
    @Autowired private SiteRepository siteRepo;

    private UUID org() {
        return identityService.ensureOrganization("pkg-enforce-" + UUID.randomUUID());
    }

    private static String siteKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void productionDefaultIsEnforce() {
        assertThat(new SitePackageProperties().getSignaturePolicy())
            .isEqualTo(SitePackageProperties.SignaturePolicy.ENFORCE);
    }

    @Test
    void signedPackageImportsUnderEnforce() {
        String siteKey = siteKey("enforce-ok");
        Map<String, Object> manifest = SitePackageFixtures.manifest(siteKey, "com.powercms.probe." + siteKey);
        var result = service.importPackage(org(),
            SitePackageFixtures.pack(manifest, SitePackageFixtures.files("enforce_ok")));
        assertThat(siteRepo.existsBySiteKey(result.siteKey())).isTrue();
        assertThat(service.getPackage(result.packageId()).packageDigest()).hasSize(64);
    }

    @Test
    void unsignedPackageBlockedUnderEnforce() {
        String siteKey = siteKey("enforce-unsigned");
        byte[] pkg = SitePackageFixtures.packUnsigned(
            SitePackageFixtures.manifest(siteKey, "com.powercms.probe." + siteKey),
            SitePackageFixtures.files("enforce_unsigned"));
        assertThatThrownBy(() -> service.importPackage(org(), pkg))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                .isEqualTo(ErrorCodes.SITE_PACKAGE_SIGNATURE_REQUIRED));
        assertThat(siteRepo.existsBySiteKey(siteKey)).isFalse();
    }

    @Test
    void tamperedManifestAfterSigningIsBlocked() {
        String siteKey = siteKey("enforce-tamper");
        byte[] pkg = SitePackageFixtures.pack(
            SitePackageFixtures.manifest(siteKey, "com.powercms.probe." + siteKey),
            SitePackageFixtures.files("enforce_tamper"));
        byte[] tampered = SitePackageFixtures.mutateManifest(pkg, manifest -> manifest.put("version", "9.9.9"));

        assertThatThrownBy(() -> service.importPackage(org(), tampered))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                .isEqualTo(ErrorCodes.SITE_PACKAGE_SIGNATURE_INVALID));
        assertThat(siteRepo.existsBySiteKey(siteKey)).isFalse();
    }
}
