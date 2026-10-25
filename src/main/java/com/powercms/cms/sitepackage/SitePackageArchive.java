package com.powercms.cms.sitepackage;

import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 站点包归档解压与安全门禁（FR-PACKAGE-001/003、NFR-SEC-006）。
 *
 * <p>安全校验**先于**清单解析执行，因此压缩炸弹、路径逃逸、大小写冲突等夹具即使清单残缺
 * 也会被归类为安全违规（避免被「清单缺字段」掩盖）。所有检出项一次性汇总返回，
 * 便于定位到具体条目。</p>
 */
final class SitePackageArchive {

    private SitePackageArchive() {}

    /** 归档扫描结果。 */
    record Scan(Map<String, byte[]> entries, String digest, long totalUncompressedBytes,
                long declaredUncompressedBytes, int zipSizeBytes) {

        boolean has(String path) { return entries.containsKey(path); }

        List<String> pathsMatching(java.util.function.Predicate<String> predicate) {
            return entries.keySet().stream().filter(predicate).sorted().toList();
        }
    }

    private static final Set<Integer> SYMLIKE_FILE_TYPES = Set.of(0xA000, 0x2000, 0x6000);

    static Scan scan(byte[] zipData, SitePackageProperties props) {
        List<SitePackageViolations.Violation> violations = new ArrayList<>();
        String digest = SitePackageSignature.sha256Hex(zipData);

        long zipLimit = props.getMaxZipSizeMb() * 1024L * 1024L;
        if (zipData.length > zipLimit) {
            throw SitePackageViolations.blocking(ErrorCodes.SITE_PACKAGE_SECURITY_BLOCKED,
                "站点包超过 " + props.getMaxZipSizeMb() + "MB 上限", List.of(new SitePackageViolations.Violation(
                    ErrorCodes.SITE_PACKAGE_SECURITY_BLOCKED, SitePackageViolations.Severity.BLOCKING,
                    "archive", "包大小 " + zipData.length + " B 超过上限 " + zipLimit + " B")));
        }

        Map<String, byte[]> entries = new LinkedHashMap<>();
        Set<String> lowerCaseNames = new LinkedHashSet<>();
        List<String> caseCollisions = new ArrayList<>();
        List<String> symlinkEntries = new ArrayList<>();
        long total = 0;
        long declared = 0;
        int fileCount = 0;
        int entryIndex = 0;

        try (var zin = new ZipInputStream(new ByteArrayInputStream(zipData))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                entryIndex++;
                String name = entry.getName();
                if (entry.isDirectory() || name.endsWith("/")) continue;

                fileCount++;
                if (fileCount > props.getMaxFileCount()) {
                    violations.add(new SitePackageViolations.Violation(
                        ErrorCodes.SITE_PACKAGE_SECURITY_BLOCKED, SitePackageViolations.Severity.BLOCKING,
                        "archive", "文件数超过 " + props.getMaxFileCount() + " 上限"));
                    break;
                }

                if (!lowerCaseNames.add(name.toLowerCase(Locale.ROOT))) {
                    caseCollisions.add(name);
                }

                String pathProblem = pathProblem(name);
                if (pathProblem != null) {
                    violations.add(new SitePackageViolations.Violation(
                        ErrorCodes.SITE_PACKAGE_SECURITY_BLOCKED, SitePackageViolations.Severity.BLOCKING,
                        name, pathProblem));
                }

                long entryLimit = props.getMaxSingleFileMb() * 1024L * 1024L;
                if (entry.getSize() > entryLimit) {
                    violations.add(new SitePackageViolations.Violation(
                        ErrorCodes.SITE_PACKAGE_SECURITY_BLOCKED, SitePackageViolations.Severity.BLOCKING,
                        name, "声明大小 " + entry.getSize() + " B 超过单文件上限 " + entryLimit + " B"));
                }
                if (isSymlinkLike(entry)) {
                    symlinkEntries.add(name);
                }

                byte[] data = zin.readAllBytes();
                if (data.length > entryLimit) {
                    violations.add(new SitePackageViolations.Violation(
                        ErrorCodes.SITE_PACKAGE_SECURITY_BLOCKED, SitePackageViolations.Severity.BLOCKING,
                        name, "实际大小 " + data.length + " B 超过单文件上限 " + entryLimit + " B"));
                }
                if (entry.getSize() >= 0 && data.length > entry.getSize() * 4 && data.length > 1024 * 1024) {
                    violations.add(new SitePackageViolations.Violation(
                        ErrorCodes.SITE_PACKAGE_SECURITY_BLOCKED, SitePackageViolations.Severity.BLOCKING,
                        name, "实际大小 " + data.length + " B 明显超过声明大小 " + entry.getSize() + " B"));
                }

                total += data.length;
                declared += Math.max(0, entry.getSize());
                entries.put(name, data);

                long totalLimit = props.getMaxUncompressedMb() * 1024L * 1024L;
                if (total > totalLimit) {
                    violations.add(new SitePackageViolations.Violation(
                        ErrorCodes.SITE_PACKAGE_SECURITY_BLOCKED, SitePackageViolations.Severity.BLOCKING,
                        "archive", "累计解压量超过 " + props.getMaxUncompressedMb() + "MB 上限"));
                    break;
                }
            }
        } catch (IOException e) {
            throw new BusinessException(ErrorCodes.BAD_REQUEST, "ZIP 包解析失败: " + e.getMessage());
        }

        if (fileCount == 0) {
            throw new BusinessException(ErrorCodes.SITE_PACKAGE_STRUCTURE_INVALID, "站点包内没有任何文件");
        }
        if (!caseCollisions.isEmpty()) {
            violations.add(new SitePackageViolations.Violation(
                ErrorCodes.SITE_PACKAGE_SECURITY_BLOCKED, SitePackageViolations.Severity.BLOCKING,
                "archive", "存在大小写冲突条目（跨平台解压会相互覆盖）：" + String.join(", ", caseCollisions)));
        }
        if (!symlinkEntries.isEmpty()) {
            violations.add(new SitePackageViolations.Violation(
                ErrorCodes.SITE_PACKAGE_SECURITY_BLOCKED, SitePackageViolations.Severity.BLOCKING,
                "archive", "包含符号链接/设备文件条目：" + String.join(", ", symlinkEntries)));
        }
        if (zipData.length > 0 && total / (double) zipData.length > props.getMaxCompressionRatio()) {
            violations.add(new SitePackageViolations.Violation(
                ErrorCodes.SITE_PACKAGE_SECURITY_BLOCKED, SitePackageViolations.Severity.BLOCKING,
                "archive", String.format("压缩比 %.1f:1 超过上限 %d:1（解压总量 %d B / 包大小 %d B）",
                    total / (double) zipData.length, props.getMaxCompressionRatio(), total, zipData.length)));
        }

        if (!violations.isEmpty()) {
            throw SitePackageViolations.blocking(ErrorCodes.SITE_PACKAGE_SECURITY_BLOCKED,
                "站点包未通过安全门禁（检出 " + violations.size() + " 项）", violations);
        }
        return new Scan(entries, digest, total, declared, zipData.length);
    }

    /** 路径逃逸判定：绝对路径、驱动器路径、父目录跳转、反斜杠。 */
    static String pathProblem(String name) {
        if (name == null || name.isBlank()) return "条目名为空";
        if (name.contains("\\")) return "路径包含反斜杠（Windows 分隔符）";
        if (name.startsWith("/")) return "绝对路径";
        if (name.length() > 1 && name.charAt(1) == ':') return "驱动器绝对路径";
        for (String segment : name.split("/")) {
            if ("..".equals(segment)) return "父目录跳转";
        }
        return null;
    }

    /**
     * 通过 PKWARE Unix（0x000d）扩展字段识别符号链接与设备文件。
     * 解析失败时按「非链接」处理，避免误阻断合法包。
     */
    private static boolean isSymlinkLike(ZipEntry entry) {
        byte[] extra = entry.getExtra();
        if (extra == null || extra.length < 4) return false;
        int offset = 0;
        while (offset + 4 <= extra.length) {
            int id = (extra[offset] & 0xFF) | ((extra[offset + 1] & 0xFF) << 8);
            int size = (extra[offset + 2] & 0xFF) | ((extra[offset + 3] & 0xFF) << 8);
            int dataStart = offset + 4;
            if (size < 0 || dataStart + size > extra.length) break;
            if (id == 0x000D && size >= 5) {
                int mode = ((extra[dataStart + 1] & 0xFF) << 24)
                    | ((extra[dataStart + 2] & 0xFF) << 16)
                    | ((extra[dataStart + 3] & 0xFF) << 8)
                    | (extra[dataStart + 4] & 0xFF);
                if (SYMLIKE_FILE_TYPES.contains(mode & 0xF000)) return true;
            }
            offset = dataStart + size;
        }
        return false;
    }

    /** 名称前缀统计工具（安全扫描与清单校验共用）。 */
    static Map<String, byte[]> filterPrefix(Map<String, byte[]> entries, String prefix) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        entries.forEach((k, v) -> { if (k.startsWith(prefix)) out.put(k, v); });
        return out;
    }
}
