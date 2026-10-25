package com.powercms.cms.platform.web;

import com.powercms.cms.audit.entity.AuditEventEntity;
import com.powercms.cms.audit.repo.AuditEventRepository;
import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.identity.AuthenticatedUserPrincipal;
import com.powercms.cms.identity.SiteCapabilityEvaluator;
import com.powercms.cms.workflow.entity.WorkflowTaskEntity;
import com.powercms.cms.workflow.repo.WorkflowTaskRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 工作台（Dashboard）聚合查询（参照 CrafterCMS 作者仪表盘）：
 * 1 Activity（全站最近操作，read）、2 Pending Approval（待审任务，review）、
 * 3 Scheduled for Publish（定时发布，review）、4 Recently Published（最近发布，deploy）、
 * 5 My Activity（我的操作，read）、6 Unpublished Work（我的未发布，read）、
 * 7 Expiring（即将到期撤回，read）。
 */
@RestController
@RequestMapping("/api/v1")
class DashboardController {

    private static final List<String> UNPUBLISHED_STATUSES = List.of(
        ContentItemEntity.STATUS_DRAFT, ContentItemEntity.STATUS_IN_REVIEW,
        ContentItemEntity.STATUS_APPROVED, ContentItemEntity.STATUS_WITHDRAWN);

    private final ContentItemRepository itemRepo;
    private final WorkflowTaskRepository taskRepo;
    private final AuditEventRepository auditRepo;
    private final com.powercms.cms.identity.ActorNameResolver actorNames;
    private final SiteCapabilityEvaluator capabilityEvaluator;

    DashboardController(ContentItemRepository itemRepo, WorkflowTaskRepository taskRepo,
                        AuditEventRepository auditRepo,
                        com.powercms.cms.identity.ActorNameResolver actorNames,
                        SiteCapabilityEvaluator capabilityEvaluator) {
        this.itemRepo = itemRepo;
        this.taskRepo = taskRepo;
        this.auditRepo = auditRepo;
        this.actorNames = actorNames;
        this.capabilityEvaluator = capabilityEvaluator;
    }

    /** 单条内容摘要（卡片行）。 */
    public record ContentCardItem(UUID id, String title, String status, String routePath,
                                  String createdBy, Instant updatedAt, Instant scheduledPublishAt,
                                  Instant scheduledWithdrawAt) {
        static ContentCardItem of(ContentItemEntity item) {
            return new ContentCardItem(item.getId(), item.getTitle(), item.getStatus(), item.getRoutePath(),
                item.getCreatedBy(), item.getUpdatedAt(), item.getScheduledPublishAt(), item.getScheduledWithdrawAt());
        }
    }

    /** 待审批行：任务 + 内容标题。 */
    public record PendingApprovalItem(UUID taskId, UUID contentItemId, String title, String submitter,
                                      String assignee, String sourceStatus, String targetStatus,
                                      Instant createdAt, Instant scheduledAt) {}

    /** 最近发布行：发布版本 + 发布时间（发布时会刷新 updatedAt，取其近似）。 */
    public record RecentlyPublishedItem(UUID id, String title, String routePath, int publishedVersion,
                                        String createdBy, Instant publishedAt) {}

    /** 活动流行：审计事件 + 解析后的操作对象标题与操作人展示名。 */
    public record ActivityItem(Instant occurredAt, String actor, String actorName, String action,
                               String objectType, String objectId, String title, String result) {}

    public record ActivityResponse(List<ActivityItem> items, long total) {}

    /** 1. Activity：全站最近管理操作（审计事件流）。 */
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @GetMapping("/sites/{siteId}/dashboard/activity")
    public ActivityResponse activity(@PathVariable UUID siteId,
                                     @RequestParam(defaultValue = "20") int limit) {
        var events = auditRepo.findBySiteIdOrderByOccurredAtDesc(siteId, PageRequest.of(0, clamp(limit)));
        TitleResolver resolver = new TitleResolver();
        List<ActivityItem> rows = new ArrayList<>();
        for (AuditEventEntity e : events.getContent()) {
            if (isContentMeaningful(e)) rows.add(toActivityItem(e, resolver));
        }
        return new ActivityResponse(rows, events.getTotalElements());
    }

    /** 5. My Activity：当前用户在站点内的最近操作（actor 可能记为 userId，两路查询合并）。 */
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @GetMapping("/sites/{siteId}/dashboard/my-activity")
    public ActivityResponse myActivity(@PathVariable UUID siteId,
                                       @RequestParam(defaultValue = "20") int limit,
                                       Authentication authentication) {
        Pageable page = PageRequest.of(0, clamp(limit));
        var events = new ArrayList<>(auditRepo
            .findBySiteIdAndActorOrderByOccurredAtDesc(siteId, authentication.getName(), page).getContent());
        if (authentication.getPrincipal() instanceof AuthenticatedUserPrincipal principal) {
            events.addAll(auditRepo
                .findBySiteIdAndActorOrderByOccurredAtDesc(siteId, principal.getUserId().toString(), page)
                .getContent());
        }
        TitleResolver resolver = new TitleResolver();
        Map<String, ActivityItem> merged = new LinkedHashMap<>();
        for (AuditEventEntity e : events) {
            if (isContentMeaningful(e)) merged.putIfAbsent(dedupKey(e), toActivityItem(e, resolver));
        }
        List<ActivityItem> rows = merged.values().stream()
            .sorted((a, b) -> b.occurredAt().compareTo(a.occurredAt()))
            .limit(clamp(limit))
            .toList();
        return new ActivityResponse(rows, rows.size());
    }

    /** 2. Pending Approval：站点内全部待审任务（review/admin 可见）。 */
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'review')")
    @GetMapping("/sites/{siteId}/dashboard/pending-approval")
    public List<PendingApprovalItem> pendingApproval(@PathVariable UUID siteId,
                                                     @RequestParam(defaultValue = "20") int limit) {
        return taskRepo.findBySiteIdAndStatusOrderByCreatedAtDesc(siteId, WorkflowTaskEntity.STATUS_PENDING)
            .stream()
            .limit(clamp(limit))
            .map(t -> {
                String title = itemRepo.findById(t.getContentItemId())
                    .map(ContentItemEntity::getTitle).orElse("(内容已删除)");
                return new PendingApprovalItem(t.getId(), t.getContentItemId(), title, t.getSubmitter(),
                    t.getAssignee(), t.getSourceStatus(), t.getTargetStatus(), t.getCreatedAt(), t.getScheduledAt());
            })
            .toList();
    }

    /** 3. Scheduled for Publish：已批准且设置了定时发布时间的内容（review/admin 可见）。 */
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'review')")
    @GetMapping("/sites/{siteId}/dashboard/scheduled")
    public List<ContentCardItem> scheduled(@PathVariable UUID siteId,
                                           @RequestParam(defaultValue = "20") int limit) {
        return itemRepo.findBySiteIdAndStatusAndScheduledPublishAtIsNotNullOrderByScheduledPublishAtAsc(
                siteId, ContentItemEntity.STATUS_APPROVED)
            .stream()
            .limit(clamp(limit))
            .map(ContentCardItem::of)
            .toList();
    }

    /** 4. Recently Published：最近发布的内容（PUBLISHED 且有发布版本，deploy/admin 可见）。 */
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'deploy')")
    @GetMapping("/sites/{siteId}/dashboard/recently-published")
    public List<RecentlyPublishedItem> recentlyPublished(@PathVariable UUID siteId,
                                                         @RequestParam(defaultValue = "20") int limit) {
        return itemRepo.findTop20BySiteIdAndStatusAndPublishedVersionNotNullOrderByUpdatedAtDesc(
                siteId, ContentItemEntity.STATUS_PUBLISHED)
            .stream()
            .limit(clamp(limit))
            .map(item -> new RecentlyPublishedItem(item.getId(), item.getTitle(), item.getRoutePath(),
                item.getPublishedVersion() == null ? 0 : item.getPublishedVersion(),
                item.getCreatedBy(), item.getUpdatedAt()))
            .toList();
    }

    /** 6. Unpublished Work：当前用户的未发布内容（DRAFT/IN_REVIEW/APPROVED/WITHDRAWN）。 */
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @GetMapping("/sites/{siteId}/dashboard/unpublished-work")
    public List<ContentCardItem> unpublishedWork(@PathVariable UUID siteId,
                                                 @RequestParam(defaultValue = "20") int limit,
                                                 Authentication authentication) {
        return itemRepo.findTop50BySiteIdAndCreatedByAndStatusInOrderByUpdatedAtDesc(
                siteId, authentication.getName(), UNPUBLISHED_STATUSES)
            .stream()
            .limit(clamp(limit))
            .map(ContentCardItem::of)
            .toList();
    }

    /** 7. Expiring：已设定时撤回的已发布内容（到期后由系统自动撤回）。 */
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @GetMapping("/sites/{siteId}/dashboard/expiring")
    public List<ContentCardItem> expiring(@PathVariable UUID siteId,
                                          @RequestParam(defaultValue = "20") int limit) {
        return itemRepo.findBySiteIdAndStatusAndScheduledWithdrawAtIsNotNullOrderByScheduledWithdrawAtAsc(
                siteId, ContentItemEntity.STATUS_PUBLISHED)
            .stream()
            .limit(clamp(limit))
            .map(ContentCardItem::of)
            .toList();
    }

    /** 各卡片数量汇总（页面顶部/角标用；无权限的卡片不出现在结果中）。 */
    @PreAuthorize("@siteAuthorization.check(authentication, #siteId, 'read')")
    @GetMapping("/sites/{siteId}/dashboard/summary")
    public Map<String, Object> summary(@PathVariable UUID siteId, Authentication authentication) {
        UUID userId = userIdOf(authentication);
        boolean canReview = capabilityEvaluator.has(userId, siteId, "review");
        boolean canDeploy = capabilityEvaluator.has(userId, siteId, "deploy");
        String username = authentication.getName();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("activity", auditRepo.findBySiteIdOrderByOccurredAtDesc(siteId, PageRequest.of(0, 1)).getTotalElements());
        out.put("myActivity", auditRepo.findBySiteIdAndActorOrderByOccurredAtDesc(siteId, username, PageRequest.of(0, 1)).getTotalElements()
            + (userId == null ? 0 : auditRepo.findBySiteIdAndActorOrderByOccurredAtDesc(siteId, userId.toString(), PageRequest.of(0, 1)).getTotalElements()));
        out.put("unpublishedWork", itemRepo.countBySiteIdAndCreatedByAndStatusIn(siteId, username, UNPUBLISHED_STATUSES));
        out.put("expiring", itemRepo.countBySiteIdAndStatusAndScheduledWithdrawAtIsNotNull(siteId, ContentItemEntity.STATUS_PUBLISHED));
        if (canReview) {
            out.put("pendingApproval", taskRepo.countBySiteIdAndStatus(siteId, WorkflowTaskEntity.STATUS_PENDING));
            out.put("scheduled", itemRepo.countBySiteIdAndStatusAndScheduledPublishAtIsNotNull(siteId, ContentItemEntity.STATUS_APPROVED));
        }
        if (canDeploy) {
            out.put("recentlyPublished", itemRepo.countBySiteIdAndStatusAndPublishedVersionNotNull(siteId, ContentItemEntity.STATUS_PUBLISHED));
        }
        return out;
    }

    // ==== helpers ====

    /** 卡片渲染范围内的内容标题解析缓存。 */
    private final class TitleResolver {
        private final Map<UUID, String> contentTitles = new LinkedHashMap<>();

        String contentTitle(UUID id) {
            return contentTitles.computeIfAbsent(id, id2 ->
                itemRepo.findById(id2).map(ContentItemEntity::getTitle).orElse(null));
        }
    }

    /** 同一事件经 username/userId 两路查询会重复出现；按事件身份去重（幂等键由 occurredAt+action+object 组成）。 */
    private static String dedupKey(AuditEventEntity e) {
        return e.getOccurredAt() + "|" + e.getAction() + "|" + e.getObjectId();
    }

    /** 仅保留内容工作相关动作，过滤掉 GET 查询类审计噪音。 */    private static boolean isContentMeaningful(AuditEventEntity e) {
        String action = e.getAction();
        if (action.startsWith("GET ")) return false;
        return action.startsWith("CONTENT_") || action.startsWith("WORKFLOW_")
            || action.startsWith("PAGE_") || action.startsWith("MEDIA_")
            || action.startsWith("SITE_") || action.startsWith("FORM_")
            || action.startsWith("WIDGET_") || action.startsWith("SHARED_COMPONENT_")
            || action.startsWith("MVC_CONTROLLER_") || action.startsWith("THEME_")
            || action.startsWith("URL_REWRITE_") || action.startsWith("POST ") || action.startsWith("PUT ")
            || action.startsWith("PATCH ") || action.startsWith("DELETE ");
    }

    private ActivityItem toActivityItem(AuditEventEntity e, TitleResolver resolver) {
        String title = null;
        if (e.getObjectId() != null) {
            try {
                title = resolver.contentTitle(UUID.fromString(e.getObjectId()));
            } catch (IllegalArgumentException ignored) {
                // objectId 非 UUID（如主题文件路径），跳过标题解析
            }
        }
        return new ActivityItem(e.getOccurredAt(), e.getActor(), actorNames.resolve(e.getActor()),
            e.getAction(), e.getObjectType(), e.getObjectId(), title, e.getResult());
    }

    private static int clamp(int limit) {
        return Math.min(100, Math.max(1, limit));
    }

    private static UUID userIdOf(Authentication authentication) {
        return authentication != null
            && authentication.getPrincipal() instanceof AuthenticatedUserPrincipal principal
            ? principal.getUserId() : null;
    }
}
