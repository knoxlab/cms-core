package com.powercms.cms.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.audit.AuditService;
import com.powercms.cms.content.ContentService;
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
import com.powercms.common.error.ApiError;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class WorkflowService implements com.powercms.cms.content.ContentService.ReviewTaskPort {

    private static final Logger log = LoggerFactory.getLogger(WorkflowService.class);

    private final WorkflowDefinitionRepository wfRepo;
    private final WorkflowTransitionRepository transRepo;
    private final WorkflowTaskRepository taskRepo;
    private final WorkflowTransitionLogRepository logRepo;
    private final ContentItemRepository itemRepo;
    private final ContentService contentService;
    private final OutboxService outbox;
    private final ApplicationEventPublisher events;
    private final AuditService audit;
    private final NotificationService notificationService;
    private final WorkflowTaskConfirmationRepository confirmationRepo;
    private final WorkflowUserAccess userAccess;
    private final ObjectMapper om;

    /**
     * 版本能力门禁端口；单测直接构造时为 null，此时跳过版本分级校验。
     * 接口定义在本模块、由 platform 模块实现（依赖倒置，避免 workflow ↔ platform 循环）。
     */
    private WorkflowEntitlementPort entitlement;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setEntitlement(WorkflowEntitlementPort entitlement) {
        this.entitlement = entitlement;
    }

    WorkflowService(WorkflowDefinitionRepository wfRepo,
                    WorkflowTransitionRepository transRepo,
                    WorkflowTaskRepository taskRepo,
                    WorkflowTransitionLogRepository logRepo,
                    ContentItemRepository itemRepo,
                    ContentService contentService,
                    OutboxService outbox,
                    ApplicationEventPublisher events,
                    AuditService audit,
                    NotificationService notificationService,
                    WorkflowTaskConfirmationRepository confirmationRepo,
                    WorkflowUserAccess userAccess,
                    ObjectMapper om) {
        this.wfRepo = wfRepo;
        this.transRepo = transRepo;
        this.taskRepo = taskRepo;
        this.logRepo = logRepo;
        this.itemRepo = itemRepo;
        this.contentService = contentService;
        this.outbox = outbox;
        this.events = events;
        this.audit = audit;
        this.notificationService = notificationService;
        this.confirmationRepo = confirmationRepo;
        this.userAccess = userAccess;
        this.om = om;
    }

    // ---------- Definition ----------

    public record WfDefInfo(UUID id, String name, String description, int version, String status,
                            boolean system, int timeoutMinutes, int reminderIntervalMinutes,
                            String timeoutActionKey, List<TransInfo> transitions) {}

    public record TransInfo(UUID id, String sourceStatus, String targetStatus,
                            UUID allowedRoleId, boolean requiredComment, int minApprovers) {}

    public WfDefInfo createDefinition(UUID siteId, String name, String description,
                                        List<TransInput> transitions) {
        assertWorkflowShapeAllowed(transitions);
        var def = new WorkflowDefinitionEntity();
        def.setSiteId(siteId);
        def.setName(name);
        def.setDescription(description);
        def = wfRepo.save(def);
        saveTransitions(def.getId(), transitions);
        return toInfo(def);
    }

    public void publish(UUID wfId) {
        var def = requireDef(wfId);
        if (WorkflowDefinitionEntity.STATUS_DEPRECATED.equals(def.getStatus())) {
            throw new BusinessException(ErrorCodes.CONFLICT, "已废弃的流程不能发布");
        }
        wfRepo.findBySiteIdAndStatus(def.getSiteId(), WorkflowDefinitionEntity.STATUS_PUBLISHED).stream()
            .filter(other -> other.getName().equals(def.getName()) && !other.getId().equals(def.getId()))
            .forEach(other -> {
                if (!other.isSystem() || def.isSystem()) {
                    other.setStatus(WorkflowDefinitionEntity.STATUS_DEPRECATED);
                    wfRepo.save(other);
                }
            });
        def.setStatus(WorkflowDefinitionEntity.STATUS_PUBLISHED);
        wfRepo.save(def);
    }

    public WfDefInfo newVersion(UUID wfId) {
        var src = requireDef(wfId);
        var copy = new WorkflowDefinitionEntity();
        copy.setSiteId(src.getSiteId());
        copy.setName(src.getName());
        copy.setDescription(src.getDescription());
        copy.setVersion(src.getVersion() + 1);
        copy.setSystem(src.isSystem());
        copy.setStatus(WorkflowDefinitionEntity.STATUS_DRAFT);
        copy.setTimeoutMinutes(src.getTimeoutMinutes());
        copy.setReminderIntervalMinutes(src.getReminderIntervalMinutes());
        copy.setTimeoutActionKey(src.getTimeoutActionKey());
        copy = wfRepo.save(copy);
        var transitions = transRepo.findByWorkflowDefinitionIdOrderBySortOrder(src.getId()).stream()
            .map(t -> new TransInput(t.getSourceStatus(), t.getTargetStatus(), t.getAllowedRoleId(),
                t.isRequiredComment(), t.getSortOrder(), t.getMinApprovers()))
            .toList();
        assertWorkflowShapeAllowed(transitions);
        saveTransitions(copy.getId(), transitions);
        return toInfo(copy);
    }

    public WfDefInfo updateDraft(UUID wfId, String name, String description,
                                 Integer timeoutMinutes, Integer reminderIntervalMinutes,
                                 String timeoutActionKey, List<TransInput> transitions) {
        var def = requireDef(wfId);
        if (!WorkflowDefinitionEntity.STATUS_DRAFT.equals(def.getStatus())) {
            throw new BusinessException(ErrorCodes.CONFLICT, "已发布流程请先另存为新版本再修改转换规则");
        }
        if (name != null && !name.isBlank()) def.setName(name);
        if (description != null) def.setDescription(description);
        if (timeoutMinutes != null) def.setTimeoutMinutes(Math.max(0, timeoutMinutes));
        if (reminderIntervalMinutes != null) def.setReminderIntervalMinutes(Math.max(0, reminderIntervalMinutes));
        if (timeoutActionKey != null && !timeoutActionKey.isBlank()) def.setTimeoutActionKey(timeoutActionKey);
        wfRepo.save(def);
        if (transitions != null) {
            assertWorkflowShapeAllowed(transitions);
            transRepo.deleteByWorkflowDefinitionId(def.getId());
            saveTransitions(def.getId(), transitions);
        }
        return toInfo(def);
    }

    public void deprecate(UUID wfId) {
        var def = requireDef(wfId);
        if (def.isSystem()) {
            throw new BusinessException(ErrorCodes.CONFLICT, "系统工作流不可废弃");
        }
        def.setStatus(WorkflowDefinitionEntity.STATUS_DEPRECATED);
        wfRepo.save(def);
    }

    public List<WfDefInfo> listDefinitions(UUID siteId) {
        ensureSystemWorkflow(siteId);
        return wfRepo.findBySiteId(siteId).stream().map(this::toInfo).toList();
    }

    public WfDefInfo getDefinition(UUID id) { return toInfo(requireDef(id)); }

    public record TransInput(String sourceStatus, String targetStatus,
                             UUID allowedRoleId, boolean requiredComment, int sortOrder, int minApprovers) {}

    /**
     * 为站点确保存在已发布的系统工作流（开箱即用）。
     */
    public WfDefInfo ensureSystemWorkflow(UUID siteId) {        var published = wfRepo.findBySiteIdAndStatus(siteId, WorkflowDefinitionEntity.STATUS_PUBLISHED);
        if (!published.isEmpty()) {
            return published.stream().filter(WorkflowDefinitionEntity::isSystem).findFirst()
                .map(this::toInfo).orElseGet(() -> toInfo(published.get(0)));
        }
        var def = new WorkflowDefinitionEntity();
        def.setSiteId(siteId);
        def.setName(WorkflowDefinitionEntity.SYSTEM_NAME);
        def.setDescription("站点内置草稿→审核→发布流程");
        def.setSystem(true);
        def.setStatus(WorkflowDefinitionEntity.STATUS_PUBLISHED);
        def = wfRepo.save(def);
        saveTransitions(def.getId(), List.of(
            new TransInput(ContentItemEntity.STATUS_DRAFT, ContentItemEntity.STATUS_IN_REVIEW, null, false, 0, 0),
            new TransInput(ContentItemEntity.STATUS_WITHDRAWN, ContentItemEntity.STATUS_IN_REVIEW, null, false, 1, 0),
            new TransInput(ContentItemEntity.STATUS_IN_REVIEW, ContentItemEntity.STATUS_PUBLISHED, null, false, 2, 0),
            new TransInput(ContentItemEntity.STATUS_IN_REVIEW, ContentItemEntity.STATUS_DRAFT, null, true, 3, 0)
        ));
        return toInfo(def);
    }

    // ---------- Task ----------

    /**
     * 审核任务。
     *
     * <p>{@code contentTitle} / {@code contentTypeId} 供「系统 → 工作流 → 待办」直接展示标题
     * （此前只有 {@code contentItemId}，列表里是一串 UUID，审核人无法判断这是什么内容），
     * 并在点击标题打开只读审核弹窗时据此加载内容模型、按布局渲染字段。</p>
     *
     * <p>两者都允许为 null：内容可能已被删除（历史任务仍要能列出并处理），
     * 此时界面回退展示编号，不因此让整条待办消失。</p>
     */
    public record TaskInfo(UUID id, UUID contentItemId, int contentVersion,
                           String sourceStatus, String targetStatus,
                           String assignee, String status, String comment,
                           String resolvedBy, Instant createdAt, String submitter,
                           Instant scheduledAt, String executionState,
                           int confirmedApprovers, int requiredApprovers, String delegatedFrom,
                           String contentTitle, UUID contentTypeId) {
        public static TaskInfo from(WorkflowTaskEntity t) {
            return from(t, 0, 1, null, null);
        }

        public static TaskInfo from(WorkflowTaskEntity t, int confirmed, int required) {
            return from(t, confirmed, required, null, null);
        }

        public static TaskInfo from(WorkflowTaskEntity t, int confirmed, int required,
                                    String contentTitle, UUID contentTypeId) {
            return new TaskInfo(t.getId(), t.getContentItemId(), t.getContentVersion(),
                t.getSourceStatus(), t.getTargetStatus(), t.getAssignee(), t.getStatus(),
                t.getComment(), t.getResolvedBy(), t.getCreatedAt(), t.getSubmitter(),
                t.getScheduledAt(), t.getExecutionState(), confirmed, required, t.getDelegatedFrom(),
                contentTitle, contentTypeId);
        }
    }

    public record ActionInfo(String sourceStatus, String targetStatus, boolean requiredComment) {}

    public record TimelineEntry(String actionKey, String fromStatus, String toStatus,
                                String actor, String comment, Instant createdAt) {}

    /** 提交审核：根据工作流定义创建任务分配 */
    public TaskInfo submitForReview(UUID wfDefId, UUID contentItemId, String targetStatus, String assignee) {
        return submitForReview(wfDefId, contentItemId, targetStatus, assignee, null, null);
    }

    public TaskInfo submitForReview(UUID wfDefId, UUID contentItemId, String targetStatus,
                                    String assignee, String submitter) {
        return submitForReview(wfDefId, contentItemId, targetStatus, assignee, submitter, null);
    }

    public TaskInfo submitForReview(UUID wfDefId, UUID contentItemId, String targetStatus,
                                    String assignee, String submitter, Instant scheduledAt) {
        var item = itemRepo.findById(contentItemId)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "内容不存在"));
        if (taskRepo.existsByContentItemIdAndStatus(item.getId(), WorkflowTaskEntity.STATUS_PENDING)) {
            throw new BusinessException(ErrorCodes.ALREADY_IN_REVIEW, "内容已在审核中");
        }
        var wf = requireDef(wfDefId);
        if (WorkflowDefinitionEntity.STATUS_DEPRECATED.equals(wf.getStatus())) {
            throw new BusinessException(ErrorCodes.CONFLICT, "已废弃的工作流不能用于提交");
        }
        validateFuture(scheduledAt, "scheduledAt");
        String target = (targetStatus == null || targetStatus.isBlank())
            ? ContentItemEntity.STATUS_PUBLISHED : targetStatus;
        requireTransition(wf.getId(), item.getStatus(), target);
        // TC-M08-014：进入审核态前回读字段值，required 字段为空即拒绝——否则内容会带着
        // 「空摘要」停在 IN_REVIEW 并生成待办。单测直构 WorkflowService 时 contentService 可能为 null。
        if (ContentItemEntity.STATUS_IN_REVIEW.equals(target) && contentService != null) {
            contentService.assertRequiredFieldsPresent(contentItemId);
        }
        String previous = item.getStatus();

        var task = WorkflowTaskEntity.create(item.getSiteId(), wf.getId(), item.getId(), item.getCurrentVersion(),
            previous, target, assignee, submitter);
        task.setScheduledAt(scheduledAt);
        taskRepo.save(task);

        item.setStatus(ContentItemEntity.STATUS_IN_REVIEW);
        item.setInWorkflow(true);
        itemRepo.save(item);

        appendLog(item.getSiteId(), task.getId(), item.getId(), "submit_for_review",
            previous, ContentItemEntity.STATUS_IN_REVIEW, actorOr(submitter), null);
        emit(WorkflowTaskEvent.SUBMITTED, task, item, actorOr(submitter), null);
        audit.write(actorOr(submitter), "WORKFLOW_SUBMIT", "ReviewTask",
            task.getId().toString(), item.getSiteId(), "SUCCESS", "content=" + item.getId());
        return toTaskInfo(task, item);
    }

    /**
     * DEF-M12-014：该内容是否已有待办任务。
     *
     * <p>状态接口（{@code POST /contents/{id}/status}）推进到 {@code IN_REVIEW} 时先问一句，
     * 避免对同一内容重复建任务（{@link #submitForReview} 遇到重复会抛 {@code ALREADY_IN_REVIEW}）。</p>
     */
    public boolean hasPendingTask(UUID contentItemId) {
        if (contentItemId == null) return false;
        return taskRepo.existsByContentItemIdAndStatus(contentItemId, WorkflowTaskEntity.STATUS_PENDING);
    }

    // ── ContentService.ReviewTaskPort 实现（依赖倒置，见端口 javadoc）─────────
    //
    // 三处适配的共同理由：端口签名**只用 JDK 类型**，以免「返回 workflow 自定义类型」
    // 重新构成 content → workflow 的字节码引用而使模块边界失效。
    // 方法名与既有 API 刻意不同（hasPendingTask vs hasPendingReviewTask、
    // ensureSystemWorkflow vs ensureSystemWorkflowId），使「经由端口调用」在代码里可辨识，
    // 便于日后审计跨模块调用点。

    @Override
    public boolean hasPendingReviewTask(UUID contentItemId) {
        return hasPendingTask(contentItemId);
    }

    @Override
    public UUID ensureSystemWorkflowId(UUID siteId) {
        WfDefInfo info = ensureSystemWorkflow(siteId);
        return info == null ? null : info.id();
    }

    @Override
    public void submitReviewTask(UUID wfDefId, UUID contentItemId, String targetStatus,
                                 String assignee, String actor) {
        // 委托给既有实现（返回 TaskInfo 被丢弃：端口只承诺「已提交」语义）
        submitForReview(wfDefId, contentItemId, targetStatus, assignee, actor);
    }

    /** 审批通过 */
    public TaskInfo approve(UUID taskId, String comment, String resolvedBy) {
        var task = requirePending(taskId);
        var item = itemRepo.findById(task.getContentItemId())
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "内容不存在"));
        if (userAccess.isPeerReviewRequired(item.getSiteId())
            && resolvedBy != null && resolvedBy.equals(task.getSubmitter())) {
            audit.write(resolvedBy, "WORKFLOW_APPROVE", "ReviewTask",
                task.getId().toString(), item.getSiteId(), "DENIED", "SELF_APPROVAL_FORBIDDEN");
            throw new BusinessException(ErrorCodes.SELF_APPROVAL_FORBIDDEN, "站点已开启同行评审，提交人不能批准自己的任务");
        }
        int required = minApproversFor(task);
        if (required > 1) {
            if (confirmationRepo.existsByTaskIdAndActionKeyAndActor(
                task.getId(), WorkflowTaskConfirmationEntity.ACTION_APPROVE, resolvedBy)) {
                throw new BusinessException(ErrorCodes.CONFLICT, "您已确认过该会签");
            }
            confirmationRepo.save(WorkflowTaskConfirmationEntity.of(
                task.getId(), WorkflowTaskConfirmationEntity.ACTION_APPROVE, resolvedBy));
            int confirmed = (int) confirmationRepo.countByTaskIdAndActionKey(
                task.getId(), WorkflowTaskConfirmationEntity.ACTION_APPROVE);
            appendLog(item.getSiteId(), task.getId(), item.getId(), "confirm",
                item.getStatus(), item.getStatus(), resolvedBy,
                "confirmed=" + confirmed + "/" + required);
            audit.write(resolvedBy, "WORKFLOW_CONFIRM", "ReviewTask",
                task.getId().toString(), item.getSiteId(), "SUCCESS", confirmed + "/" + required);
            if (confirmed < required) {
                return toTaskInfo(task, item);
            }
        }
        String previous = item.getStatus();
        task.setStatus(WorkflowTaskEntity.STATUS_APPROVED);
        task.setComment(comment);
        resolve(task, resolvedBy);

        if (isFuture(task.getScheduledAt())) {
            // DEF-WF-02：定时发布同样要固化为「后继转换」的终态，否则执行器会按提交审核时的
            // IN_REVIEW 回填，把已批准内容退回审核态。
            task.setTargetStatus(resolveApprovedTarget(task, item));
            item.setStatus(ContentItemEntity.STATUS_APPROVED);
            item.setInWorkflow(false);
            item.setScheduledPublishAt(task.getScheduledAt());
            itemRepo.save(item);
            taskRepo.save(task);
            appendLog(item.getSiteId(), task.getId(), item.getId(), "approve",
                previous, ContentItemEntity.STATUS_APPROVED, resolvedBy, comment);
        } else {
            applyApprovedTarget(task, item);
            task.setExecutionState(WorkflowTaskEntity.EXEC_SUCCEEDED);
            taskRepo.save(task);
            item = itemRepo.findById(item.getId()).orElse(item);
            appendLog(item.getSiteId(), task.getId(), item.getId(), "approve",
                previous, item.getStatus(), resolvedBy, comment);
        }
        emit(WorkflowTaskEvent.APPROVED, task, item, resolvedBy, comment);
        audit.write(resolvedBy, "WORKFLOW_APPROVE", "ReviewTask",
            task.getId().toString(), item.getSiteId(), "SUCCESS", null);
        return toTaskInfo(task, item);
    }

    /** 驳回 */
    public TaskInfo reject(UUID taskId, String comment, String resolvedBy) {
        var task = requirePending(taskId);
        if (comment == null || comment.isBlank()) {
            throw new BusinessException(ErrorCodes.WORKFLOW_COMMENT_REQUIRED, "驳回必须填写原因");
        }
        var item = itemRepo.findById(task.getContentItemId())
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "内容不存在"));
        String previous = item.getStatus();
        task.setStatus(WorkflowTaskEntity.STATUS_REJECTED);
        task.setComment(comment);
        resolve(task, resolvedBy);
        taskRepo.save(task);

        item.setStatus(ContentItemEntity.STATUS_DRAFT);
        item.setInWorkflow(false);
        item.setLastRejectionReason(comment);
        itemRepo.save(item);

        appendLog(item.getSiteId(), task.getId(), item.getId(), "reject",
            previous, ContentItemEntity.STATUS_DRAFT, resolvedBy, comment);
        emit(WorkflowTaskEvent.REJECTED, task, item, resolvedBy, comment);
        audit.write(resolvedBy, "WORKFLOW_REJECT", "ReviewTask",
            task.getId().toString(), item.getSiteId(), "SUCCESS", comment);
        return toTaskInfo(task, item);
    }

    public TaskInfo cancel(UUID taskId, String comment, String actor) {
        var task = requirePending(taskId);
        var item = itemRepo.findById(task.getContentItemId())
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "内容不存在"));
        String previous = item.getStatus();
        task.setStatus(WorkflowTaskEntity.STATUS_CANCELLED);
        task.setComment(comment);
        resolve(task, actor);
        taskRepo.save(task);

        String restored = task.getSourceStatus() == null ? ContentItemEntity.STATUS_DRAFT : task.getSourceStatus();
        item.setStatus(restored);
        item.setInWorkflow(false);
        itemRepo.save(item);

        appendLog(item.getSiteId(), task.getId(), item.getId(), "cancel_review",
            previous, restored, actor, comment);
        emit(WorkflowTaskEvent.CANCELLED, task, item, actor, comment);
        audit.write(actor, "WORKFLOW_CANCEL", "ReviewTask",
            task.getId().toString(), item.getSiteId(), "SUCCESS", null);
        return toTaskInfo(task, item);
    }

    /** 委派 */
    public TaskInfo delegate(UUID taskId, String newAssignee, String resolvedBy) {
        var task = requirePending(taskId);
        if (newAssignee == null || newAssignee.isBlank()) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "委派对象不能为空");
        }
        // 版本分层（矩阵 E）：任务委派与超时转派对应能力位 WORKFLOW_DELEGATION（专业版及以上 ✅）。
        // 验收 G-12：能力位收敛到统一入口（WorkflowEntitlementAdapter → LicenseManager.assertCapability），
        // 未授权版本返回 403 LICENSE_CAPABILITY_DISABLED；entitlement 未注入（单测/降级）时不拦截。
        if (entitlement != null) {
            entitlement.requireCapability("WORKFLOW_DELEGATION");
        }
        var item = itemRepo.findById(task.getContentItemId()).orElseThrow();
        if (userAccess != null && !userAccess.canReview(newAssignee, item.getSiteId())) {
            throw new BusinessException(ErrorCodes.DELEGATION_PERMISSION_DENIED, "委派对象没有站点审核权限");
        }
        task.setDelegatedFrom(resolvedBy);
        task.setAssignee(newAssignee);
        taskRepo.save(task);

        appendLog(item.getSiteId(), task.getId(), item.getId(), "delegate",
            item.getStatus(), item.getStatus(), resolvedBy, "to=" + newAssignee);
        emit(WorkflowTaskEvent.DELEGATED, task, item, resolvedBy, newAssignee);
        audit.write(resolvedBy, "WORKFLOW_DELEGATE", "ReviewTask",
            task.getId().toString(), item.getSiteId(), "SUCCESS", "assignee=" + newAssignee);
        return toTaskInfo(task, item);
    }

    public List<TaskInfo> pendingTasks(String username) {
        return pendingTasks(username, null);
    }

    /**
     * 待办列表（系统 → 工作流 → 待办）。
     *
     * <p>只保留审核人**能真正处理**的任务，避免列表里出现点开就 403/404 的僵尸条目：</p>
     * <ul>
     *   <li><b>内容已删除</b>：任务指向的条目不存在 → 隐藏（历史脏任务不清理，但不再展示）；</li>
     *   <li><b>非当前站点</b>：条目所属站点与 {@code siteId} 不一致 → 隐藏（委派/换站后常见的错位）；</li>
     *   <li><b>无查看权限</b>：对象 ACL 被打破且未授予本人 view，或站点能力失效 → 隐藏。</li>
     * </ul>
     *
     * <p>站点以**内容条目**的 siteId 为准而不是任务列：早期任务行可能没有 site_id，
     * 而条目归属永远可靠。{@code siteId} 为 null 时不做站点限制（内部/系统调用）。
     * 标题与模型随过滤一并批量取回，不产生 N+1。</p>
     */
    public List<TaskInfo> pendingTasks(String username, UUID siteId) {
        var tasks = taskRepo.findByAssigneeAndStatus(username, WorkflowTaskEntity.STATUS_PENDING);
        if (tasks.isEmpty()) return List.of();
        // 标题/模型一次性批量取回（待办列表每行都要显示标题），避免 N+1 查询。
        Map<UUID, ContentItemEntity> items = new LinkedHashMap<>();
        itemRepo.findAllById(tasks.stream().map(WorkflowTaskEntity::getContentItemId).distinct().toList())
            .forEach(item -> items.put(item.getId(), item));
        return tasks.stream()
            .filter(task -> visibleTo(task, items.get(task.getContentItemId()), username, siteId))
            .map(task -> toTaskInfo(task, items.get(task.getContentItemId())))
            .toList();
    }

    /** 待办可见性：内容存在 + 属于当前站点 + 审核人有权查看（见 pendingTasks 的说明）。 */
    private boolean visibleTo(WorkflowTaskEntity task, ContentItemEntity item, String username, UUID siteId) {
        if (item == null) return false;
        if (siteId != null && !siteId.equals(item.getSiteId())) return false;
        return userAccess == null || userAccess.canViewContent(username, item.getId());
    }

    public List<ActionInfo> availableActions(UUID contentItemId) {
        var item = itemRepo.findById(contentItemId)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "内容不存在"));
        var published = wfRepo.findBySiteIdAndStatus(item.getSiteId(), WorkflowDefinitionEntity.STATUS_PUBLISHED);
        if (published.isEmpty()) {
            ensureSystemWorkflow(item.getSiteId());
            published = wfRepo.findBySiteIdAndStatus(item.getSiteId(), WorkflowDefinitionEntity.STATUS_PUBLISHED);
        }
        return published.stream()
            .flatMap(def -> transRepo.findByWorkflowDefinitionIdAndSourceStatus(def.getId(), item.getStatus()).stream())
            .map(t -> new ActionInfo(t.getSourceStatus(), t.getTargetStatus(), t.isRequiredComment()))
            .toList();
    }

    public List<TimelineEntry> timeline(UUID taskId) {
        requireTask(taskId);
        return logRepo.findByTaskIdOrderByCreatedAtAsc(taskId).stream()
            .map(l -> new TimelineEntry(l.getActionKey(), l.getFromStatus(), l.getToStatus(),
                l.getActor(), l.getComment(), l.getCreatedAt()))
            .toList();
    }

    public TaskInfo reschedule(UUID taskId, Instant scheduledAt, String actor) {
        var task = requireTask(taskId);
        boolean pending = WorkflowTaskEntity.STATUS_PENDING.equals(task.getStatus());
        boolean waiting = WorkflowTaskEntity.STATUS_APPROVED.equals(task.getStatus())
            && task.getExecutionState() == null;
        if (!pending && !waiting) {
            throw new BusinessException(ErrorCodes.INVALID_TASK_STATE, "只有待审或待执行的定时任务可以改期");
        }
        validateFuture(scheduledAt, "scheduledAt");
        task.setScheduledAt(scheduledAt);
        taskRepo.save(task);
        if (waiting) {
            itemRepo.findById(task.getContentItemId()).ifPresent(item -> {
                item.setScheduledPublishAt(scheduledAt);
                itemRepo.save(item);
            });
        }
        var item = itemRepo.findById(task.getContentItemId())
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "内容不存在"));
        appendLog(item.getSiteId(), task.getId(), item.getId(), "reschedule",
            item.getStatus(), item.getStatus(), actor, scheduledAt == null ? null : scheduledAt.toString());
        audit.write(actor, "WORKFLOW_RESCHEDULE", "ReviewTask",
            task.getId().toString(), item.getSiteId(), "SUCCESS",
            scheduledAt == null ? null : scheduledAt.toString());
        return toTaskInfo(task, item);
    }

    public Instant scheduleWithdraw(UUID contentItemId, Instant scheduledWithdrawAt, String actor) {
        var item = itemRepo.findById(contentItemId)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "内容不存在"));
        if (!ContentItemEntity.STATUS_PUBLISHED.equals(item.getStatus())) {
            throw new BusinessException(ErrorCodes.INVALID_TASK_STATE, "只有已发布内容可以定时撤回");
        }
        if (scheduledWithdrawAt != null) {
            validateFuture(scheduledWithdrawAt, "scheduledWithdrawAt");
        }
        item.setScheduledWithdrawAt(scheduledWithdrawAt);
        itemRepo.save(item);
        audit.write(actor, "CONTENT_SCHEDULE_WITHDRAW", "ContentItem",
            item.getId().toString(), item.getSiteId(), "SUCCESS",
            scheduledWithdrawAt == null ? "cleared" : scheduledWithdrawAt.toString());
        return item.getScheduledWithdrawAt();
    }

    /**
     * 扫描器调用：行锁 + execution_state CAS，保证定时发布只执行一次。
     */
    public boolean executeDueScheduledPublish(UUID taskId, Instant now) {
        var task = taskRepo.findByIdForUpdate(taskId).orElse(null);
        if (task == null) return false;
        if (!WorkflowTaskEntity.STATUS_APPROVED.equals(task.getStatus())) return false;
        if (task.getExecutionState() != null) return false;
        if (task.getScheduledAt() == null || task.getScheduledAt().isAfter(now)) return false;
        task.setExecutionState(WorkflowTaskEntity.EXEC_PROCESSING);
        taskRepo.save(task);

        var item = itemRepo.findByIdForUpdate(task.getContentItemId()).orElse(null);
        if (item == null) {
            task.setExecutionState(WorkflowTaskEntity.EXEC_FAILED);
            taskRepo.save(task);
            return false;
        }
        try {
            String previous = item.getStatus();
            applyApprovedTarget(task, item);
            task.setExecutionState(WorkflowTaskEntity.EXEC_SUCCEEDED);
            taskRepo.save(task);
            item = itemRepo.findById(item.getId()).orElse(item);
            appendLog(item.getSiteId(), task.getId(), item.getId(), "scheduled_publish",
                previous, item.getStatus(), "system", null);
            emit(WorkflowTaskEvent.SUCCEEDED, task, item, "system", null);
            audit.write("system", "WORKFLOW_SCHEDULED_PUBLISH", "ReviewTask",
                task.getId().toString(), item.getSiteId(), "SUCCESS", null);
            return true;
        } catch (RuntimeException ex) {
            log.error("scheduled publish failed task={}", taskId, ex);
            task.setExecutionState(WorkflowTaskEntity.EXEC_FAILED);
            taskRepo.save(task);
            return false;
        }
    }

    /**
     * 扫描器调用：行锁后清除 scheduled_withdraw_at，保证撤回只执行一次。
     */
    public boolean executeDueWithdraw(UUID itemId, Instant now) {
        var item = itemRepo.findByIdForUpdate(itemId).orElse(null);
        if (item == null) return false;
        if (!ContentItemEntity.STATUS_PUBLISHED.equals(item.getStatus())) return false;
        Instant due = item.getScheduledWithdrawAt();
        if (due == null || due.isAfter(now)) return false;
        item.setScheduledWithdrawAt(null);
        itemRepo.save(item);
        try {
            contentService.updateStatus(item.getId(), ContentItemEntity.STATUS_WITHDRAWN);
            audit.write("system", "CONTENT_SCHEDULED_WITHDRAW", "ContentItem",
                item.getId().toString(), item.getSiteId(), "SUCCESS", null);
            return true;
        } catch (RuntimeException ex) {
            log.error("scheduled withdraw failed item={}", itemId, ex);
            item.setScheduledWithdrawAt(due);
            itemRepo.save(item);
            return false;
        }
    }

    public List<UUID> pendingTaskIds() {
        return taskRepo.findByStatus(WorkflowTaskEntity.STATUS_PENDING).stream()
            .map(WorkflowTaskEntity::getId)
            .toList();
    }

    /**
     * 超时提醒或升级。返回 true 表示本轮发生了提醒或转派。
     */
    public boolean processTimeout(UUID taskId, Instant now) {
        var task = taskRepo.findByIdForUpdate(taskId).orElse(null);
        if (task == null || !WorkflowTaskEntity.STATUS_PENDING.equals(task.getStatus())) return false;
        var def = wfRepo.findById(task.getWorkflowDefId()).orElse(null);
        if (def == null) return false;
        Instant created = task.getCreatedAt() == null ? now : task.getCreatedAt();
        int timeoutMin = def.getTimeoutMinutes();
        int reminderMin = def.getReminderIntervalMinutes();
        var item = itemRepo.findById(task.getContentItemId()).orElse(null);
        if (item == null) return false;

        if (timeoutMin > 0 && !created.plusSeconds(timeoutMin * 60L).isAfter(now)
            && task.getTimeoutEscalatedAt() == null) {
            task.setTimeoutEscalatedAt(now);
            task.setLastTimeoutWarnedAt(now);
            if ("ESCALATE".equalsIgnoreCase(def.getTimeoutActionKey()) && userAccess != null) {
                userAccess.findSiteAdminUsername(item.getSiteId()).ifPresent(admin -> {
                    task.setDelegatedFrom(task.getAssignee());
                    task.setAssignee(admin);
                });
            }
            taskRepo.save(task);
            appendLog(item.getSiteId(), task.getId(), item.getId(), "timeout",
                item.getStatus(), item.getStatus(), "system", def.getTimeoutActionKey());
            emit(WorkflowTaskEvent.TIMEOUT, task, item, "system", def.getTimeoutActionKey());
            audit.write("system", "WORKFLOW_TIMEOUT", "ReviewTask",
                task.getId().toString(), item.getSiteId(), "SUCCESS", def.getTimeoutActionKey());
            return true;
        }
        if (reminderMin > 0 && task.getTimeoutEscalatedAt() == null
            && !created.plusSeconds(reminderMin * 60L).isAfter(now)) {
            Instant last = task.getLastTimeoutWarnedAt();
            if (last != null && last.plusSeconds(reminderMin * 60L).isAfter(now)) return false;
            task.setLastTimeoutWarnedAt(now);
            taskRepo.save(task);
            emit(WorkflowTaskEvent.TIMEOUT, task, item, "system", "REMINDER");
            return true;
        }
        return false;
    }

    // ---------- Notification (compat facade) ----------

    public record NotifInfo(UUID id, String type, String title, String body, boolean read, Instant createdAt) {}

    public List<NotifInfo> notifications(String username) {
        return notificationService.list(username, false).stream()
            .map(n -> new NotifInfo(n.id(), n.type(), n.title(), n.body(), n.read(), n.createdAt()))
            .toList();
    }

    public void markRead(UUID notifId) {
        notificationService.markRead(notifId);
    }

    public int markAllRead(String username) {
        return notificationService.markAllRead(username);
    }

    public long unreadCount(String username) {
        return notificationService.unreadCount(username);
    }

    public List<NotificationService.NotifInfo> notificationsDetailed(String username, boolean unreadOnly) {
        return notificationService.list(username, unreadOnly);
    }

    private void applyApprovedTarget(WorkflowTaskEntity task, ContentItemEntity item) {
        String target = resolveApprovedTarget(task, item);
        if (ContentItemEntity.STATUS_PUBLISHED.equals(target)) {
            // GOV-004：审批链末步发布以 WORKFLOW 来源放行（AI 内容已过人工审批）
            contentService.updateStatus(item.getId(), ContentItemEntity.STATUS_PUBLISHED,
                ContentService.PublishSource.WORKFLOW);
        } else {
            item.setStatus(target);
            item.setInWorkflow(false);
            item.setScheduledPublishAt(null);
            itemRepo.save(item);
        }
    }

    /**
     * 审批推进目标解析。
     *
     * <p>常规场景：任务 {@code targetStatus} 即终态（提交时已越过当前状态），直接采用。
     *
     * <p>提交审核型任务（DEF-WF-02）：系统工作流只定义 {@code DRAFT→IN_REVIEW}，提交时
     * {@code targetStatus} 只能是 {@code IN_REVIEW}，与内容当前状态相同——此时若原样落库就会
     * 记录「IN_REVIEW→IN_REVIEW」的零推进，并把内容留在 IN_REVIEW 且再无待办（无主状态）。
     * 故按工作流定义的**后继转换**推进：取当前状态的出边，排除自环与回退到
     * {@code sourceStatus} 的驳回路径；多候选时优先 {@code PUBLISHED}，否则取定义排序首个。
     */
    private String resolveApprovedTarget(WorkflowTaskEntity task, ContentItemEntity item) {
        String taskTarget = task.getTargetStatus();
        if (taskTarget != null && !taskTarget.equals(item.getStatus())) {
            return taskTarget;
        }
        var successors = transRepo.findByWorkflowDefinitionIdAndSourceStatus(
                task.getWorkflowDefId(), item.getStatus()).stream()
            .map(WorkflowTransitionEntity::getTargetStatus)
            .filter(target -> target != null && !target.equals(item.getStatus()))
            .filter(target -> task.getSourceStatus() == null || !target.equals(task.getSourceStatus()))
            .distinct()
            .toList();
        if (successors.isEmpty()) {
            return taskTarget;
        }
        return successors.contains(ContentItemEntity.STATUS_PUBLISHED)
            ? ContentItemEntity.STATUS_PUBLISHED
            : successors.get(0);
    }

    private static boolean isFuture(Instant scheduledAt) {
        return scheduledAt != null && scheduledAt.isAfter(Instant.now());
    }

    private static void validateFuture(Instant scheduledAt, String field) {
        if (scheduledAt == null) return;
        if (!scheduledAt.isAfter(Instant.now())) {
            throw new BusinessException(ErrorCodes.SCHEDULE_INVALID, "定时时间已过或无效",
                List.of(new ApiError.FieldError(field, "定时时间必须晚于当前时间")));
        }
    }

    /** 退回型目标状态：驳回/撤回边不计入「条件分支」与「级数」（社区版基础流程必须可用）。 */
    private static final Set<String> RETURN_TARGET_STATUSES =
        Set.of(ContentItemEntity.STATUS_DRAFT, ContentItemEntity.STATUS_WITHDRAWN);

    /**
     * 版本分级门禁（矩阵 E1：社区版串行 ≤2 级、无并行会签、无条件分支）。
     *
     * <p>仅约束<b>用户提交</b>的流程定义（create / update / newVersion）；系统内置工作流
     * （{@link #ensureSystemWorkflow} 直接写转换，不经过本方法）与运行期实例不受影响。
     * 判定规则：</p>
     * <ol>
     *   <li>并行会签：任一转换 {@code minApprovers &gt; 1} → 需要 {@code WORKFLOW_PARALLEL}；</li>
     *   <li>条件分支：同一来源状态存在 ≥2 个不同的前进型目标（退回 DRAFT/WITHDRAWN 的边不计）；</li>
     *   <li>串行级数：前进型子图上的最长路径 &gt; 2 级（DRAFT→IN_REVIEW→PUBLISHED 恰为 2 级，
     *       即社区版上限口径）。</li>
     * </ol>
     *
     * <p>端口为 null（单测直接构造）或转换为空时放行；端口实现未授权时抛
     * {@code LICENSE_CAPABILITY_DISABLED}（403）。</p>
     */
    private void assertWorkflowShapeAllowed(List<TransInput> transitions) {
        if (entitlement == null || transitions == null || transitions.isEmpty()) return;
        // 1. 并行会签
        if (transitions.stream().anyMatch(t -> t.minApprovers() > 1)) {
            entitlement.requireCapability("WORKFLOW_PARALLEL");
            return;
        }
        // 2. 条件分支：同一 source 的前进型目标 ≥2
        Map<String, Set<String>> forwardTargets = new LinkedHashMap<>();
        for (var t : transitions) {
            if (t.targetStatus() == null || RETURN_TARGET_STATUSES.contains(t.targetStatus())) continue;
            forwardTargets.computeIfAbsent(t.sourceStatus(), k -> new HashSet<>()).add(t.targetStatus());
        }
        if (forwardTargets.values().stream().anyMatch(s -> s.size() >= 2)) {
            entitlement.requireCapability("WORKFLOW_PARALLEL");
            return;
        }
        // 3. 串行级数：前进型子图最长路径 > 2
        if (longestForwardPath(transitions) > 2) {
            entitlement.requireCapability("WORKFLOW_PARALLEL");
        }
    }

    /** 前进型子图（忽略退回边）上的最长简单路径边数；状态数很小（≤6），DFS 精确求解。 */
    private static int longestForwardPath(List<TransInput> transitions) {
        Map<String, List<String>> graph = new LinkedHashMap<>();
        for (var t : transitions) {
            if (t.targetStatus() == null || RETURN_TARGET_STATUSES.contains(t.targetStatus())) continue;
            graph.computeIfAbsent(t.sourceStatus(), k -> new ArrayList<>()).add(t.targetStatus());
        }
        int[] best = {0};
        Set<String> onPath = new HashSet<>();
        for (String node : graph.keySet()) {
            walkForwardDepth(node, graph, onPath, 0, best);
        }
        return best[0];
    }

    private static void walkForwardDepth(String node, Map<String, List<String>> graph,
                                         Set<String> onPath, int len, int[] best) {
        best[0] = Math.max(best[0], len);
        if (!onPath.add(node)) return;
        for (String next : graph.getOrDefault(node, List.of())) {
            walkForwardDepth(next, graph, onPath, len + 1, best);
        }
        onPath.remove(node);
    }

    private void saveTransitions(UUID defId, List<TransInput> transitions) {
        if (transitions == null) return;
        int i = 0;
        for (var t : transitions) {
            var te = new WorkflowTransitionEntity();
            te.setId(UUID.randomUUID());
            te.setWorkflowDefinitionId(defId);
            te.setSourceStatus(t.sourceStatus());
            te.setTargetStatus(t.targetStatus());
            te.setAllowedRoleId(t.allowedRoleId());
            te.setRequiredComment(t.requiredComment());
            te.setSortOrder(t.sortOrder() > 0 ? t.sortOrder() : i);
            te.setMinApprovers(Math.max(0, t.minApprovers()));
            transRepo.save(te);
            i++;
        }
    }

    private void requireTransition(UUID wfDefId, String source, String target) {
        boolean allowed = transRepo.findByWorkflowDefinitionIdAndSourceStatus(wfDefId, source).stream()
            .anyMatch(t -> t.getTargetStatus().equals(target));
        if (!allowed) {
            throw new BusinessException(ErrorCodes.WORKFLOW_ACTION_NOT_AVAILABLE,
                "当前状态不允许转换到 " + target);
        }
    }

    private WorkflowTaskEntity requirePending(UUID taskId) {
        var task = requireTask(taskId);
        if (!WorkflowTaskEntity.STATUS_PENDING.equals(task.getStatus())) {
            throw new BusinessException(ErrorCodes.INVALID_TASK_STATE, "任务已处理");
        }
        return task;
    }

    private static void resolve(WorkflowTaskEntity task, String resolvedBy) {
        task.setResolvedBy(resolvedBy);
        task.setResolvedAt(Instant.now());
    }

    private void appendLog(UUID siteId, UUID taskId, UUID contentId, String action,
                           String from, String to, String actor, String comment) {
        logRepo.save(WorkflowTransitionLogEntity.of(siteId, taskId, contentId, action, from, to, actor, comment));
    }

    private void emit(String type, WorkflowTaskEntity task, ContentItemEntity item, String actor, String comment) {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId.toString());
        payload.put("eventType", type);
        payload.put("siteId", item.getSiteId().toString());
        payload.put("taskId", task.getId().toString());
        payload.put("contentItemId", item.getId().toString());
        payload.put("actor", actor);
        try {
            outbox.write(type, "ReviewTask", task.getId().toString(), om.writeValueAsString(payload));
        } catch (Exception ex) {
            throw new BusinessException(ErrorCodes.INTERNAL_ERROR, "工作流事件写入失败");
        }
        events.publishEvent(new WorkflowTaskEvent(
            eventId, type, item.getSiteId(), task.getId(), item.getId(),
            item.getTitle(), actor, task.getSubmitter(), task.getAssignee(), comment));
    }

    private static String actorOr(String submitter) {
        return submitter == null || submitter.isBlank() ? "system" : submitter;
    }

    private TaskInfo toTaskInfo(WorkflowTaskEntity t) {
        return toTaskInfo(t, null);
    }

    /** 带内容条目的任务视图：标题/模型取自调用方已加载的条目，避免每行再查一次库。 */
    private TaskInfo toTaskInfo(WorkflowTaskEntity t, ContentItemEntity item) {
        int required = minApproversFor(t);
        int confirmed = (int) confirmationRepo.countByTaskIdAndActionKey(
            t.getId(), WorkflowTaskConfirmationEntity.ACTION_APPROVE);
        return TaskInfo.from(t, confirmed, Math.max(required, 1),
            item == null ? null : item.getTitle(),
            item == null ? null : item.getContentTypeId());
    }

    private int minApproversFor(WorkflowTaskEntity task) {
        int required = transRepo.findByWorkflowDefinitionIdAndSourceStatus(task.getWorkflowDefId(), task.getSourceStatus())
            .stream()
            .filter(t -> t.getTargetStatus().equals(task.getTargetStatus()))
            .mapToInt(WorkflowTransitionEntity::getMinApprovers)
            .max()
            .orElse(0);
        // 降级兼容（矩阵 E1）：当前版本无并行会签能力时，遗留的多审会签按单人审批继续推进。
        // 必须 clamp 而非抛错——否则降级后在途会签任务永远无法完成。
        if (required > 1 && entitlement != null && !entitlement.allowsCapability("WORKFLOW_PARALLEL")) {
            required = 1;
        }
        return required;
    }

    private WfDefInfo toInfo(WorkflowDefinitionEntity def) {
        var trans = transRepo.findByWorkflowDefinitionIdOrderBySortOrder(def.getId())
            .stream().map(t -> new TransInfo(t.getId(), t.getSourceStatus(), t.getTargetStatus(),
                t.getAllowedRoleId(), t.isRequiredComment(), t.getMinApprovers())).toList();
        return new WfDefInfo(def.getId(), def.getName(), def.getDescription(),
            def.getVersion(), def.getStatus(), def.isSystem(),
            def.getTimeoutMinutes(), def.getReminderIntervalMinutes(), def.getTimeoutActionKey(), trans);
    }

    private WorkflowDefinitionEntity requireDef(UUID id) {
        return wfRepo.findById(id).orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "工作流不存在"));
    }

    private WorkflowTaskEntity requireTask(UUID id) {
        return taskRepo.findById(id).orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "任务不存在"));
    }
}
