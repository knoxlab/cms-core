package com.powercms.cms.audit;

import com.powercms.cms.audit.entity.AuditEventEntity;
import com.powercms.cms.audit.repo.AuditEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * 审计服务：追加写，错误不影响主业务事务。
 * 通过 Spring 事务事件机制在事务提交后异步写入。
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private final AuditEventRepository auditRepo;

    AuditService(AuditEventRepository auditRepo) {
        this.auditRepo = auditRepo;
    }

    /**
     * 同步写入审计事件，用于必须在同一事务中原子写入的场景。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(String actor, String action, String objectType,
                       String objectId, UUID siteId, String result, String detail) {
        write(actor, action, objectType, objectId, siteId, result, null, detail);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(String actor, String action, String objectType,
                      String objectId, UUID siteId, String result, String traceId, String detail) {
        AuditEventEntity event = AuditEventEntity.create(
            actor, action, objectType, objectId, siteId, result, traceId, detail);
        auditRepo.save(event);
    }

    /**
     * 异步写入：监听 {@link AuditEvent} 领域事件，事务提交后执行。
     * 异步写入失败不影响主业务事务（FR-AUDIT-001）。
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAuditEvent(AuditEvent event) {
        try {
            AuditEventEntity entity = AuditEventEntity.create(
                event.actor(), event.action(), event.objectType(),
                event.objectId(), event.siteId(), event.result(),
                event.traceId(), event.detail());
            auditRepo.save(entity);
        } catch (Exception e) {
            log.error("审计写入失败 action={} object={}", event.action(), event.objectType(), e);
        }
    }

    public record AuditEvent(String actor, String action, String objectType,
                              String objectId, UUID siteId, String result,
                              String traceId, String detail) {}

    /** 从安全上下文解析当前操作者；未认证或无名操作者统一记 "system"。 */
    public static String currentActor() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
            .getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return "system";
        Object principal = auth.getPrincipal();
        if (principal instanceof com.powercms.cms.identity.AuthenticatedUserPrincipal user) {
            return user.getUserId().toString();
        }
        return auth.getName();
    }
}
