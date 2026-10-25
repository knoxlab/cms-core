package com.powercms.cms.sitepackage;

import com.powercms.common.error.ApiError;
import com.powercms.common.error.BusinessException;

import java.util.ArrayList;
import java.util.List;

/**
 * 站点包校验违规定义与错误装配（FR-PACKAGE-002/003/004/006）。
 *
 * <p>站点包校验必须「一次列出全部问题」而不是首个即返回：导入方通常需要一次性修完清单、
 * 摘要与模型定义，逐条试错成本过高。因此各校验阶段累积 {@link Violation}，
 * 由 {@link #blocking} 统一装配为携带字段级 details 的 4xx。</p>
 */
public final class SitePackageViolations {

    /** 违规严重度。{@code BLOCKING} 阻断导入；{@code WARN} 仅提示（进入冲突报告）。 */
    public enum Severity { BLOCKING, WARN }

    /**
     * 单条违规。
     *
     * @param code     稳定错误码（与 {@code ErrorCodes} 对应，供前端/探针判定）
     * @param severity 严重度
     * @param object   违规对象（包内路径、模型编码、能力名等）
     * @param detail   可读原因
     */
    public record Violation(String code, Severity severity, String object, String detail) {
        public static Violation blocking(String code, String object, String detail) {
            return new Violation(code, Severity.BLOCKING, object, detail);
        }

        public static Violation warn(String code, String object, String detail) {
            return new Violation(code, Severity.WARN, object, detail);
        }
    }

    private SitePackageViolations() {}

    static boolean hasBlocking(List<Violation> violations) {
        return violations.stream().anyMatch(v -> v.severity() == Severity.BLOCKING);
    }

    static List<Violation> blockingOnly(List<Violation> violations) {
        return violations.stream().filter(v -> v.severity() == Severity.BLOCKING).toList();
    }

    /** 装配阻断异常：错误码 + 汇总文案 + 逐条 details。 */
    static BusinessException blocking(String errorCode, String summary, List<Violation> violations) {
        return new BusinessException(errorCode, summary, details(violations));
    }

    static List<ApiError.FieldError> details(List<Violation> violations) {
        List<ApiError.FieldError> details = new ArrayList<>();
        for (Violation v : violations) {
            if (v.severity() != Severity.BLOCKING) continue;
            details.add(new ApiError.FieldError(v.code() + "@" + v.object(), v.detail()));
        }
        return List.copyOf(details);
    }

    /** 阻断原因摘要（用于 message，details 仍携带逐条明细）。 */
    static String summary(List<Violation> violations) {
        List<Violation> blocking = blockingOnly(violations);
        if (blocking.isEmpty()) return "无阻断项";
        List<String> items = blocking.stream().limit(3).map(Violation::detail).toList();
        String head = String.join("；", items);
        return blocking.size() <= 3 ? head : head + "；等共 " + blocking.size() + " 项";
    }
}
