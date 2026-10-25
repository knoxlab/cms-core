package com.powercms.cms.workflow;

/**
 * 版本能力门禁端口（依赖倒置，模式同 {@code WorkflowUserAccess}）。
 *
 * <p><b>为什么需要端口</b>：工作流的版本分级校验（矩阵 E1/E3）需要读取授权能力位，
 * 但 {@code workflow → platform} 会构成 Spring Modulith 循环——{@code platform} 的
 * {@code DashboardController}/{@code SiteAuthorization} 已经依赖 {@code workflow}
 * （见 {@code platform/package-info.java} 的 allowedDependencies），把 {@code platform}
 * 加进 {@code workflow} 的 allowedDependencies 只会把循环合法化（ContentService 的
 * 审核待办端口注释记录了同一结论）。因此接口定义在 {@code workflow}，由 {@code platform}
 * 模块提供实现 {@code WorkflowEntitlementAdapter}，编译期依赖方向仍是单向的 platform → workflow。</p>
 *
 * <p><b>语义</b>：能力名取 {@code LicenseCapability} 枚举名（大写蛇形）。未授权时实现抛出
 * {@code LICENSE_CAPABILITY_DISABLED}（HTTP 403）；授权功能关闭或 enforce 开关关闭时放行。</p>
 */
public interface WorkflowEntitlementPort {

    /** 断言当前授权允许该能力；未授权抛 403 {@code LICENSE_CAPABILITY_DISABLED}。 */
    void requireCapability(String capabilityName);

    /** 只读判断，不抛异常。用于运行期降级兼容（如遗留会签按单人审批继续）。 */
    boolean allowsCapability(String capabilityName);

    /** 当前版本分档：0=社区 1=专业 2=企业 3=信创（矩阵 ◐/✅ 的版本分档判定用）。 */
    int editionRank();
}
