package com.powercms.cms.sitepackage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 站点包测试夹具：构造「除目标缺陷外全部合规」的包，保证每个负例只触发一条门禁
 * （否则「清单缺字段」会掩盖压缩炸弹、摘要篡改等具体结论）。
 */
final class SitePackageFixtures {

    static final String DEV_KEY_ID = "dev-2026";
    static final String DEV_SECRET = "dev-only-change-this-site-package-signing-key";

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private SitePackageFixtures() {}

    /** 全部必填项齐备的清单草稿。 */
    static Map<String, Object> manifest(String siteKey, String packageId) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", "1.0");
        manifest.put("packageId", packageId);
        manifest.put("name", "Probe " + siteKey);
        manifest.put("version", "1.0.0");
        manifest.put("siteKey", siteKey);
        manifest.put("cmsVersionRange", ">=12.0.0");
        manifest.put("vendor", Map.of("name", "PowerCMS Probe"));
        manifest.put("license", Map.of("spdx", "MIT", "name", "MIT License"));
        manifest.put("templateRuntime", Map.of("type", "REACT", "nodeVersion", "20"));
        manifest.put("entrypoints", Map.of("build", "npm run build", "output", "dist"));
        manifest.put("requiredCapabilities", java.util.List.of("content-type", "template-react"));
        return manifest;
    }

    /** 最简合规文件集：一个模型 + 一个前端工程入口。 */
    static Map<String, byte[]> files(String modelCode) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("models/" + modelCode + ".yaml", utf8("code: " + modelCode + "\nname: " + modelCode
            + "\nfields:\n  - code: title\n    name: Title\n    fieldType: TEXT\n"));
        files.put("template/package.json", utf8("{\"name\":\"probe\",\"scripts\":{\"build\":\"vite build\"}}"));
        files.put("template/src/main.tsx", utf8("export const x = 1;\n"));
        return files;
    }

    /** 签名打包（keyId/secret 使用与 application.yml 一致的开发密钥）。 */
    static byte[] pack(Map<String, Object> manifest, Map<String, byte[]> files) {
        return SitePackageBuilder.build(manifest, files, DEV_KEY_ID, DEV_SECRET);
    }

    static byte[] packUnsigned(Map<String, Object> manifest, Map<String, byte[]> files) {
        return SitePackageBuilder.buildUnsigned(manifest, files);
    }

    /** 按给定清单（不改动 checksums/signature）重新打包，用于构造篡改/未覆盖夹具。 */
    static byte[] repackRaw(Map<String, Object> manifest, Map<String, byte[]> files) {
        return SitePackageBuilder.zip(manifest, files);
    }

    /** 解包 → 修改清单 → 重新打包（用于签名篡改、字段替换等场景）。 */
    static byte[] mutateManifest(byte[] pkg, Consumer<Map<String, Object>> tweak) {
        Map<String, byte[]> entries = unzip(pkg);
        Map<String, Object> manifest;
        try {
            manifest = YAML.readValue(entries.remove(SitePackageBuilder.MANIFEST_PATH), Map.class);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        tweak.accept(manifest);
        return SitePackageBuilder.zip(manifest, entries);
    }

    /** 解包 → 替换某个文件的字节 → 重新打包（清单保持原样，用于摘要/签名失配）。 */
    static byte[] mutateFile(byte[] pkg, String path, byte[] content) {
        Map<String, byte[]> entries = unzip(pkg);
        entries.put(path, content);
        Map<String, Object> manifest;
        try {
            manifest = YAML.readValue(entries.remove(SitePackageBuilder.MANIFEST_PATH), Map.class);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return SitePackageBuilder.zip(manifest, entries);
    }

    static Map<String, byte[]> unzip(byte[] pkg) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (var zin = new ZipInputStream(new ByteArrayInputStream(pkg))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                entries.put(entry.getName(), zin.readAllBytes());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return entries;
    }

    /**
     * 压缩炸弹夹具：5 × 8MB 全零文件，单文件在 50MB 上限内，但压缩比约 1000:1。
     */
    static byte[] ratioBomb(Map<String, Object> manifest) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("models/bomb.yaml", utf8("code: bomb\nname: Bomb\n"));
        files.put("template/package.json", utf8("{\"name\":\"bomb\"}"));
        byte[] zeros = new byte[8 * 1024 * 1024];
        for (int i = 1; i <= 5; i++) files.put("assets/zeros" + i + ".bin", zeros);
        return packUnsigned(manifest, files);
    }

    /** 大小写冲突夹具：仅大小写不同的两个条目。 */
    static byte[] caseCollision(Map<String, Object> manifest) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("models/case.yaml", utf8("code: case\nname: Case\n"));
        files.put("template/package.json", utf8("{\"name\":\"case\"}"));
        files.put("template/src/Readme.md", utf8("# Readme\n"));
        files.put("template/src/README.md", utf8("# README\n"));
        return packUnsigned(manifest, files);
    }

    /** 符号链接夹具：PKWARE Unix 扩展字段声明 S_IFLNK。 */
    static byte[] symlinkEntry(Map<String, Object> manifest) {
        byte[] body = packUnsigned(manifest, files("link"));
        Map<String, byte[]> entries = unzip(body);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (var zos = new ZipOutputStream(bos)) {
            for (String path : entries.keySet().stream()
                .sorted(Comparator.comparingInt(SitePackageBuilder::importOrder)).toList()) {
                ZipEntry entry = new ZipEntry(path);
                if (path.equals("template/src/main.tsx")) {
                    // id=0x000d, size=5, version=1, mode=0x0000A1FF（S_IFLNK | 0777）
                    entry.setExtra(new byte[]{0x0d, 0x00, 0x05, 0x00, 0x01, 0x00, 0x00, (byte) 0xA1, (byte) 0xFF});
                }
                zos.putNextEntry(entry);
                zos.write(entries.get(path));
                zos.closeEntry();
            }
            zos.finish();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bos.toByteArray();
    }

    static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
