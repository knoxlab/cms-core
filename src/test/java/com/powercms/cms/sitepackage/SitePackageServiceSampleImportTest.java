package com.powercms.cms.sitepackage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.powercms.cms.identity.IdentityService;
import com.powercms.cms.model.ContentTypeService;
import com.powercms.cms.model.dto.ContentTypeResponse;
import com.powercms.cms.site.SiteService;
import com.powercms.common.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 样例站点包导入（AC-001）。
 *
 * <p>样例目录是「源码树」而非「成品包」：{@code samples/&lt;name&gt;} 不含 checksums（摘要必须在打包时
 * 按实际字节生成）。因此测试用 {@link SitePackageBuilder} 打包后再导入，并额外验证「直接导入源码树
 * 会被清单门禁阻断」——这正是 FR-PACKAGE-002/004 要求的行为。</p>
 */
@SpringBootTest
@Transactional
class SitePackageServiceSampleImportTest {

    @Autowired private IdentityService identityService;
    @Autowired private SitePackageService sitePackageService;
    @Autowired private SiteService siteService;
    @Autowired private ContentTypeService contentTypeService;
    @Autowired private TemplateService templateService;

    @Test
    void importsReactNewsSampleThroughRealService() throws IOException {
        assertSampleImport("react-news", "react-newsroom", "article", "REACT");
    }

    @Test
    void importsVueKnowledgeSampleThroughRealService() throws IOException {
        assertSampleImport("vue-knowledge", "vue-knowledge", "document", "VUE");
    }

    @Test
    void importsReactDamSampleThroughRealService() throws IOException {
        Path sample = repositoryRoot().resolve("samples").resolve("react-dam");
        UUID organizationId = identityService.ensureOrganization("dam-sample-" + UUID.randomUUID());
        var result = sitePackageService.importPackage(organizationId, packagedSample(sample));

        var site = siteService.getById(result.siteId());
        assertThat(site.siteKey()).isEqualTo("react-dam");
        assertThat(site.status()).isEqualTo("ACTIVE");

        var models = contentTypeService.list(site.id());
        assertThat(models).extracting(m -> m.code())
            .as("包内模型必须全部导入，媒体引用目标由预置种子补齐")
            .contains("digital_asset", "asset_version", "share_link", "resource_group", "media_image");
        assertThat(nonPresetModels(models)).extracting(m -> m.code())
            .as("非预置模型即包内四模型")
            .containsExactlyInAnyOrder("digital_asset", "asset_version", "share_link", "resource_group");
        assertThat(models).filteredOn(m -> "digital_asset".equals(m.code())).singleElement()
            .satisfies(model -> assertThat(model.fields()).anySatisfy(field -> {
                assertThat(field.code()).isEqualTo("asset_kind_s");
                assertThat(field.fieldType()).isEqualTo("ENUM");
                assertThat(field.fieldConfig()).isNotNull();
                assertThat(field.fieldConfig().get("options").size()).isEqualTo(5);
            }));
        // media_items 重复组：YAML 的嵌套 fields 必须还原为布局树上的组元素 + 6 个嵌套子字段。
        // 样例模型已页签化（TAB → FORM_SECTION → REPEAT_GROUP），查找必须递归，不能只看顶层一层的子节点。
        assertThat(models).filteredOn(m -> "digital_asset".equals(m.code())).singleElement()
            .satisfies(model -> {
                var repeat = findRepeatGroup(model.layout(), "media_items");
                assertThat(repeat).as("布局树内必须存在 media_items 重复组").isNotNull();
                assertThat(repeat.children())
                    .as("重复组必须携带 6 个嵌套子字段")
                    .hasSize(6);
                assertThat(repeat.children())
                    .extracting(child -> child.field().code())
                    .containsExactly("media_storage_uri_s", "media_title_s", "media_kind_s",
                        "media_mime_type_s", "media_file_size_i", "media_thumbnail_url_s");
            });

        var projects = templateService.listProjects(site.id());
        assertThat(projects).singleElement().satisfies(project -> {
            assertThat(project.projectType()).isEqualTo("REACT");
            assertThat(templateService.validateLockfile(project.id()).isReleaseReady()).isTrue();
            assertThat(templateService.listFiles(project.id())).extracting(f -> f.path())
                .as("客户端表单控制器脚本随模板工程一起分发（模型只需引用路径）")
                .contains("package.json", "src/App.tsx", "src/main.tsx",
                    "scripts/digital-asset-form-controller.js");
        });

        // 模型级属性（客户端表单控制器）必须落库：此前 toCreateRequest 硬编码 null，包内声明被静默丢弃，
        // 导入后要人工去管理端再填一次 —— README 的「零改动」承诺因此失效。
        assertThat(models).filteredOn(m -> "digital_asset".equals(m.code())).singleElement()
            .satisfies(model -> assertThat(model.formController())
                .isEqualTo("/scripts/digital-asset-form-controller.js"));

        // FR-PACKAGE-008 在重复组上的往返保真：导出按嵌套 fields 输出，重导入后层级不丢。
        // （响应的扁平 fields 过滤掉了 REPEAT_GROUP 结构元素，导出必须改走布局树，
        //   否则「自己导出的包自己导回去」会把重复组与子字段的关系压平。）
        assertExportedDamRoundTripKeepsRepeatGroup(organizationId, site.id());
    }

    @SuppressWarnings("unchecked")
    private void assertExportedDamRoundTripKeepsRepeatGroup(UUID organizationId, UUID siteId) throws IOException {
        byte[] exported = sitePackageService.exportPackage(siteId);
        Map<String, byte[]> entries = unzip(exported);
        assertThat(entries.keySet()).contains("models/digital_asset.yaml");

        Map<String, Object> model = new ObjectMapper(new YAMLFactory())
            .readValue(entries.get("models/digital_asset.yaml"), Map.class);
        // 模型级属性随包导出（否则「导出→重导入」会把客户端表单控制器重置为空）
        assertThat(model.get("formController")).isEqualTo("/scripts/digital-asset-form-controller.js");
        List<Map<String, Object>> fields = (List<Map<String, Object>>) model.get("fields");
        Map<String, Object> group = fields.stream()
            .filter(field -> "media_items".equals(field.get("code")))
            .findFirst()
            .orElseThrow();
        assertThat(group.get("fieldType")).isEqualTo("REPEAT_GROUP");
        List<Map<String, Object>> children = (List<Map<String, Object>>) group.get("fields");
        assertThat(children).as("导出必须保留重复组的嵌套子字段").isNotNull().hasSize(6);
        assertThat(children.get(0).get("code")).isEqualTo("media_storage_uri_s");

        var precheck = sitePackageService.precheck(organizationId, exported);
        assertThat(precheck.blocked()).isFalse();
        assertThat(precheck.confirmable()).isTrue();
        String token = precheck.resolutions().stream()
            .filter(r -> "RENAME".equals(r.strategy())).findFirst().orElseThrow().token();
        var reimported = sitePackageService.importPackage(organizationId, exported, "RENAME", token);

        var models = contentTypeService.list(reimported.siteId());
        assertThat(models).filteredOn(m -> "digital_asset".equals(m.code())).singleElement()
            .satisfies(reimportedModel -> {
                var repeat = findRepeatGroup(reimportedModel.layout(), "media_items");
                assertThat(repeat).as("重导入后布局树内仍须存在 media_items 重复组").isNotNull();
                assertThat(repeat.children()).hasSize(6);
                assertThat(reimportedModel.formController())
                    .as("重导入后客户端表单控制器不得被重置")
                    .isEqualTo("/scripts/digital-asset-form-controller.js");
            });
    }

    /** 在布局树（TAB / FORM_SECTION / ROW / COLUMN 等容器）中递归查找指定变量名的重复组。 */
    private static ContentTypeResponse.LayoutInfo findRepeatGroup(
            List<ContentTypeResponse.LayoutInfo> nodes, String variableName) {
        if (nodes == null) return null;
        for (ContentTypeResponse.LayoutInfo node : nodes) {
            if (node == null) continue;
            if ("REPEAT_GROUP".equals(node.elementType()) && node.config() != null
                && variableName.equals(node.config().path("variableName").asText(null))) {
                return node;
            }
            var found = findRepeatGroup(node.children(), variableName);
            if (found != null) return found;
        }
        return null;
    }

    @Test
    void rejectsRawSourceTreeWithoutChecksums() throws IOException {
        Path sample = repositoryRoot().resolve("samples").resolve("react-news");
        UUID organizationId = identityService.ensureOrganization("sample-raw-" + UUID.randomUUID());
        byte[] rawZip = zipDirectory(sample);
        assertThatThrownBy(() -> sitePackageService.importPackage(organizationId, rawZip))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                .isEqualTo(com.powercms.common.error.ErrorCodes.SITE_PACKAGE_MANIFEST_INVALID));
    }

    private void assertSampleImport(String sampleName, String siteKey, String modelCode,
                                    String projectType) throws IOException {
        Path sample = repositoryRoot().resolve("samples").resolve(sampleName);
        UUID organizationId = identityService.ensureOrganization("SitePackageServiceSampleImportTest-" + UUID.randomUUID());
        var result = sitePackageService.importPackage(organizationId, packagedSample(sample));

        var site = siteService.getById(result.siteId());
        assertThat(site.siteKey()).isEqualTo(siteKey);
        assertThat(site.status()).isEqualTo("ACTIVE");
        assertThat(site.environments()).extracting(e -> e.name())
            .containsExactlyInAnyOrder("preview", "staging", "production");

        var models = contentTypeService.list(site.id());
        assertThat(nonPresetModels(models)).singleElement().satisfies(model -> {
            assertThat(model.code()).isEqualTo(modelCode);
            assertThat(model.status()).isEqualTo("PUBLISHED");
            assertThat(model.fields()).isNotEmpty();
        });

        var projects = templateService.listProjects(site.id());
        assertThat(projects).singleElement().satisfies(project -> {
            assertThat(project.projectType()).isEqualTo(projectType);
            assertThat(project.currentRevision()).isEqualTo(1);
            Map<String, TemplateService.FileInfo> files = templateService.listFiles(project.id()).stream()
                .collect(Collectors.toMap(TemplateService.FileInfo::path, Function.identity()));
            assertThat(files).containsKeys("package.json", "package-lock.json", "index.html");
            var lockfile = templateService.validateLockfile(project.id());
            assertThat(lockfile.hasPackageJson()).isTrue();
            assertThat(lockfile.hasLockfile()).isTrue();
            assertThat(lockfile.matchValid()).isTrue();
            assertThat(lockfile.isReleaseReady()).isTrue();
        });

        assertThat(sitePackageService.getPackage(result.packageId()).importedSiteId())
            .isEqualTo(site.id());
    }

    /**
     * FR-PACKAGE-008 往返保真：导出包必须能被再次导入，且不得携带会在导入侧被阻断的内容。
     *
     * <p>覆盖三个真实缺陷：</p>
     * <ol>
     *   <li>导出曾把 {@code models/system.*} 一并打包 —— 目标实例会为站点自动补种这些模型
     *       （FR-PACKAGE-007 隔离），而导入侧禁止覆盖系统模型（FR-PACKAGE-005），
     *       于是「自己导出的包自己导不回去」；</li>
     *   <li>FTL 等工程没有 {@code template/package.json}，而导入器以它判定「是否携带前端工程」，
     *       缺失会让模板文件被静默丢弃；</li>
     *   <li>剔除动作必须留痕（{@code docs/omitted-system-models.md}），不做静默丢弃。</li>
     * </ol>
     */
    @Test
    void exportedPackageIsRoundTrippableAndExcludesAutoSeededSystemModels() throws IOException {
        Path sample = repositoryRoot().resolve("samples").resolve("react-news");
        UUID organizationId = identityService.ensureOrganization("export-roundtrip-" + UUID.randomUUID());
        var imported = sitePackageService.importPackage(organizationId, packagedSample(sample));

        // 造一个系统前缀模型，让「导出剔除系统模型」这条断言不是空验证
        contentTypeService.create(imported.siteId(), new com.powercms.cms.model.dto.CreateContentTypeRequest(
            "system.probe-seeded", "Probe Seeded", "roundtrip", java.util.List.of()));

        byte[] exported = sitePackageService.exportPackage(imported.siteId());
        Map<String, byte[]> entries = unzip(exported);

        assertThat(entries.keySet()).as("系统模型不得随导出包外流").noneMatch(p -> p.startsWith("models/system."));
        assertThat(entries.keySet())
            .as("剔除原因必须留痕，不做静默丢弃")
            .contains("docs/omitted-system-models.md");
        assertThat(new String(entries.get("docs/omitted-system-models.md"), java.nio.charset.StandardCharsets.UTF_8))
            .contains("system.probe-seeded");
        assertThat(entries.keySet())
            .as("缺少 template/package.json 时模板文件会在往返中被静默丢弃")
            .contains("template/package.json", "template/src/main.tsx");

        // 导出包必须能被再次导入（同实例 siteKey 冲突 → 显式 RENAME 确认后成功）
        var precheck = sitePackageService.precheck(organizationId, exported);
        assertThat(precheck.blocked()).isFalse();
        assertThat(precheck.confirmable()).isTrue();
        String token = precheck.resolutions().stream()
            .filter(r -> "RENAME".equals(r.strategy())).findFirst().orElseThrow().token();

        var reimported = sitePackageService.importPackage(organizationId, exported, "RENAME", token);
        assertThat(reimported.siteKey()).startsWith(siteKeyOf(sample)).isNotEqualTo(siteKeyOf(sample));

        var models = contentTypeService.list(reimported.siteId());
        assertThat(models.stream().map(m -> m.code())).contains("article").doesNotContain("system.probe-seeded");
        assertThat(templateService.listProjects(reimported.siteId())).singleElement()
            .satisfies(p -> assertThat(templateService.listFiles(p.id())).extracting(TemplateService.FileInfo::path)
                .contains("package.json", "src/main.tsx"));
    }

    private static String siteKeyOf(Path sample) throws IOException {
        byte[] manifestBytes = Files.readAllBytes(sample.resolve(SitePackageBuilder.MANIFEST_PATH));
        Object key = new ObjectMapper(new YAMLFactory()).readValue(manifestBytes, Map.class).get("siteKey");
        return String.valueOf(key);
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (var zin = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                out.put(entry.getName(), zin.readAllBytes());
            }
        }
        return out;
    }

    /** 把样例源码树打包为合规包：解析清单 → 注入 checksums → 生成 ZIP。 */
    @SuppressWarnings("unchecked")
    private static byte[] packagedSample(Path root) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).filter(p -> isPackable(root, p))
                    .sorted(Comparator.naturalOrder()).toList()) {
                String entryName = root.relativize(path).toString().replace('\\', '/');
                files.put(entryName, Files.readAllBytes(path));
            }
        }
        byte[] manifestBytes = files.remove(SitePackageBuilder.MANIFEST_PATH);
        assertThat(manifestBytes).as("样例目录必须包含 site-package.yaml").isNotNull();
        Map<String, Object> manifest = new ObjectMapper(new YAMLFactory()).readValue(manifestBytes, Map.class);
        return SitePackageBuilder.buildUnsigned(manifest, files);
    }

    /** 平台预置模型（media_* 等）由导入种子补齐，断言「包内模型」时先剔除。 */
    private static java.util.List<com.powercms.cms.model.dto.ContentTypeResponse> nonPresetModels(
            java.util.List<com.powercms.cms.model.dto.ContentTypeResponse> models) {
        return models.stream()
            .filter(m -> com.powercms.cms.model.MediaTypeSeedService.seeds().stream()
                .noneMatch(s -> s.code().equals(m.code())))
            .toList();
    }

    /** 样例源码树只打包源文件：node_modules 等依赖目录不得进包（npm 的 .cmd/.ps1 shim 会触发脚本门禁）。 */
    private static boolean isPackable(Path root, Path file) {
        for (Path part : root.relativize(file)) {
            if ("node_modules".equals(part.toString()) || ".vite".equals(part.toString())) {
                return false;
            }
        }
        return true;
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isDirectory(current.resolve("samples"))) {
            current = current.getParent();
        }
        // 社区版仓库不携带 samples（样例站点各有独立仓库），缺少样例目录时跳过本组用例。
        assumeTrue(current != null, "Cannot locate repository samples directory");
        return current;
    }

    private static byte[] zipDirectory(Path root) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).filter(p -> isPackable(root, p))
                    .sorted(Comparator.naturalOrder()).toList()) {
                files.put(root.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path));
            }
        }
        byte[] manifestBytes = files.remove(SitePackageBuilder.MANIFEST_PATH);
        try {
            Map<String, Object> manifest = new ObjectMapper(new YAMLFactory()).readValue(manifestBytes, Map.class);
            return SitePackageBuilder.zip(manifest, files);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
