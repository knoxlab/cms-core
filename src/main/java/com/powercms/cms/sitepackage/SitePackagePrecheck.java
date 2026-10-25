package com.powercms.cms.sitepackage;

import java.util.List;

/**
 * 站点包导入前冲突报告（FR-PACKAGE-005）与预检响应契约。
 *
 * <p>预检与导入共用同一套校验（{@code SitePackageService#validate}），因此报告中列出的
 * {@code violations}/{@code conflicts} 就是导入会阻断或要求确认的同一批结论——避免
 * 「预检通过、导入失败」的不一致。</p>
 */
public final class SitePackagePrecheck {

    /**
     * 冲突条目。
     *
     * @param type        冲突类别（SITE/PACKAGE/MODEL/CAPABILITY/SECTION）
     * @param objectType  对象类型（site、site-package、content-type、capability…）
     * @param objectKey   对象标识（siteKey、packageId@version、模型编码…）
     * @param severity    BLOCKING（无解，必须改包）/ CONFIRMABLE（可确认策略）/ WARN（仅提示）
     * @param resolutions 可用的处理策略，BLOCKING/WARN 时为空
     * @param detail      可读说明
     */
    public record Conflict(String type, String objectType, String objectKey, String severity,
                           List<String> resolutions, String detail) {}

    /** 签名校验结论。 */
    public record SignatureInfo(String policy, boolean present, String algorithm, String keyId,
                                boolean verified, String detail) {}

    /**
     * 可确认冲突的处理选项：{@code strategy} 为策略名，{@code token} 为该策略对应的确认令牌
     * （导入时原样回传，服务端据此确认「运维人员已看过报告并选定策略」）。
     */
    public record Resolution(String strategy, String token) {}

    /** 预检报告。 */
    public record Report(
        String packageDigest,
        String packageId,
        String name,
        String version,
        String schemaVersion,
        String siteKey,
        int fileCount,
        long zipSizeBytes,
        long totalUncompressedBytes,
        String cmsVersion,
        String cmsVersionRange,
        boolean versionCompatible,
        SignatureInfo signature,
        List<SitePackageViolations.Violation> violations,
        List<Conflict> conflicts,
        boolean blocked,
        boolean confirmable,
        List<Resolution> resolutions) {}

    private SitePackagePrecheck() {}
}
