package com.powercms.cms.sitepackage;

import com.powercms.common.error.ApiError;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.powercms.cms.sitepackage.SitePackagePrecheck.Conflict;

/**
 * 站点包冲突分析（FR-PACKAGE-005：模型、模板、内容、资源、权限和能力冲突报告）。
 *
 * <p>仅在**目标站点创建之前**可判定的冲突才在此报告：站点 key 占用、packageId/version 重复、
 * 保留系统模型覆盖、能力不满足，以及当前实现未导入的可选目录提示。内容/资源级冲突
 * （FR-PACKAGE-005 的 content/assets）随导入实现补齐，这里以 {@code SECTION} 提示项显式暴露，
 * 不做静默丢弃。</p>
 */
final class SitePackageConflicts {

    static final String SEVERITY_BLOCKING = "BLOCKING";
    static final String SEVERITY_CONFIRMABLE = "CONFIRMABLE";
    static final String SEVERITY_WARN = "WARN";

    /** 可确认冲突的默认处理策略集（{@code FAIL} 始终隐含可用，代表「不导入」）。 */
    static final List<String> DEFAULT_RESOLUTIONS = List.of("RENAME", "OVERWRITE", "SKIP");

    private SitePackageConflicts() {}

    /**
     * 计算冲突清单。
     *
     * @param siteKeyExists           目标站点 key 是否已被占用
     * @param packageAlreadyImported  同 packageId+version 是否已导入过
     */
    static List<Conflict> analyze(String siteKey, String packageId, String version,
                                  List<String> modelCodes, List<String> capabilities,
                                  Map<String, byte[]> entries, SitePackageProperties props,
                                  boolean siteKeyExists, boolean packageAlreadyImported) {
        List<Conflict> conflicts = new ArrayList<>();

        if (siteKeyExists) {
            conflicts.add(new Conflict("SITE", "site", siteKey, SEVERITY_CONFIRMABLE, List.of("RENAME"),
                "站点 key 已存在：" + siteKey + "；可按 RENAME 生成新 key 后导入"));
        }
        if (packageAlreadyImported) {
            // 来源记录是元数据：重复导入同一 packageId/version 属正常操作（一个包可用于多个站点），
            // 记录会被更新为指向本次导入的站点，因此只作为提示项，不要求确认策略。
            conflicts.add(new Conflict("PACKAGE", "site-package", packageId + "@" + version, SEVERITY_WARN,
                List.of(), "同 packageId+version 的导入记录已存在，来源记录将更新为指向本次导入的站点"));
        }
        for (String code : modelCodes) {
            if (SitePackageModels.isReserved(code, props)) {
                conflicts.add(new Conflict("MODEL", "content-type", code, SEVERITY_BLOCKING, List.of(),
                    "模型编码命中系统保留前缀，站点包不得覆盖系统模型（" + code + "）"));
            }
        }
        for (String capability : capabilities) {
            String name = capability.split(":", 2)[0].trim().toLowerCase(Locale.ROOT);
            boolean supported = props.getSupportedCapabilities().stream()
                .anyMatch(s -> s.toLowerCase(Locale.ROOT).equals(name));
            if (!supported) {
                conflicts.add(new Conflict("CAPABILITY", "capability", capability, SEVERITY_BLOCKING, List.of(),
                    "当前实例不具备清单要求的能力：" + capability));
            }
        }
        for (String section : SitePackageManifest.optionalSections(entries)) {
            conflicts.add(new Conflict("SECTION", "section", section, SEVERITY_WARN, List.of(),
                section + " 目录当前不会被导入（内容/资源迁移尚未实现），包内该部分不会复制"));
        }
        return conflicts;
    }

    static boolean hasBlocking(List<Conflict> conflicts) {
        return conflicts.stream().anyMatch(c -> SEVERITY_BLOCKING.equals(c.severity()));
    }

    static boolean hasConfirmable(List<Conflict> conflicts) {
        return conflicts.stream().anyMatch(c -> SEVERITY_CONFIRMABLE.equals(c.severity()));
    }

    /** 汇总所有可确认冲突的处理策略（保持稳定顺序，供前端单选）。 */
    static List<String> resolutions(List<Conflict> conflicts) {
        Set<String> out = new LinkedHashSet<>();
        for (String candidate : DEFAULT_RESOLUTIONS) {
            boolean applicable = conflicts.stream()
                .filter(c -> SEVERITY_CONFIRMABLE.equals(c.severity()))
                .allMatch(c -> c.resolutions().isEmpty() || c.resolutions().contains(candidate));
            if (applicable) out.add(candidate);
        }
        return List.copyOf(out);
    }

    /** 校验选定策略是否覆盖全部可确认冲突。 */
    static boolean strategyApplicable(List<Conflict> conflicts, String strategy) {
        return conflicts.stream()
            .filter(c -> SEVERITY_CONFIRMABLE.equals(c.severity()))
            .allMatch(c -> c.resolutions().isEmpty() || c.resolutions().contains(strategy));
    }

    /** 可确认冲突的字段级明细（导入在缺少确认时随 409 返回，前端可直接渲染）。 */
    static List<ApiError.FieldError> confirmableDetails(List<Conflict> conflicts) {
        return conflicts.stream()
            .filter(c -> SEVERITY_CONFIRMABLE.equals(c.severity()))
            .map(c -> new ApiError.FieldError(c.type() + "@" + c.objectKey(),
                c.detail() + "（可选策略：" + String.join("/", c.resolutions()) + "）"))
            .toList();
    }

    /** 全部冲突的字段级明细（含 WARN，用于报告落库/日志）。 */
    static List<ApiError.FieldError> allDetails(List<Conflict> conflicts) {
        return conflicts.stream()
            .map(c -> new ApiError.FieldError(c.type() + "@" + c.objectKey(),
                "[" + c.severity() + "] " + c.detail()))
            .toList();
    }
}
