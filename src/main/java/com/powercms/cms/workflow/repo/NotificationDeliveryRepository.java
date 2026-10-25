package com.powercms.cms.workflow.repo;

import com.powercms.cms.workflow.entity.NotificationDeliveryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDeliveryEntity, Long> {
    Optional<NotificationDeliveryEntity> findByIdempotencyKeyAndChannel(String idempotencyKey, String channel);
    boolean existsByIdempotencyKeyAndChannelAndStatus(String idempotencyKey, String channel, String status);
}
