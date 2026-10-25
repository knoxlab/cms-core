package com.powercms.cms.outbox;

import com.powercms.cms.outbox.entity.OutboxEventEntity;
import com.powercms.cms.outbox.repo.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 事务 Outbox 写入服务。
 * 业务服务在同事务中调用 {@link #write(String, String, String, String)} 写入事件，
 * 而后由定时任务扫描 published_at IS NULL 的记录投递给消费者（规格书 9.2）。
 */
@Service
public class OutboxService {

    private final OutboxEventRepository repo;

    public OutboxService(OutboxEventRepository repo) {
        this.repo = repo;
    }

    /**
     * 在业务事务中写入 Outbox 记录；必须在 @Transactional 方法中调用以确保原子性。
     */
    public void write(String eventType, String aggregateType, String aggregateId, String payload) {
        repo.save(OutboxEventEntity.create(eventType, aggregateType, aggregateId, payload));
    }
}
