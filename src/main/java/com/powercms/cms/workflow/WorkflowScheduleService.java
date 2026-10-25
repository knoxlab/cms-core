package com.powercms.cms.workflow;

import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.workflow.repo.WorkflowTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

/**
 * 逐条独立事务扫描到期的定时发布与定时撤回（AC-WF-008：只执行一次）。
 */
@Service
public class WorkflowScheduleService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowScheduleService.class);

    private final WorkflowTaskRepository taskRepo;
    private final ContentItemRepository itemRepo;
    private final WorkflowService workflow;
    private final TransactionTemplate tx;

    WorkflowScheduleService(WorkflowTaskRepository taskRepo,
                            ContentItemRepository itemRepo,
                            WorkflowService workflow,
                            PlatformTransactionManager txManager) {
        this.taskRepo = taskRepo;
        this.itemRepo = itemRepo;
        this.workflow = workflow;
        this.tx = new TransactionTemplate(txManager);
    }

    public int processDue(Instant now) {
        Instant when = now == null ? Instant.now() : now;
        int done = 0;
        for (UUID taskId : taskRepo.findDueScheduledTaskIds(when)) {
            try {
                Boolean ok = tx.execute(status -> workflow.executeDueScheduledPublish(taskId, when));
                if (Boolean.TRUE.equals(ok)) done++;
            } catch (Exception ex) {
                log.error("scheduled publish failed task={}", taskId, ex);
            }
        }
        for (UUID itemId : itemRepo.findDueWithdrawItemIds(when)) {
            try {
                Boolean ok = tx.execute(status -> workflow.executeDueWithdraw(itemId, when));
                if (Boolean.TRUE.equals(ok)) done++;
            } catch (Exception ex) {
                log.error("scheduled withdraw failed item={}", itemId, ex);
            }
        }
        for (UUID taskId : workflow.pendingTaskIds()) {
            try {
                Boolean ok = tx.execute(status -> workflow.processTimeout(taskId, when));
                if (Boolean.TRUE.equals(ok)) done++;
            } catch (Exception ex) {
                log.error("workflow timeout scan failed task={}", taskId, ex);
            }
        }
        return done;
    }
}
