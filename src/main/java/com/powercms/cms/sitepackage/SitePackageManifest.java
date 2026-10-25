package com.powercms.cms.sitepackage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static com.powercms.cms.sitepackage.SitePackageViolations.Violation;

/**
 * 站点包清单解析与门禁（FR-PACKAGE-001/002/003/004、§8.2）。
 *
 * <p>职责：根结构预检 → 必填项校验 → 类型与取值校验 → CMS 版本兼容 → 入口脚本与可执行文件策略
 * → 文件摘要（checksums）逐项校验 → 签名校验（策略驱动）。所有结论以 {@link Violation} 累积，
 * 由调用方一次性阻断。</p>
 */
final class SitePackageManifest {

    static final String MANIFEST_PATH = "site-package.yaml";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** FR-PACKAGE-002：清单必须声明的字段（{@code signature} 由签名策略决定是否必填）。 */
    static final List<String> REQUIRED_FIELDS = List.of(
        "schemaVersion", "packageId", "name", "version", "vendor", "license", "cmsVersionRange",
        "templateRuntime", "entrypoints", "requiredCapabilities", "checksums");

    private static final Set<String> RUNTIME_TYPES = Set.of("REACT", "VUE", "FTL");
    private static final Set<String> OPTIONAL_SECTIONS = Set.of("content/", "assets/", "search/", "workflows/", "docs/");

    /** 解析后的清单视图（值均已规范化）。 */
    record View(
        Map<String, Object> raw,
        String schemaVersion,
        String packageId,
        String name,
        String version,
        String siteKey,
        String cmsVersionRange,
        String vendorName,
        String licenseSpdx,
        String templateRuntimeType,
        Map<String, Object> entrypoints,
        List<String> requiredCapabilities,
        Map<String, String> checksums,
        Map<String, Object> signature,
        boolean signed,
        boolean signatureVerified) {}

    private SitePackageManifest() {}

    @SuppressWarnings("unchecked")
    static Optional<View> parse(SitePackageArchive.Scan scan, SitePackageProperties props, List<Violation> violations) {
        byte[] manifestBytes = scan.entries().get(MANIFEST_PATH);
        if (manifestBytes == null) {
            violations.add(Violation.blocking(com.powercms.common.error.ErrorCodes.SITE_PACKAGE_STRUCTURE_INVALID,
                MANIFEST_PATH, "根目录缺少 " + MANIFEST_PATH));
            return Optional.empty();
        }

        Map<String, Object> manifest;
        try {
            manifest = YAML.readValue(manifestBytes, Map.class);
        } catch (IOException e) {
            violations.add(Violation.blocking(com.powercms.common.error.ErrorCodes.SITE_PACKAGE_MANIFEST_INVALID,
                MANIFEST_PATH, "YAML 解析失败: " + e.getMessage()));
            return Optional.empty();
        }
        if (manifest == null || manifest.isEmpty()) {
            violations.add(Violation.blocking(com.powercms.common.error.ErrorCodes.SITE_PACKAGE_MANIFEST_INVALID,
                MANIFEST_PATH, "清单内容为空"));
            return Optional.empty();
        }

        checkStructure(scan, violations);
        checkRequiredFields(manifest, props, violations);

        String siteKey = text(manifest.get("siteKey"));
        String cmsVersionRange = text(manifest.get("cmsVersionRange"));

        // ---- 类型校验 ----
        String vendorName = null;
        Object vendor = manifest.get("vendor");
        if (vendor instanceof Map<?, ?> v) {
            vendorName = text(((Map<String, Object>) v).get("name"));
        } else if (vendor instanceof String s) {
            vendorName = s;
        }
        String licenseSpdx = licenseSpdx(manifest.get("license"));

        String runtimeType = null;
        Object runtime = manifest.get("templateRuntime");
        if (runtime instanceof Map<?, ?> r) {
            runtimeType = text(((Map<String, Object>) r).get("type"));
            if (runtimeType == null) {
                violations.add(Violation.blocking(com.powercms.common.error.ErrorCodes.SITE_PACKAGE_MANIFEST_INVALID,
                    "templateRuntime.type", "缺少 templateRuntime.type"));
            } else if (!RUNTIME_TYPES.contains(runtimeType.toUpperCase(Locale.ROOT))) {
                violations.add(Violation.blocking(com.powercms.common.error.ErrorCodes.SITE_PACKAGE_MANIFEST_INVALID,
                    "templateRuntime.type", "不支持的模板运行时类型: " + runtimeType));
            } else {
                runtimeType = runtimeType.toUpperCase(Locale.ROOT);
            }
        } else if (runtime != null) {
            violations.add(Violation.blocking(com.powercms.common.error.ErrorCodes.SITE_PACKAGE_MANIFEST_INVALID,
                "templateRuntime", "templateRuntime 必须是对象（含 type/nodeVersion）"));
        }

        Map<String, Object> entrypoints = new LinkedHashMap<>();
        Object rawEntrypoints = manifest.get("entrypoints");
        if (rawEntrypoints instanceof Map<?, ?> ep) {
            ep.forEach((k, v) -> entrypoints.put(String.valueOf(k), v));
            checkEntrypoints(entrypoints, props, violations);
        } else if (rawEntrypoints != null) {
            violations.add(Violation.blocking(com.powercms.common.error.ErrorCodes.SITE_PACKAGE_MANIFEST_INVALID,
                "entrypoints", "entrypoints 必须是对象（预览/构建/静态生成入口）"));
        }

        List<String> capabilities = new ArrayList<>();
        Object rawCapabilities = manifest.get("requiredCapabilities");
        if (rawCapabilities instanceof List<?> list) {
            for (Object item : list) {
                String value = text(item);
                if (value == null) {
                    violations.add(Violation.blocking(com.powercms.common.error.ErrorCodes.SITE_PACKAGE_MANIFEST_INVALID,
                        "requiredCapabilities", "能力条目必须是非空字符串"));
                } else {
                    capabilities.add(value);
                }
            }
        } else if (rawCapabilities != null) {
            violations.add(Violation.blocking(com.powercms.common.error.ErrorCodes.SITE_PACKAGE_MANIFEST_INVALID,
                "requiredCapabilities", "requiredCapabilities 必须是数组"));
        }

        // ---- CMS 版本兼容（FR-PACKAGE-002）----
        if (cmsVersionRange != null) {
            SemverRange range = SemverRange.parse(cmsVersionRange);
            if (range == null) {
                violations.add(Violation.blocking(com.powercms.common.error.ErrorCodes.SITE_PACKAGE_MANIFEST_INVALID,
                    "cmsVersionRange", "版本范围表达式无法解析: " + cmsVersionRange));
            } else if (!range.admits(props.getCmsVersion())) {
                violations.add(Violation.blocking(com.powercms.common.error.ErrorCodes.SITE_PACKAGE_VERSION_INCOMPATIBLE,
                    "cmsVersionRange", "清单要求 " + cmsVersionRange + "，当前 CMS 版本 " + props.getCmsVersion() + " 不满足"));
            }
        }

        // ---- 可执行/脚本文件门禁（FR-PACKAGE-003）----
        for (String path : scan.entries().keySet()) {
            String lower = path.toLowerCase(Locale.ROOT);
            for (String ext : props.getForbiddenExtensions()) {
                if (lower.endsWith(ext)) {
                    violations.add(Violation.blocking(com.powercms.common.error.ErrorCodes.SITE_PACKAGE_EXECUTABLE_FORBIDDEN,
                        path, "站点包不允许携带可执行/脚本文件（" + ext + "）；安装脚本不会被执行"));
                }
            }
        }

        Map<String, String> checksums = normalizeChecksums(manifest.get("checksums"), violations);
        if (props.isEnforceChecksums() && !checksums.isEmpty()) {
            verifyChecksums(scan, checksums, violations);
        }

        Map<String, Object> signature = normalizeSignature(manifest.get("signature"), violations);
        boolean signatureVerified = verifySignature(manifest, signature, props, violations);

        return Optional.of(new View(manifest, text(manifest.get("schemaVersion")), text(manifest.get("packageId")),
            text(manifest.get("name")), text(manifest.get("version")),
            siteKey != null ? siteKey : sanitizeKey(text(manifest.get("name"))),
            cmsVersionRange, vendorName, licenseSpdx, runtimeType, entrypoints, List.copyOf(capabilities),
            checksums, signature, signature != null, signatureVerified));
    }

    /** FR-PACKAGE-001 根结构：{@code models/} 至少一个模型、{@code template/package.json} 必须存在。 */
    private static void checkStructure(SitePackageArchive.Scan scan, List<Violation> violations) {
        List<String> models = scan.pathsMatching(p -> p.startsWith("models/") && p.endsWith(".yaml"));
        if (models.isEmpty()) {
            violations.add(Violation.blocking(com.powercms.common.error.ErrorCodes.SITE_PACKAGE_STRUCTURE_INVALID,
                "models/", "根目录缺少 models/*.yaml 内容模型定义"));
        }
        long templateFiles = scan.pathsMatching(p -> p.startsWith("template/")).size();
        if (templateFiles == 0) {
            violations.add(Violation.blocking(com.powercms.common.error.ErrorCodes.SITE_PACKAGE_STRUCTURE_INVALID,
                "template/", "根目录缺少 template/ 前端工程"));
        } else if (!scan.has("template/package.json")) {
            violations.add(Violation.blocking(com.powercms.common.error.ErrorCodes.SITE_PACKAGE_STRUCTURE_INVALID,
                "template/package.json", "template/ 存在文件但缺少 package.json，无法构建"));
        }
    }

    private static void checkRequiredFields(Map<String, Object> manifest, SitePackageProperties props,
                                            List<Violation> violations) {
        for (String field : REQUIRED_FIELDS) {
            Object value = manifest.get(field);
            if (value == null || (value instanceof String s && s.isBlank())) {
                violations.add(Violation.blocking(com.powercms.common.error.ErrorCodes.SITE_PACKAGE_MANIFEST_INVALID,
                    field, "缺少必填字段: " + field));
            }
        }
        if (props.getSignaturePolicy() == SitePackageProperties.SignaturePolicy.ENFORCE
            && manifest.get("signature") == null) {
            violations.add(Violation.blocking(com.powercms.common.error.ErrorCodes.SITE_PACKAGE_SIGNATURE_REQUIRED,
                "signature", "当前签名策略为 ENFORCE，站点包必须携带 signature"));
        }
    }

    /** 入口脚本策略：命令型入口必须是白名单内的单条命令，且不得含 shell 元字符。 */
    private static void checkEntrypoints(Map<String, Object> entrypoints, SitePackageProperties props,
                                         List<Violation> violations) {
        if (!entrypoints.containsKey("build")) {
            violations.add(Violation.blocking(com.powercms.common.error.ErrorCodes.SITE_PACKAGE_MANIFEST_INVALID,
                "entrypoints.build", "entrypoints 缺少 build 入口"));
        }
        entrypoints.forEach((key, value) -> {
            if (!props.getCommandEntrypoints().contains(key)) return; // output/dist 等为路径声明，不做命令校验
            String command = text(value);
            String object = "entrypoints." + key;
            if (command == null) {
                violations.add(Violation.blocking(com.powercms.common.error.ErrorCodes.SITE_PACKAGE_MANIFEST_INVALID,
                    object, "入口必须是非空字符串"));
                return;
            }
            if (command.contains("\n") || command.contains("\r")) {
                violations.add(Violation.blocking(com.powercms.common.error.ErrorCodes.SITE_PACKAGE_EXECUTABLE_FORBIDDEN,
                    object, "入口命令不允许跨行"));
                return;
            }
            if (command.chars().anyMatch(c -> ";|&$`<>\n".indexOf(c) >= 0)) {
                violations.add(Violation.blocking(com.powercms.common.error.ErrorCodes.SITE_PACKAGE_EXECUTABLE_FORBIDDEN,
                    object, "入口命令包含 shell 元字符，疑似注入: " + command));
                return;
            }
            boolean allowed = props.getAllowedEntrypointPrefixes().stream()
                .anyMatch(prefix -> command.trim().startsWith(prefix));
            if (!allowed) {
                violations.add(Violation.blocking(com.powercms.common.error.ErrorCodes.SITE_PACKAGE_EXECUTABLE_FORBIDDEN,
                    object, "入口命令不在白名单内（只允许声明的构建入口）: " + command));
            }
        });
    }

    /** {@code checksums} 既支持「路径 → 摘要」映射，也支持 {@code [{path, sha256}]} 列表（§8.2）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, String> normalizeChecksums(Object raw, List<Violation> violations) {
        Map<String, String> out = new TreeMap<>();
        if (raw instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                String digest = normalizeDigest(text(v));
                if (digest == null) {
                    violations.add(Violation.blocking(com.powercms.common.error.ErrorCodes.SITE_PACKAGE_MANIFEST_INVALID,
                        "checksums." + k, "摘要格式非法（应为 sha256:<hex> 或 <hex>）"));
                } else {
                    out.put(String.valueOf(k), digest);
                }
            });
        } else if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> entry)) {
                    violations.add(Violation.blocking(com.powercms.common.error.ErrorCodes.SITE_PACKAGE_MANIFEST_INVALID,
                        "checksums", "列表条目必须是 {path, sha256} 对象"));
                    continue;
                }
                Map<String, Object> e = (Map<String, Object>) entry;
                String path = text(e.get("path"));
                String digest = normalizeDigest(firstNonNull(text(e.get("sha256")), text(e.get("value"))));
                if (path == null || digest == null) {
                    violations.add(Violation.blocking(com.powercms.common.error.ErrorCodes.SITE_PACKAGE_MANIFEST_INVALID,
                        "checksums", "列表条目缺少 path 或合法 sha256"));
                } else {
                    out.put(path, digest);
                }
            }
        } else if (raw != null) {
            violations.add(Violation.blocking(com.powercms.common.error.ErrorCodes.SITE_PACKAGE_MANIFEST_INVALID,
                "checksums", "checksums 必须是映射或列表"));
        }
        return out;
    }

    /**
     * FR-PACKAGE-004：摘要必须覆盖包内全部非清单文件，且逐项与实际字节一致。
     * 篡改任一文件即失败；未声明的文件视为「未受完整性保护」。
     */
    private static void verifyChecksums(SitePackageArchive.Scan scan, Map<String, String> checksums,
                                        List<Violation> violations) {
        for (Map.Entry<String, String> declared : checksums.entrySet()) {
            byte[] data = scan.entries().get(declared.getKey());
            if (data == null) {
                violations.add(Violation.blocking(com.powercms.common.error.ErrorCodes.SITE_PACKAGE_MANIFEST_INVALID,
                    declared.getKey(), "checksums 声明的文件在包内不存在"));
                continue;
            }
            String actual = SitePackageSignature.sha256Hex(data);
            if (!SitePackageSignature.constantTimeEquals(declared.getValue(), actual)) {
                violations.add(Violation.blocking(com.powercms.common.error.ErrorCodes.SITE_PACKAGE_CHECKSUM_MISMATCH,
                    declared.getKey(), "摘要不匹配：清单 sha256:" + declared.getValue() + "，实际 sha256:" + actual));
            }
        }
        List<String> uncovered = new ArrayList<>();
        for (String path : scan.entries().keySet()) {
            if (MANIFEST_PATH.equals(path)) continue;
            if (!checksums.containsKey(path)) uncovered.add(path);
        }
        if (!uncovered.isEmpty()) {
            violations.add(Violation.blocking(com.powercms.common.error.ErrorCodes.SITE_PACKAGE_CHECKSUM_MISMATCH,
                "checksums", "以下文件未被 checksums 覆盖（无法检测篡改）: " + summarize(uncovered)));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalizeSignature(Object raw, List<Violation> violations) {
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> map)) {
            violations.add(Violation.blocking(com.powercms.common.error.ErrorCodes.SITE_PACKAGE_SIGNATURE_INVALID,
                "signature", "signature 必须是对象（algorithm/keyId/value）"));
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        ((Map<String, Object>) map).forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    /**
     * FR-PACKAGE-004：签名存在则必须可验证（任何策略下都不放过篡改）；
     * 签名缺失由策略决定（ENFORCE 已在必填项阶段阻断，WARN 记录告警，OFF 忽略）。
     */
    private static boolean verifySignature(Map<String, Object> manifest, Map<String, Object> signature,
                                           SitePackageProperties props, List<Violation> violations) {
        if (signature == null) {
            if (props.getSignaturePolicy() == SitePackageProperties.SignaturePolicy.WARN) {
                violations.add(Violation.warn(com.powercms.common.error.ErrorCodes.SITE_PACKAGE_SIGNATURE_REQUIRED,
                    "signature", "站点包未签名；当前策略为 WARN，仅记录并继续导入"));
            }
            return false;
        }
        String algorithm = text(signature.get("algorithm"));
        String keyId = text(signature.get("keyId"));
        String value = text(signature.get("value"));
        if (algorithm == null || !SitePackageSignature.ALGORITHM.equalsIgnoreCase(algorithm)) {
            violations.add(Violation.blocking(com.powercms.common.error.ErrorCodes.SITE_PACKAGE_SIGNATURE_INVALID,
                "signature.algorithm", "不支持的签名算法: " + algorithm + "（支持 " + SitePackageSignature.ALGORITHM + "）"));
            return false;
        }
        String secret = props.signingKey(keyId);
        if (secret == null) {
            violations.add(Violation.blocking(com.powercms.common.error.ErrorCodes.SITE_PACKAGE_SIGNATURE_INVALID,
                "signature.keyId", "签名密钥未配置: " + keyId));
            return false;
        }
        if (value == null) {
            violations.add(Violation.blocking(com.powercms.common.error.ErrorCodes.SITE_PACKAGE_SIGNATURE_INVALID,
                "signature.value", "签名值为空"));
            return false;
        }
        String expected = SitePackageSignature.sign(manifest, secret);
        if (!SitePackageSignature.constantTimeEquals(value, expected)) {
            violations.add(Violation.blocking(com.powercms.common.error.ErrorCodes.SITE_PACKAGE_SIGNATURE_INVALID,
                "signature.value", "签名校验失败（清单内容或摘要被篡改）"));
            return false;
        }
        return true;
    }

    /** 清单中的可选目录：当前实现不导入，作为冲突报告中的提示项（避免静默丢失）。 */
    static List<String> optionalSections(Map<String, byte[]> entries) {
        List<String> found = new ArrayList<>();
        for (String section : OPTIONAL_SECTIONS) {
            if (entries.keySet().stream().anyMatch(p -> p.startsWith(section))) found.add(section);
        }
        return found;
    }

    private static String licenseSpdx(Object license) {
        if (license instanceof Map<?, ?> map) {
            return firstNonNull(text(map.get("spdx")), text(map.get("name")));
        }
        return text(license);
    }

    private static String normalizeDigest(String value) {
        if (value == null) return null;
        String hex = value.toLowerCase(Locale.ROOT).startsWith("sha256:") ? value.substring(7) : value;
        return hex.length() == 64 && hex.chars().allMatch(c -> Character.digit(c, 16) >= 0)
            ? hex.toLowerCase(Locale.ROOT) : null;
    }

    static String text(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    private static String summarize(List<String> items) {
        return items.size() <= 5 ? String.join(", ", items)
            : String.join(", ", items.subList(0, 5)) + " 等 " + items.size() + " 个";
    }

    static String sanitizeKey(String s) {
        if (s == null) return "site";
        String key = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-").replaceAll("-+", "-")
            .replaceAll("^-|-$", "");
        return key.isBlank() ? "site" : key;
    }
}
