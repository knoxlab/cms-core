package com.powercms.cms.outbox.repo;

import com.powercms.cms.outbox.entity.OutboxEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {

    List<OutboxEventEntity> findByPublishedAtIsNullOrderByOccurredAt();
}
