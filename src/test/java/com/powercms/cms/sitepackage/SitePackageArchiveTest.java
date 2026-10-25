package com.powercms.cms.sitepackage;

import com.powercms.common.error.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 归档安全门禁（FR-PACKAGE-003、NFR-SEC-006）：压缩比 / 总解压量 / 大小写冲突 / 路径逃逸 / 符号链接。
 * 这些校验必须先于清单解析，否则残缺清单会掩盖安全结论。
 */
class SitePackageArchiveTest {

    private static SitePackageProperties props() {
        SitePackageProperties props = new SitePackageProperties();
        props.setMaxZipSizeMb(64);
        props.setMaxSingleFileMb(16);
        props.setMaxUncompressedMb(64);
        props.setMaxCompressionRatio(50);
        return props;
    }

    @Test
    void acceptsNormalPackage() {
        byte[] zip = SitePackageFixtures.packUnsigned(SitePackageFixtures.manifest("probe-ok", "probe.ok"),
            SitePackageFixtures.files("ok_model"));
        var scan = SitePackageArchive.scan(zip, props());
        assertThat(scan.entries()).containsKeys("site-package.yaml", "models/ok_model.yaml", "template/package.json");
        assertThat(scan.digest()).hasSize(64);
    }

    @Test
    void blocksCompressionBomb() {
        byte[] zip = SitePackageFixtures.ratioBomb(SitePackageFixtures.manifest("probe-bomb", "probe.bomb"));
        assertThatThrownBy(() -> SitePackageArchive.scan(zip, props()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("安全门禁")
            .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                .isEqualTo(com.powercms.common.error.ErrorCodes.SITE_PACKAGE_SECURITY_BLOCKED));
    }

    @Test
    void blocksUncompressedTotalOverLimit() {
        SitePackageProperties small = props();
        small.setMaxCompressionRatio(10_000);
        small.setMaxUncompressedMb(8);
        byte[] zip = SitePackageFixtures.ratioBomb(SitePackageFixtures.manifest("probe-total", "probe.total"));
        assertThatThrownBy(() -> SitePackageArchive.scan(zip, small))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("安全门禁");
    }

    @Test
    void blocksCaseCollision() {
        byte[] zip = SitePackageFixtures.caseCollision(SitePackageFixtures.manifest("probe-case", "probe.case"));
        assertThatThrownBy(() -> SitePackageArchive.scan(zip, props()))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> assertThat(String.valueOf(ex.getMessage() + ((BusinessException) ex).getDetails()))
                .contains("大小写冲突"));
    }

    @Test
    void blocksSymlinkEntry() {
        byte[] zip = SitePackageFixtures.symlinkEntry(SitePackageFixtures.manifest("probe-link", "probe.link"));
        assertThatThrownBy(() -> SitePackageArchive.scan(zip, props()))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> assertThat(String.valueOf(((BusinessException) ex).getDetails()))
                .containsAnyOf("符号链接", "设备文件"));
    }

    @Test
    void detectsPathEscapes() {
        assertThat(SitePackageArchive.pathProblem("../evil.txt")).isNotNull();
        assertThat(SitePackageArchive.pathProblem("/abs.txt")).isNotNull();
        assertThat(SitePackageArchive.pathProblem("C:/abs.txt")).isNotNull();
        assertThat(SitePackageArchive.pathProblem("a\\b.txt")).isNotNull();
        assertThat(SitePackageArchive.pathProblem("models/ok.yaml")).isNull();
    }
}
