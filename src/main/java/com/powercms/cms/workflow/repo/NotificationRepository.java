package com.powercms.cms.workflow.repo;

import com.powercms.cms.workflow.entity.NotificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<NotificationEntity, UUID> {
    List<NotificationEntity> findByRecipientOrderByCreatedAtDesc(String recipient);
    List<NotificationEntity> findByRecipientAndReadAtIsNull(String recipient);
    long countByRecipientAndReadAtIsNull(String recipient);
    java.util.Optional<NotificationEntity> findByIdempotencyKey(String idempotencyKey);
}
