package com.powercms.cms.sitepackage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 站点包打包器（FR-PACKAGE-002/004/008）：清单 + 文件 → 带摘要与签名的合规 ZIP。
 *
 * <p>导出与测试共用同一实现，保证「导出的包必然可以再导入」（往返等价）——
 * 摘要覆盖包内每个文件，签名绑定规范化清单，二者由同一 {@code packageDigest} 关联（§8.3）。</p>
 */
public final class SitePackageBuilder {

    static final String MANIFEST_PATH = SitePackageManifest.MANIFEST_PATH;

    private SitePackageBuilder() {}

    /** 注入 {@code checksums}（路径 → {@code sha256:<hex>}），覆盖除清单外的全部条目。 */
    public static Map<String, Object> withChecksums(Map<String, Object> manifest, Map<String, byte[]> files) {
        Map<String, Object> out = new LinkedHashMap<>(manifest);
        Map<String, Object> checksums = new TreeMap<>();
        files.forEach((path, data) -> checksums.put(path, SitePackageSignature.fileDigest(data)));
        out.put("checksums", checksums);
        return out;
    }

    /**
     * 打包（带签名）。
     *
     * @param manifest 已含全部必填项的清单草稿（checksums/signature 会被覆盖写入）
     * @param files    包内文件（相对路径 → 字节），不含 {@code site-package.yaml}
     * @param keyId    签名 keyId；{@code null} 表示不签名
     * @param secret   签名密钥；{@code null} 表示不签名
     */
    public static byte[] build(Map<String, Object> manifest, Map<String, byte[]> files, String keyId, String secret) {
        Map<String, Object> finalManifest = withChecksums(manifest, files);
        if (keyId != null && secret != null && !secret.isBlank()) {
            finalManifest.put("signature", SitePackageSignature.buildSignature(finalManifest, keyId, secret));
        }
        return zip(finalManifest, files);
    }

    /** 打包（不签名），用于签名策略为 WARN/OFF 的场景与夹具构造。 */
    public static byte[] buildUnsigned(Map<String, Object> manifest, Map<String, byte[]> files) {
        return build(manifest, files, null, null);
    }

    static byte[] zip(Map<String, Object> manifest, Map<String, byte[]> files) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (var zos = new ZipOutputStream(bos)) {
            byte[] manifestBytes = new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory()).writeValueAsBytes(manifest);
            zos.putNextEntry(new ZipEntry(MANIFEST_PATH));
            zos.write(manifestBytes);
            zos.closeEntry();

            List<String> paths = new ArrayList<>(files.keySet());
            paths.sort(Comparator.comparingInt(SitePackageBuilder::importOrder).thenComparing(Comparator.naturalOrder()));
            for (String path : paths) {
                zos.putNextEntry(new ZipEntry(path));
                zos.write(files.get(path));
                zos.closeEntry();
            }
            zos.finish();
        } catch (IOException e) {
            throw new IllegalStateException("站点包打包失败: " + e.getMessage(), e);
        }
        return bos.toByteArray();
    }

    /** 包内文件写入顺序：package.json 优先，lockfile 最后，保证还原时可先识别工程类型。 */
    static int importOrder(String path) {
        if (path.equals("template/package.json")) return 0;
        if (path.equals("template/package-lock.json") || path.equals("template/yarn.lock")
            || path.equals("template/pnpm-lock.yaml")) return 2;
        return 1;
    }

    /** 供测试/夹具使用的摘要计算入口。 */
    public static String sha256(byte[] data) {
        return SitePackageSignature.sha256Hex(data);
    }
}
