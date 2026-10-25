package com.powercms.cms.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.audit.AuditService;
import com.powercms.cms.content.ContentService;
import com.powercms.cms.content.ContentService.PublishSource;
import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.outbox.OutboxService;
import com.powercms.cms.workflow.entity.WorkflowTaskConfirmationEntity;
import com.powercms.cms.workflow.entity.WorkflowDefinitionEntity;
import com.powercms.cms.workflow.entity.WorkflowTaskEntity;
import com.powercms.cms.workflow.entity.WorkflowTransitionEntity;
import com.powercms.cms.workflow.entity.WorkflowTransitionLogEntity;
import com.powercms.cms.workflow.repo.WorkflowDefinitionRepository;
import com.powercms.cms.workflow.repo.WorkflowTaskConfirmationRepository;
import com.powercms.cms.workflow.repo.WorkflowTaskRepository;
import com.powercms.cms.workflow.repo.WorkflowTransitionLogRepository;
import com.powercms.cms.workflow.repo.WorkflowTransitionRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowServiceTest {

    @Mock WorkflowDefinitionRepository wfRepo;
    @Mock WorkflowTransitionRepository transRepo;
    @Mock WorkflowTaskRepository taskRepo;
    @Mock WorkflowTransitionLogRepository logRepo;
    @Mock ContentItemRepository itemRepo;
    @Mock ContentService contentService;
    @Mock OutboxService outbox;
    @Mock ApplicationEventPublisher events;
    @Mock AuditService audit;
    @Mock NotificationService notificationService;
    @Mock WorkflowTaskConfirmationRepository confirmationRepo;
    @Mock WorkflowUserAccess userAccess;

    WorkflowService service;
    UUID siteId;
    UUID wfId;
    UUID itemId;
    ContentItemEntity item;
    WorkflowDefinitionEntity def;

    @BeforeEach
    void setUp() {
        service = new WorkflowService(wfRepo, transRepo, taskRepo, logRepo, itemRepo,
            contentService, outbox, events, audit, notificationService, confirmationRepo, userAccess, new ObjectMapper());
        lenient().when(userAccess.canReview(any(), any())).thenReturn(true);
        // 待办可见性：默认「能查看」，具体用例再覆盖为 false（hide / 权限收回场景）。
        lenient().when(userAccess.canViewContent(anyString(), any())).thenReturn(true);
        lenient().when(userAccess.findSiteAdminUsername(any())).thenReturn(Optional.empty());
        lenient().when(confirmationRepo.countByTaskIdAndActionKey(any(), any())).thenReturn(0L);
        siteId = UUID.randomUUID();
        wfId = UUID.randomUUID();
        itemId = UUID.randomUUID();
        item = new ContentItemEntity();
        item.setId(itemId);
        item.setSiteId(siteId);
        item.setTitle("首页");
        item.setStatus(ContentItemEntity.STATUS_DRAFT);
        item.setCurrentVersion(1);
        item.setCreatedBy("editor");
        def = new WorkflowDefinitionEntity();
        def.setId(wfId);
        def.setSiteId(siteId);
        def.setName("系统工作流");
        def.setStatus(WorkflowDefinitionEntity.STATUS_PUBLISHED);
        def.setSystem(true);
    }

    @Test
    void submitCreatesPendingTaskAndMarksInReview() {
        when(itemRepo.findById(itemId)).thenReturn(Optional.of(item));
        when(taskRepo.existsByContentItemIdAndStatus(itemId, WorkflowTaskEntity.STATUS_PENDING)).thenReturn(false);
        when(wfRepo.findById(wfId)).thenReturn(Optional.of(def));
        when(transRepo.findByWorkflowDefinitionIdAndSourceStatus(wfId, ContentItemEntity.STATUS_DRAFT))
            .thenReturn(List.of(transition(ContentItemEntity.STATUS_DRAFT, ContentItemEntity.STATUS_PUBLISHED)));
        when(taskRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(itemRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(logRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        var info = service.submitForReview(wfId, itemId, ContentItemEntity.STATUS_PUBLISHED, "reviewer", "editor");

        assertThat(info.status()).isEqualTo(WorkflowTaskEntity.STATUS_PENDING);
        assertThat(info.submitter()).isEqualTo("editor");
        assertThat(item.getStatus()).isEqualTo(ContentItemEntity.STATUS_IN_REVIEW);
        assertThat(item.isInWorkflow()).isTrue();
        verify(outbox).write(eq(WorkflowTaskEvent.SUBMITTED), eq("ReviewTask"), anyString(), anyString());
        verify(events).publishEvent(any(WorkflowTaskEvent.class));
        verify(logRepo).save(any(WorkflowTransitionLogEntity.class));
    }

    @Test
    void submitRejectsDuplicatePendingTask() {
        when(itemRepo.findById(itemId)).thenReturn(Optional.of(item));
        when(taskRepo.existsByContentItemIdAndStatus(itemId, WorkflowTaskEntity.STATUS_PENDING)).thenReturn(true);

        assertThatThrownBy(() -> service.submitForReview(wfId, itemId, "PUBLISHED", "reviewer", "editor"))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ErrorCodes.ALREADY_IN_REVIEW);
        verify(taskRepo, never()).save(any());
    }

    @Test
    void rejectRequiresCommentAndReturnsDraft() {
        var task = pendingTask();
        item.setStatus(ContentItemEntity.STATUS_IN_REVIEW);
        when(taskRepo.findById(task.getId())).thenReturn(Optional.of(task));
        when(itemRepo.findById(itemId)).thenReturn(Optional.of(item));
        when(taskRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(itemRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        assertThatThrownBy(() -> service.reject(task.getId(), "  ", "reviewer"))
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ErrorCodes.WORKFLOW_COMMENT_REQUIRED);

        var info = service.reject(task.getId(), "错别字过多", "reviewer");
        assertThat(info.status()).isEqualTo(WorkflowTaskEntity.STATUS_REJECTED);
        assertThat(item.getStatus()).isEqualTo(ContentItemEntity.STATUS_DRAFT);
        assertThat(item.isInWorkflow()).isFalse();
        assertThat(item.getLastRejectionReason()).isEqualTo("错别字过多");
        verify(events).publishEvent(any(WorkflowTaskEvent.class));
    }

    @Test
    void approvePublishedDelegatesToContentService() {
        var task = pendingTask();
        item.setStatus(ContentItemEntity.STATUS_IN_REVIEW);
        when(taskRepo.findById(task.getId())).thenReturn(Optional.of(task));
        when(itemRepo.findById(itemId)).thenReturn(Optional.of(item));
        when(taskRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        service.approve(task.getId(), "ok", "reviewer");

        verify(contentService).updateStatus(itemId, ContentItemEntity.STATUS_PUBLISHED, PublishSource.WORKFLOW);
        verify(outbox).write(eq(WorkflowTaskEvent.APPROVED), eq("ReviewTask"), anyString(), anyString());
    }

    @Test
    void cancelRestoresSourceStatus() {
        var task = pendingTask();
        item.setStatus(ContentItemEntity.STATUS_IN_REVIEW);
        when(taskRepo.findById(task.getId())).thenReturn(Optional.of(task));
        when(itemRepo.findById(itemId)).thenReturn(Optional.of(item));
        when(taskRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(itemRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        var info = service.cancel(task.getId(), null, "editor");
        assertThat(info.status()).isEqualTo(WorkflowTaskEntity.STATUS_CANCELLED);
        assertThat(item.getStatus()).isEqualTo(ContentItemEntity.STATUS_DRAFT);
        assertThat(item.isInWorkflow()).isFalse();
    }

    @Test
    void approveOnResolvedTaskFails() {
        var task = pendingTask();
        task.setStatus(WorkflowTaskEntity.STATUS_APPROVED);
        when(taskRepo.findById(task.getId())).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> service.approve(task.getId(), "x", "reviewer"))
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ErrorCodes.INVALID_TASK_STATE);
    }

    @Test
    void ensureSystemWorkflowCreatesPublishedDefinition() {
        when(wfRepo.findBySiteIdAndStatus(siteId, WorkflowDefinitionEntity.STATUS_PUBLISHED)).thenReturn(List.of());
        when(wfRepo.save(any())).thenAnswer(i -> {
            WorkflowDefinitionEntity d = i.getArgument(0);
            if (d.getId() == null) d.setId(UUID.randomUUID());
            return d;
        });
        when(transRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(transRepo.findByWorkflowDefinitionIdOrderBySortOrder(any())).thenReturn(List.of());

        var info = service.ensureSystemWorkflow(siteId);
        assertThat(info.system()).isTrue();
        assertThat(info.status()).isEqualTo(WorkflowDefinitionEntity.STATUS_PUBLISHED);
        ArgumentCaptor<WorkflowTransitionEntity> cap = ArgumentCaptor.forClass(WorkflowTransitionEntity.class);
        verify(transRepo, org.mockito.Mockito.atLeast(4)).save(cap.capture());
        assertThat(cap.getAllValues()).anyMatch(t ->
            ContentItemEntity.STATUS_IN_REVIEW.equals(t.getSourceStatus())
                && ContentItemEntity.STATUS_DRAFT.equals(t.getTargetStatus())
                && t.isRequiredComment());
    }

    @Test
    void submitRejectsPastSchedule() {
        when(itemRepo.findById(itemId)).thenReturn(Optional.of(item));
        when(taskRepo.existsByContentItemIdAndStatus(itemId, WorkflowTaskEntity.STATUS_PENDING)).thenReturn(false);
        when(wfRepo.findById(wfId)).thenReturn(Optional.of(def));

        assertThatThrownBy(() -> service.submitForReview(wfId, itemId, "PUBLISHED", "reviewer", "editor",
            Instant.now().minus(1, ChronoUnit.HOURS)))
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ErrorCodes.SCHEDULE_INVALID);
        verify(taskRepo, never()).save(any());
    }

    @Test
    void approveFutureScheduleDoesNotPublish() {
        var task = pendingTask();
        task.setScheduledAt(Instant.now().plus(2, ChronoUnit.HOURS));
        item.setStatus(ContentItemEntity.STATUS_IN_REVIEW);
        when(taskRepo.findById(task.getId())).thenReturn(Optional.of(task));
        when(itemRepo.findById(itemId)).thenReturn(Optional.of(item));
        when(taskRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(itemRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        var info = service.approve(task.getId(), "ok", "reviewer");

        assertThat(info.status()).isEqualTo(WorkflowTaskEntity.STATUS_APPROVED);
        assertThat(info.executionState()).isNull();
        assertThat(item.getStatus()).isEqualTo(ContentItemEntity.STATUS_APPROVED);
        assertThat(item.getScheduledPublishAt()).isEqualTo(task.getScheduledAt());
        verify(contentService, never()).updateStatus(any(), any());
    }

    @Test
    void executeDueScheduledPublishRunsOnce() {
        var task = pendingTask();
        task.setStatus(WorkflowTaskEntity.STATUS_APPROVED);
        task.setScheduledAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        item.setStatus(ContentItemEntity.STATUS_APPROVED);
        when(taskRepo.findByIdForUpdate(task.getId())).thenReturn(Optional.of(task));
        when(itemRepo.findByIdForUpdate(itemId)).thenReturn(Optional.of(item));
        when(taskRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(itemRepo.findById(itemId)).thenReturn(Optional.of(item));

        assertThat(service.executeDueScheduledPublish(task.getId(), Instant.now())).isTrue();
        verify(contentService).updateStatus(itemId, ContentItemEntity.STATUS_PUBLISHED, PublishSource.WORKFLOW);
        assertThat(task.getExecutionState()).isEqualTo(WorkflowTaskEntity.EXEC_SUCCEEDED);

        assertThat(service.executeDueScheduledPublish(task.getId(), Instant.now())).isFalse();
        verify(contentService).updateStatus(itemId, ContentItemEntity.STATUS_PUBLISHED, PublishSource.WORKFLOW);
    }

    @Test
    void executeDueWithdrawClearsScheduleAndWithdraws() {
        item.setStatus(ContentItemEntity.STATUS_PUBLISHED);
        item.setScheduledWithdrawAt(Instant.now().minusSeconds(5));
        when(itemRepo.findByIdForUpdate(itemId)).thenReturn(Optional.of(item));
        when(itemRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        assertThat(service.executeDueWithdraw(itemId, Instant.now())).isTrue();
        verify(contentService).updateStatus(itemId, ContentItemEntity.STATUS_WITHDRAWN);
        assertThat(item.getScheduledWithdrawAt()).isNull();

        assertThat(service.executeDueWithdraw(itemId, Instant.now())).isFalse();
        verify(contentService).updateStatus(itemId, ContentItemEntity.STATUS_WITHDRAWN);
    }

    @Test
    void firstConfirmDoesNotPublishWhenMinApproversTwo() {
        var task = pendingTask();
        item.setStatus(ContentItemEntity.STATUS_IN_REVIEW);
        var trans = transition(ContentItemEntity.STATUS_DRAFT, ContentItemEntity.STATUS_PUBLISHED);
        trans.setMinApprovers(2);
        when(taskRepo.findById(task.getId())).thenReturn(Optional.of(task));
        when(itemRepo.findById(itemId)).thenReturn(Optional.of(item));
        when(transRepo.findByWorkflowDefinitionIdAndSourceStatus(wfId, ContentItemEntity.STATUS_DRAFT))
            .thenReturn(List.of(trans));
        when(confirmationRepo.existsByTaskIdAndActionKeyAndActor(
            task.getId(), WorkflowTaskConfirmationEntity.ACTION_APPROVE, "reviewer")).thenReturn(false);
        when(confirmationRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(confirmationRepo.countByTaskIdAndActionKey(
            task.getId(), WorkflowTaskConfirmationEntity.ACTION_APPROVE)).thenReturn(1L);

        var info = service.approve(task.getId(), "ok", "reviewer");

        assertThat(info.status()).isEqualTo(WorkflowTaskEntity.STATUS_PENDING);
        assertThat(info.confirmedApprovers()).isEqualTo(1);
        assertThat(info.requiredApprovers()).isEqualTo(2);
        assertThat(item.getStatus()).isEqualTo(ContentItemEntity.STATUS_IN_REVIEW);
        verify(contentService, never()).updateStatus(any(), any());
    }

    @Test
    void secondConfirmPublishesWhenMinApproversTwo() {
        var task = pendingTask();
        item.setStatus(ContentItemEntity.STATUS_IN_REVIEW);
        var trans = transition(ContentItemEntity.STATUS_DRAFT, ContentItemEntity.STATUS_PUBLISHED);
        trans.setMinApprovers(2);
        when(taskRepo.findById(task.getId())).thenReturn(Optional.of(task));
        when(itemRepo.findById(itemId)).thenReturn(Optional.of(item));
        when(taskRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(transRepo.findByWorkflowDefinitionIdAndSourceStatus(wfId, ContentItemEntity.STATUS_DRAFT))
            .thenReturn(List.of(trans));
        when(confirmationRepo.existsByTaskIdAndActionKeyAndActor(
            task.getId(), WorkflowTaskConfirmationEntity.ACTION_APPROVE, "reviewer2")).thenReturn(false);
        when(confirmationRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(confirmationRepo.countByTaskIdAndActionKey(
            task.getId(), WorkflowTaskConfirmationEntity.ACTION_APPROVE)).thenReturn(2L);

        var info = service.approve(task.getId(), "ok", "reviewer2");

        assertThat(info.status()).isEqualTo(WorkflowTaskEntity.STATUS_APPROVED);
        verify(contentService).updateStatus(itemId, ContentItemEntity.STATUS_PUBLISHED, PublishSource.WORKFLOW);
    }

    @Test
    void duplicateConfirmRejected() {
        var task = pendingTask();
        item.setStatus(ContentItemEntity.STATUS_IN_REVIEW);
        var trans = transition(ContentItemEntity.STATUS_DRAFT, ContentItemEntity.STATUS_PUBLISHED);
        trans.setMinApprovers(2);
        when(taskRepo.findById(task.getId())).thenReturn(Optional.of(task));
        when(itemRepo.findById(itemId)).thenReturn(Optional.of(item));
        when(transRepo.findByWorkflowDefinitionIdAndSourceStatus(wfId, ContentItemEntity.STATUS_DRAFT))
            .thenReturn(List.of(trans));
        when(confirmationRepo.existsByTaskIdAndActionKeyAndActor(
            task.getId(), WorkflowTaskConfirmationEntity.ACTION_APPROVE, "reviewer")).thenReturn(true);

        assertThatThrownBy(() -> service.approve(task.getId(), "ok", "reviewer"))
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ErrorCodes.CONFLICT);
        verify(contentService, never()).updateStatus(any(), any());
    }

    @Test
    void delegateDeniedWithoutReviewPermission() {
        var task = pendingTask();
        when(taskRepo.findById(task.getId())).thenReturn(Optional.of(task));
        when(itemRepo.findById(itemId)).thenReturn(Optional.of(item));
        when(userAccess.canReview("guest", siteId)).thenReturn(false);

        assertThatThrownBy(() -> service.delegate(task.getId(), "guest", "reviewer"))
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ErrorCodes.DELEGATION_PERMISSION_DENIED);
        verify(taskRepo, never()).save(any());
    }

    @Test
    void peerReviewBlocksSelfApproval() {
        var task = pendingTask();
        item.setStatus(ContentItemEntity.STATUS_IN_REVIEW);
        when(taskRepo.findById(task.getId())).thenReturn(Optional.of(task));
        when(itemRepo.findById(itemId)).thenReturn(Optional.of(item));
        when(userAccess.isPeerReviewRequired(siteId)).thenReturn(true);

        assertThatThrownBy(() -> service.approve(task.getId(), "ok", "editor"))
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ErrorCodes.SELF_APPROVAL_FORBIDDEN);
        verify(contentService, never()).updateStatus(any(), any());
    }

    @Test
    void timeoutEscalatesToSiteAdminOnce() {
        var task = pendingTask();
        task.setCreatedAt(Instant.now().minus(2, ChronoUnit.HOURS));
        def.setTimeoutMinutes(60);
        def.setTimeoutActionKey("ESCALATE");
        when(taskRepo.findByIdForUpdate(task.getId())).thenReturn(Optional.of(task));
        when(wfRepo.findById(wfId)).thenReturn(Optional.of(def));
        when(itemRepo.findById(itemId)).thenReturn(Optional.of(item));
        when(userAccess.findSiteAdminUsername(siteId)).thenReturn(Optional.of("site-admin"));
        when(taskRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        assertThat(service.processTimeout(task.getId(), Instant.now())).isTrue();
        assertThat(task.getAssignee()).isEqualTo("site-admin");
        assertThat(task.getDelegatedFrom()).isEqualTo("reviewer");
        assertThat(task.getTimeoutEscalatedAt()).isNotNull();

        assertThat(service.processTimeout(task.getId(), Instant.now())).isFalse();
        verify(events).publishEvent(any(WorkflowTaskEvent.class));
    }

    @Test
    void updatePublishedDefinitionRequiresNewVersion() {
        when(wfRepo.findById(wfId)).thenReturn(Optional.of(def));
        assertThatThrownBy(() -> service.updateDraft(wfId, "x", null, null, null, null, List.of()))
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ErrorCodes.CONFLICT);
    }

    /**
     * 待办列表要显示内容标题（此前只有 UUID，审核人无法判断是哪条内容）。
     * 标题/模型来自内容条目，一次批量取回；内容已删除时标题为 null（界面回退展示编号）。
     */
    @Test
    void pendingTasksCarryContentTitleAndType() {
        var typeId = UUID.randomUUID();
        item.setContentTypeId(typeId);
        var task = pendingTask();
        when(taskRepo.findByAssigneeAndStatus("reviewer", WorkflowTaskEntity.STATUS_PENDING))
            .thenReturn(List.of(task));
        when(itemRepo.findAllById(any())).thenReturn(List.of(item));

        var rows = service.pendingTasks("reviewer");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).contentTitle()).isEqualTo("首页");
        assertThat(rows.get(0).contentTypeId()).isEqualTo(typeId);
    }

    @Test
    void pendingTasksTolerateDeletedContent() {
        var task = pendingTask();
        when(taskRepo.findByAssigneeAndStatus("reviewer", WorkflowTaskEntity.STATUS_PENDING))
            .thenReturn(List.of(task));
        when(itemRepo.findAllById(any())).thenReturn(List.of());

        var rows = service.pendingTasks("reviewer");

        // 内容已删除：任务行仍在库里，但不再出现在待办列表（点开只会 404）
        assertThat(rows).isEmpty();
    }

    /** 非当前站点：内容属于别的站点时不出现在本站点待办里（委派/换站后的常见错位）。 */
    @Test
    void pendingTasksHideContentFromOtherSites() {
        var task = pendingTask();
        item.setSiteId(UUID.randomUUID());
        when(taskRepo.findByAssigneeAndStatus("reviewer", WorkflowTaskEntity.STATUS_PENDING))
            .thenReturn(List.of(task));
        when(itemRepo.findAllById(any())).thenReturn(List.of(item));

        assertThat(service.pendingTasks("reviewer", siteId)).isEmpty();
    }

    /** 无查看权限（ACL 收回 / 站点能力失效）：隐藏，避免审核人点开就 403。 */
    @Test
    void pendingTasksHideContentWithoutViewPermission() {
        var task = pendingTask();
        when(taskRepo.findByAssigneeAndStatus("reviewer", WorkflowTaskEntity.STATUS_PENDING))
            .thenReturn(List.of(task));
        when(itemRepo.findAllById(any())).thenReturn(List.of(item));
        when(userAccess.canViewContent("reviewer", itemId)).thenReturn(false);

        assertThat(service.pendingTasks("reviewer", siteId)).isEmpty();
    }

    @Test
    void pendingTasksKeepVisibleContentOfCurrentSite() {
        var task = pendingTask();
        when(taskRepo.findByAssigneeAndStatus("reviewer", WorkflowTaskEntity.STATUS_PENDING))
            .thenReturn(List.of(task));
        when(itemRepo.findAllById(any())).thenReturn(List.of(item));

        var rows = service.pendingTasks("reviewer", siteId);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).contentItemId()).isEqualTo(itemId);
        verify(userAccess).canViewContent("reviewer", itemId);
    }

    private WorkflowTaskEntity pendingTask() {
        return WorkflowTaskEntity.create(siteId, wfId, itemId, 1,
            ContentItemEntity.STATUS_DRAFT, ContentItemEntity.STATUS_PUBLISHED, "reviewer", "editor");
    }

    /**
     * DEF-WF-02 回归：系统工作流只定义 DRAFT→IN_REVIEW，提交审核时任务 targetStatus 只能是
     * IN_REVIEW，与内容当前状态相同。审批必须按后继转换 IN_REVIEW→PUBLISHED 推进，而不是零推进。
     */
    @Test
    void approveAdvancesViaSuccessorWhenSubmitTargetIsInReview() {
        var task = WorkflowTaskEntity.create(siteId, wfId, itemId, 1,
            ContentItemEntity.STATUS_DRAFT, ContentItemEntity.STATUS_IN_REVIEW, "reviewer", "editor");
        item.setStatus(ContentItemEntity.STATUS_IN_REVIEW);
        when(taskRepo.findById(task.getId())).thenReturn(Optional.of(task));
        when(itemRepo.findById(itemId)).thenReturn(Optional.of(item));
        when(transRepo.findByWorkflowDefinitionIdAndSourceStatus(wfId, ContentItemEntity.STATUS_DRAFT))
            .thenReturn(List.of(transition(ContentItemEntity.STATUS_DRAFT, ContentItemEntity.STATUS_IN_REVIEW)));
        when(transRepo.findByWorkflowDefinitionIdAndSourceStatus(wfId, ContentItemEntity.STATUS_IN_REVIEW))
            .thenReturn(List.of(
                transition(ContentItemEntity.STATUS_IN_REVIEW, ContentItemEntity.STATUS_DRAFT),
                transition(ContentItemEntity.STATUS_IN_REVIEW, ContentItemEntity.STATUS_PUBLISHED)));

        var info = service.approve(task.getId(), "ok", "reviewer");

        assertThat(info.status()).isEqualTo(WorkflowTaskEntity.STATUS_APPROVED);
        verify(contentService).updateStatus(itemId, ContentItemEntity.STATUS_PUBLISHED, PublishSource.WORKFLOW);
    }

    /** DEF-WF-02 回归：定时发布路径同样要固化后继终态，否则执行时会回填 IN_REVIEW。 */
    @Test
    void scheduledApproveFreezesSuccessorTargetForDeferredExecution() {
        var task = WorkflowTaskEntity.create(siteId, wfId, itemId, 1,
            ContentItemEntity.STATUS_DRAFT, ContentItemEntity.STATUS_IN_REVIEW, "reviewer", "editor");
        task.setScheduledAt(Instant.now().plus(1, ChronoUnit.HOURS));
        item.setStatus(ContentItemEntity.STATUS_IN_REVIEW);
        when(taskRepo.findById(task.getId())).thenReturn(Optional.of(task));
        when(itemRepo.findById(itemId)).thenReturn(Optional.of(item));
        when(taskRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(transRepo.findByWorkflowDefinitionIdAndSourceStatus(wfId, ContentItemEntity.STATUS_DRAFT))
            .thenReturn(List.of(transition(ContentItemEntity.STATUS_DRAFT, ContentItemEntity.STATUS_IN_REVIEW)));
        when(transRepo.findByWorkflowDefinitionIdAndSourceStatus(wfId, ContentItemEntity.STATUS_IN_REVIEW))
            .thenReturn(List.of(transition(ContentItemEntity.STATUS_IN_REVIEW, ContentItemEntity.STATUS_PUBLISHED)));
        when(itemRepo.findByIdForUpdate(itemId)).thenReturn(Optional.of(item));
        when(taskRepo.findByIdForUpdate(task.getId())).thenReturn(Optional.of(task));

        service.approve(task.getId(), "ok", "reviewer");
        assertThat(task.getTargetStatus()).isEqualTo(ContentItemEntity.STATUS_PUBLISHED);
        assertThat(item.getStatus()).isEqualTo(ContentItemEntity.STATUS_APPROVED);

        // 定时点到达后由执行器落库，必须仍然是 PUBLISHED 而不是 IN_REVIEW。
        task.setScheduledAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        assertThat(service.executeDueScheduledPublish(task.getId(), Instant.now())).isTrue();
        verify(contentService).updateStatus(itemId, ContentItemEntity.STATUS_PUBLISHED, PublishSource.WORKFLOW);
    }

    private static WorkflowTransitionEntity transition(String from, String to) {
        var t = new WorkflowTransitionEntity();
        t.setId(UUID.randomUUID());
        t.setSourceStatus(from);
        t.setTargetStatus(to);
        return t;
    }
}
