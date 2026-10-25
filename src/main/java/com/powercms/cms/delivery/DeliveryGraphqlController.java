package com.powercms.cms.delivery;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;
import java.util.List;
import java.util.UUID;

@Controller
public class DeliveryGraphqlController {
    private final DeliveryService service;
    /** 配置值 {@code powercms.graphql.max-page-size}；有效上限 = min(配置, License 配额)，逐请求求值。 */
    private final int maxPageSize;

    /** License 天花板端口（{@code delivery} 不得依赖 {@code platform}，实现在 platform 侧）；单测直接 new 控制器时为 null（等同只用配置值）。 */
    private ObjectProvider<DeliveryEntitlementPort> entitlements;

    @Autowired(required = false)
    public void setEntitlements(ObjectProvider<DeliveryEntitlementPort> entitlements) {
        this.entitlements = entitlements;
    }

    DeliveryGraphqlController(DeliveryService service, @Value("${powercms.graphql.max-page-size:100}") int maxPageSize) { this.service = service; this.maxPageSize = maxPageSize; }

    @QueryMapping
    List<DeliveryService.DeliveryView> contents(@Argument UUID siteId, @Argument UUID categoryId,
                                                @Argument UUID contentTypeId, @Argument String locale,
                                                @Argument Integer page, @Argument Integer size) {
        // H3（§1.4 规则 4）：page 上限 = min(配置, GRAPHQL_MAX_PAGE 配额)，每请求重新求值——
        // 运行期换发授权抬高/压低天花板立即生效；配置只能在 License 天花板之下再收紧。
        int effectiveMaxPage = GraphqlLimitsConfig.pageLimit(maxPageSize, entitlements);
        int safePage = Math.max(page == null ? 0 : page, 0);
        int safeSize = Math.min(Math.max(size == null ? 20 : size, 1), effectiveMaxPage);
        return service.listPublishedViews(siteId, categoryId, safePage, safeSize).stream()
            .filter(item -> contentTypeId == null || contentTypeId.equals(item.contentTypeId())).toList();
    }

    @QueryMapping
    DeliveryService.DeliveryView content(@Argument UUID siteId, @Argument UUID id, @Argument String locale) {
        try { return service.getViewById(siteId, id); }
        catch (RuntimeException notFound) { return null; }
    }
}
