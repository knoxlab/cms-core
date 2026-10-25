package com.powercms.cms.identity;

import com.powercms.cms.audit.AuditService;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.identity.entity.UserEntity;
import com.powercms.cms.identity.repo.UserRepository;
import com.powercms.cms.workflow.entity.WorkflowTaskEntity;
import com.powercms.cms.workflow.repo.WorkflowTaskRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 用户引用迁移（spec 5.3 / 16.2，P1）：禁用/删除用户前，若其为内容 owner 或
 * 工作流待办受理人，必须指定替代用户；迁移后禁用（对齐 dotCMS UserAPI 删除时迁移引用）。
 *
 * <p>首期迁移范围：内容项 {@code createdBy}（owner）与待办工作流 {@code assignee}。
 * 审计保留原 username，历史任务不改写。
 */
@Service
@Transactional
public class UserReferenceMigrator {

    /** 用户被引用的摘要（spec 5.3）。 */
    public record ReferenceSummary(long ownedContents, long pendingTasks) {
        public boolean hasReferences() {
            return ownedContents > 0 || pendingTasks > 0;
        }
    }

    private final UserRepository userRepo;
    private final ContentItemRepository contentRepo;
    private final WorkflowTaskRepository taskRepo;
    private final AuditService audit;

    public UserReferenceMigrator(UserRepository userRepo, ContentItemRepository contentRepo,
                                 WorkflowTaskRepository taskRepo, AuditService audit) {
        this.userRepo = userRepo;
        this.contentRepo = contentRepo;
        this.taskRepo = taskRepo;
        this.audit = audit;
    }

    public ReferenceSummary referencesOf(UUID userId) {
        UserEntity user = requireUser(userId);
        long owned = contentRepo.countByCreatedBy(user.getUsername());
        long pending = taskRepo.findByAssigneeAndStatus(
            user.getUsername(), WorkflowTaskEntity.STATUS_PENDING).size();
        return new ReferenceSummary(owned, pending);
    }

    /** 把 {@code fromUserId} 的内容 owner 与待办受理人迁移到 {@code toUserId}。 */
    public void reassign(UUID fromUserId, UUID toUserId, UUID operatorId) {
        if (fromUserId.equals(toUserId)) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "替代用户不能与被迁移用户相同");
        }
        UserEntity from = requireUser(fromUserId);
        UserEntity to = requireUser(toUserId);
        String fromUsername = from.getUsername();
        String toUsername = to.getUsername();

        int contents = 0;
        for (com.powercms.cms.content.entity.ContentItemEntity item
                : contentRepo.findByCreatedBy(fromUsername)) {
            item.setCreatedBy(toUsername);
            contentRepo.save(item);
            contents++;
        }
        int tasks = 0;
        for (WorkflowTaskEntity task
                : taskRepo.findByAssigneeAndStatus(fromUsername, WorkflowTaskEntity.STATUS_PENDING)) {
            task.setAssignee(toUsername);
            taskRepo.save(task);
            tasks++;
        }
        audit.write(actor(operatorId), "USER_REFERENCE_MIGRATE", "User", fromUserId.toString(),
            null, "SUCCESS", "from=" + fromUsername + ", to=" + toUsername
                + ", contents=" + contents + ", tasks=" + tasks);
    }

    /** 迁移成功后的审计保留原 username；owner 迁移改写 createdBy。 */
    private String actor(UUID operatorId) {
        if (operatorId == null) return "system";
        return userRepo.findById(operatorId).map(UserEntity::getUsername).orElse(operatorId.toString());
    }

    private UserEntity requireUser(UUID userId) {
        return userRepo.findById(userId)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "用户不存在"));
    }
}
