package com.powercms.cms.delivery;

import graphql.analysis.MaxQueryComplexityInstrumentation;
import graphql.analysis.MaxQueryDepthInstrumentation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.graphql.GraphQlSourceBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;

/**
 * GraphQL depth/complexity 上限（规格 H3）——**有效上限 = min(配置值, License 配额)**。
 *
 * <p>§1.4 规则 4：门禁必须落在服务端、不得只依赖配置项默认值——所以配置只能在 License
 * 上限**之下**再往下调（站点策略），不能通过改 {@code powercms.graphql.max-depth} 把上限
 * 抬过当前版本的 License 配额（堵住「改配置绕过版本边界」）。配额 {@code -1} 表示不限；
 * 授权模块不存在（单测）或被禁用时等同只用配置值，不额外收紧。</p>
 *
 * <p><b>授权值经由 {@link DeliveryEntitlementPort} 读取</b>：{@code delivery → platform}
 * 是 Spring Modulith 禁止的反向依赖（{@code platform} 的 allowedDependencies 已含
 * {@code delivery}），故配额天花板通过端口注入，实现在
 * {@code platform.license.DeliveryEntitlementAdapter}；{@code ports} 为 null、
 * 或端口拿不到授权时等同只用配置值。</p>
 *
 * <p><b>求值时机（重要）</b>：depth 上限在本类的 {@link GraphQlSourceBuilderCustomizer}
 * 被调用、即 {@code GraphQlSource} 构建（Bean 初始化）时一次性求值并固化进
 * instrumentation——因此 license 配额作为天花板是**启动期固化**的：运行期换发授权抬高
 * 天花板需要重启进程才生效；配置侧无论何时都仍受同一天花板约束，不会越权。
 * 与之相对，page 上限在 {@link DeliveryGraphqlController} 内**逐请求**求值，换授权立即生效。</p>
 *
 * <p>complexity 没有对应的 License 配额键（规格 H3 只规定 page/depth 由配额决定，
 * §1.2 固定 100），因此只取配置值。</p>
 */
@Configuration
class GraphqlLimitsConfig {
    @Bean
    GraphQlSourceBuilderCustomizer deliveryGraphqlLimits(
        @Value("${powercms.graphql.max-depth:8}") int maxDepth,
        @Value("${powercms.graphql.max-complexity:100}") int maxComplexity,
        ObjectProvider<DeliveryEntitlementPort> entitlements) {
        // 启动期天花板：min(配置 depth, License GRAPHQL_MAX_DEPTH)
        int effectiveDepth = depthLimit(maxDepth, entitlements);
        return builder -> builder.instrumentation(List.of(
            new MaxQueryDepthInstrumentation(effectiveDepth),
            new MaxQueryComplexityInstrumentation(maxComplexity)));
    }

    /** page 天花板（逐请求求值入口，Controller 每次请求调一次）。 */
    static int pageLimit(int configured, DeliveryEntitlementPort entitlement) {
        return entitlement == null || !entitlement.enabled()
            ? configured : ceiling(configured, entitlement.maxPageSize());
    }

    /** depth 天花板（启动期固化入口）。 */
    static int depthLimit(int configured, DeliveryEntitlementPort entitlement) {
        return entitlement == null || !entitlement.enabled()
            ? configured : ceiling(configured, entitlement.maxDepth());
    }

    /** {@code ports} 为 null、或其中无端口实现（单测姿态）→ 等同只用配置值。 */
    static int pageLimit(int configured, ObjectProvider<DeliveryEntitlementPort> ports) {
        return pageLimit(configured, ports == null ? null : ports.getIfAvailable());
    }

    /** 同上。 */
    static int depthLimit(int configured, ObjectProvider<DeliveryEntitlementPort> ports) {
        return depthLimit(configured, ports == null ? null : ports.getIfAvailable());
    }

    /**
     * 有效上限 = min(配置值, License 配额)：配置可往下调（站点策略），不可越过 License 天花板。
     *
     * @param configured 配置值（powercms.graphql.*）
     * @param quota      当前授权的配额值；{@code -1} 表示不限 → 返回配置值
     */
    private static int ceiling(int configured, long quota) {
        if (quota < 0) return configured;
        return (int) Math.min(configured, quota);
    }
}
