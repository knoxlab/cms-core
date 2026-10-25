package com.powercms.cms.workflow;

import java.util.Optional;
import java.util.UUID;

/**
 * Site-scoped reviewer lookup without a hard Identity module import.
 */
public interface WorkflowUserAccess {
    boolean canReview(String username, UUID siteId);
    Optional<String> findSiteAdminUsername(UUID siteId);
    default boolean isPeerReviewRequired(UUID siteId) { return false; }

    /**
     * 用户是否有权**查看**该内容（对象级 view 权利，含站点能力兜底；内容不存在按无权处理）。
     *
     * <p>待办列表用它隐藏「审核人已看不到的内容」（权限被收回、内容被删除等）——
     * 这类任务即使指派人仍是本人，也不该出现在待办里（点开只会 403/404）。</p>
     *
     * <p>缺省放行：宿主未实现该判定（如单测直构服务）时不因该能力缺失把待办全部清空。</p>
     */
    default boolean canViewContent(String username, UUID contentId) { return true; }
}
