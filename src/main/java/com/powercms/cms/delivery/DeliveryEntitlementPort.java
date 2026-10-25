package com.powercms.cms.delivery;

/**
 * 版本授权天花板端口（依赖倒置，模式同 {@code workflow.WorkflowEntitlementPort}）。
 *
 * <p><b>为什么需要端口</b>：规格 H3（§1.4 规则 4）要求 GraphQL 的 page/depth 上限
 * 「有效上限 = min(配置值, License 配额)」，但 {@code delivery → platform} 会破坏
 * Spring Modulith 边界——{@code platform} 的 allowedDependencies 已经包含
 * {@code delivery}（{@code platform/package-info.java}），把 {@code platform} 加进
 * {@code delivery} 只会把循环合法化。因此接口定义在 {@code delivery}，由
 * {@code platform} 模块提供实现 {@code DeliveryEntitlementAdapter}，
 * 编译期依赖方向仍是单向的 platform → delivery。</p>
 *
 * <p><b>语义</b>：三个方法都按「读取当前授权快照」求值，逐请求/逐构建重新读取——
 * 运行期换发授权抬高或压低天花板立即（page）或重启后（depth）生效；
 * 返回 {@code -1} 表示该配额不限；{@link #enabled()} 为 {@code false}
 * （授权模块关闭或 enforce 开关关闭）时调用方只用配置值，不额外收紧。</p>
 */
public interface DeliveryEntitlementPort {

    /** 授权门禁是否生效；{@code false} = 等同只用配置值（单测/授权模块禁用姿态）。 */
    boolean enabled();

    /** {@code GRAPHQL_MAX_PAGE} 配额；{@code -1} 表示不限。 */
    long maxPageSize();

    /** {@code GRAPHQL_MAX_DEPTH} 配额；{@code -1} 表示不限。 */
    long maxDepth();
}
