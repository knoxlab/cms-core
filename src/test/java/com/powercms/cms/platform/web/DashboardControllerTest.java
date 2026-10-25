package com.powercms.cms.platform.web;

import com.powercms.cms.audit.entity.AuditEventEntity;
import com.powercms.cms.audit.repo.AuditEventRepository;
import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.identity.AuthenticatedUserPrincipal;
import com.powercms.cms.identity.ActorNameResolver;
import com.powercms.cms.identity.SiteCapabilityEvaluator;
import com.powercms.cms.identity.entity.UserEntity;
import com.powercms.cms.identity.repo.UserRepository;
import com.powercms.cms.workflow.repo.WorkflowTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashboardControllerTest {

    private ContentItemRepository itemRepo;
    private WorkflowTaskRepository taskRepo;
    private AuditEventRepository auditRepo;
    private UserRepository userRepo;
    private SiteCapabilityEvaluator evaluator;
    private DashboardController controller;

    private final UUID siteId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        itemRepo = mock(ContentItemRepository.class);
        taskRepo = mock(WorkflowTaskRepository.class);
        auditRepo = mock(AuditEventRepository.class);
        userRepo = mock(UserRepository.class);
        evaluator = mock(SiteCapabilityEvaluator.class);
        controller = new DashboardController(itemRepo, taskRepo, auditRepo,
            new ActorNameResolver(userRepo), evaluator);
    }

    @Test
    void activityFiltersGetNoiseAndResolvesActorName() {
        var content = AuditEventEntity.create(userId.toString(), "POST createContent",
            "ContentController", UUID.randomUUID().toString(), siteId, "SUCCESS", null, null);
        var noise = AuditEventEntity.create(userId.toString(), "GET search",
            "ContentController", null, siteId, "SUCCESS", null, null);
        when(auditRepo.findBySiteIdOrderByOccurredAtDesc(eq(siteId), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(content, noise), PageRequest.of(0, 20), 2));
        var user = new UserEntity();
        user.setId(userId);
        user.setUsername("alice");
        when(userRepo.findById(userId)).thenReturn(Optional.of(user));

        var result = controller.activity(siteId, 20);

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.items()).hasSize(1);
        var item = result.items().get(0);
        assertThat(item.actorName()).isEqualTo("alice");
        assertThat(item.action()).isEqualTo("POST createContent");
    }

    @Test
    void myActivityMergesUsernameAndUserIdActors() {
        // 两个事件发生在同一秒（dedupKey 含 objectId，不会互相去重），仅用于验证合并与排序稳定性
        var byName = AuditEventEntity.create("alice", "WORKFLOW_SUBMIT",
            "ReviewTask", UUID.randomUUID().toString(), siteId, "SUCCESS", null, null);
        var byId = AuditEventEntity.create(userId.toString(), "POST saveVersion",
            "ContentController", UUID.randomUUID().toString(), siteId, "SUCCESS", null, null);
        when(auditRepo.findBySiteIdAndActorOrderByOccurredAtDesc(eq(siteId), eq("alice"), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(byName), PageRequest.of(0, 20), 1));
        when(auditRepo.findBySiteIdAndActorOrderByOccurredAtDesc(eq(siteId), eq(userId.toString()), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(byId), PageRequest.of(0, 20), 1));

        var result = controller.myActivity(siteId, 20, auth());

        assertThat(result.items()).hasSize(2);
        // 相同时间戳下排序保持稳定即可，不做顺序强断言
        assertThat(result.items()).extracting(DashboardController.ActivityItem::action)
            .containsExactlyInAnyOrder("POST saveVersion", "WORKFLOW_SUBMIT");
    }

    @Test
    void summaryRespectsCapabilityGating() {
        when(evaluator.has(userId, siteId, "review")).thenReturn(false);
        when(evaluator.has(userId, siteId, "deploy")).thenReturn(true);
        when(auditRepo.findBySiteIdOrderByOccurredAtDesc(eq(siteId), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 1), 0));
        when(auditRepo.findBySiteIdAndActorOrderByOccurredAtDesc(any(), any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 1), 0));
        when(itemRepo.countBySiteIdAndCreatedByAndStatusIn(eq(siteId), eq("alice"), any())).thenReturn(3L);
        when(itemRepo.countBySiteIdAndStatusAndScheduledWithdrawAtIsNotNull(siteId, ContentItemEntity.STATUS_PUBLISHED))
            .thenReturn(2L);
        when(itemRepo.countBySiteIdAndStatusAndPublishedVersionNotNull(siteId, ContentItemEntity.STATUS_PUBLISHED))
            .thenReturn(7L);

        var summary = controller.summary(siteId, auth());

        assertThat(summary.get("unpublishedWork")).isEqualTo(3L);
        assertThat(summary.get("expiring")).isEqualTo(2L);
        assertThat(summary.get("recentlyPublished")).isEqualTo(7L);
        assertThat(summary).doesNotContainKeys("pendingApproval", "scheduled");
    }

    private UsernamePasswordAuthenticationToken auth() {
        var details = org.springframework.security.core.userdetails.User
            .withUsername("alice").password("x").authorities("ROLE_USER").build();
        return UsernamePasswordAuthenticationToken.authenticated(
            new AuthenticatedUserPrincipal(userId, details), "x", details.getAuthorities());
    }
}
