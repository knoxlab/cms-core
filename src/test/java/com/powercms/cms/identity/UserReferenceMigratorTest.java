package com.powercms.cms.identity;

import com.powercms.cms.audit.AuditService;
import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.identity.entity.UserEntity;
import com.powercms.cms.identity.repo.UserRepository;
import com.powercms.cms.workflow.entity.WorkflowTaskEntity;
import com.powercms.cms.workflow.repo.WorkflowTaskRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户引用迁移（spec 5.3 / 16.2，P1）：禁用/删除前内容 owner 与工作流受理人迁移。
 */
class UserReferenceMigratorTest {

    private final UserRepository users = mock(UserRepository.class);
    private final ContentItemRepository contents = mock(ContentItemRepository.class);
    private final WorkflowTaskRepository tasks = mock(WorkflowTaskRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private UserReferenceMigrator migrator;

    @BeforeEach
    void setUp() {
        migrator = new UserReferenceMigrator(users, contents, tasks, audit);
    }

    private UserEntity user(UUID id, String username) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setUsername(username);
        return u;
    }

    @Test
    void referencesOfCountsOwnedContentsAndPendingTasks() {
        UUID userId = UUID.randomUUID();
        when(users.findById(userId)).thenReturn(Optional.of(user(userId, "alice")));
        when(contents.countByCreatedBy("alice")).thenReturn(3L);
        WorkflowTaskEntity pending = WorkflowTaskEntity.create(
            UUID.randomUUID(), UUID.randomUUID(), 1, "DRAFT", "REVIEW", "alice");
        when(tasks.findByAssigneeAndStatus("alice", WorkflowTaskEntity.STATUS_PENDING))
            .thenReturn(List.of(pending, pending));

        UserReferenceMigrator.ReferenceSummary summary = migrator.referencesOf(userId);

        assertThat(summary.ownedContents()).isEqualTo(3);
        assertThat(summary.pendingTasks()).isEqualTo(2);
        assertThat(summary.hasReferences()).isTrue();
    }

    @Test
    void reassignMigratesContentOwnerAndPendingTasks() {
        UUID fromId = UUID.randomUUID(), toId = UUID.randomUUID(), operatorId = UUID.randomUUID();
        when(users.findById(fromId)).thenReturn(Optional.of(user(fromId, "alice")));
        when(users.findById(toId)).thenReturn(Optional.of(user(toId, "bob")));
        when(users.findById(operatorId)).thenReturn(Optional.of(user(operatorId, "admin")));

        ContentItemEntity item = new ContentItemEntity();
        item.setId(UUID.randomUUID());
        item.setCreatedBy("alice");
        when(contents.findByCreatedBy("alice")).thenReturn(List.of(item));

        WorkflowTaskEntity pending = WorkflowTaskEntity.create(
            UUID.randomUUID(), UUID.randomUUID(), 1, "DRAFT", "REVIEW", "alice");
        when(tasks.findByAssigneeAndStatus("alice", WorkflowTaskEntity.STATUS_PENDING))
            .thenReturn(List.of(pending));

        migrator.reassign(fromId, toId, operatorId);

        assertThat(item.getCreatedBy()).isEqualTo("bob");
        assertThat(pending.getAssignee()).isEqualTo("bob");
        verify(contents).save(item);
        verify(tasks).save(pending);
    }

    @Test
    void reassignRejectsSameUser() {
        UUID userId = UUID.randomUUID();
        when(users.findById(userId)).thenReturn(Optional.of(user(userId, "alice")));

        assertThatThrownBy(() -> migrator.reassign(userId, userId, userId))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ErrorCodes.VALIDATION_FAILED);
        verify(contents, never()).save(any());
        verify(tasks, never()).save(any());
    }

    @Test
    void reassignDoesNotRewriteResolvedTasks() {
        UUID fromId = UUID.randomUUID(), toId = UUID.randomUUID(), operatorId = UUID.randomUUID();
        when(users.findById(fromId)).thenReturn(Optional.of(user(fromId, "alice")));
        when(users.findById(toId)).thenReturn(Optional.of(user(toId, "bob")));
        when(users.findById(operatorId)).thenReturn(Optional.of(user(operatorId, "admin")));
        when(contents.findByCreatedBy("alice")).thenReturn(List.of());

        WorkflowTaskEntity approved = WorkflowTaskEntity.create(
            UUID.randomUUID(), UUID.randomUUID(), 1, "DRAFT", "REVIEW", "alice");
        approved.setStatus(WorkflowTaskEntity.STATUS_APPROVED);
        // 只有待办会出现在 find 结果中；已决任务不参与迁移
        when(tasks.findByAssigneeAndStatus("alice", WorkflowTaskEntity.STATUS_PENDING))
            .thenReturn(List.of());

        migrator.reassign(fromId, toId, operatorId);

        assertThat(approved.getAssignee()).isEqualTo("alice");
        verify(tasks, never()).save(any());
    }
}
