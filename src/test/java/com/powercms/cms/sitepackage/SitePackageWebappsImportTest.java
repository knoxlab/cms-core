package com.powercms.cms.sitepackage;

import com.powercms.cms.identity.IdentityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 站点包导入 → 站点应用部署（webapps/{siteKey}/）：REACT/VUE 前端产物落盘后可直接访问。
 */
@SpringBootTest
@Transactional
class SitePackageWebappsImportTest {

    @Autowired private IdentityService identityService;
    @Autowired private SitePackageService sitePackageService;
    @Autowired private WebappsService webapps;

    @Test
    void importsReactSiteAppIntoWebappsDir() throws Exception {
        Map<String, Object> manifest = SitePackageFixtures.manifest(
            "probe-webapps", "com.powercms.probe.webapps");
        Map<String, byte[]> files = new LinkedHashMap<>(SitePackageFixtures.files("probe"));
        files.put("template/dist/index.html",
            SitePackageFixtures.utf8("<html><head></head><body>app</body></html>"));
        files.put("template/dist/assets/app-abc.js", SitePackageFixtures.utf8("console.log(1);"));

        UUID organizationId = identityService.ensureOrganization("webapps-" + UUID.randomUUID());
        var result = sitePackageService.importPackage(organizationId, SitePackageFixtures.pack(manifest, files));

        assertThat(result.webappsPath()).isEqualTo("probe-webapps");
        Path dir = webapps.siteDir("probe-webapps");
        assertThat(dir.resolve("index.html")).exists();
        assertThat(dir.resolve("assets/app-abc.js")).exists();
        // 工程源码不部署，只部署构建产物
        assertThat(dir.resolve("src")).doesNotExist();
        assertThat(dir.resolve("package.json")).doesNotExist();
    }

    @Test
    void siteAppNotDeployedForFtlRuntime() {
        Map<String, Object> manifest = SitePackageFixtures.manifest(
            "probe-ftl", "com.powercms.probe.ftl");
        manifest.put("templateRuntime", Map.of("type", "FTL"));
        Map<String, byte[]> files = new LinkedHashMap<>(SitePackageFixtures.files("probe"));
        // FTL 工程不允许 .html 视图（FR-LANG-001），夹具用 js 产物即可
        files.put("template/dist/app.js", SitePackageFixtures.utf8("console.log(1);"));

        UUID organizationId = identityService.ensureOrganization("webapps-ftl-" + UUID.randomUUID());
        var result = sitePackageService.importPackage(organizationId, SitePackageFixtures.pack(manifest, files));

        assertThat(result.webappsPath()).isNull();
        assertThat(Files.exists(webapps.siteDir("probe-ftl"))).isFalse();
    }
}
