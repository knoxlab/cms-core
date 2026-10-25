package com.powercms.cms.webhook.repo;

import com.powercms.cms.webhook.entity.WebhookDeliveryEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDeliveryEntity, UUID> {

    Optional<WebhookDeliveryEntity> findBySubscriptionIdAndEventId(UUID subscriptionId, UUID eventId);

    boolean existsBySubscriptionIdAndEventId(UUID subscriptionId, UUID eventId);

    List<WebhookDeliveryEntity> findByStatus(String status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from WebhookDeliveryEntity d where d.id = :id")
    Optional<WebhookDeliveryEntity> findByIdForUpdate(@Param("id") UUID id);

    List<WebhookDeliveryEntity> findBySubscriptionIdOrderByCreatedAtDesc(UUID subscriptionId, Pageable pageable);

    @Query("""
        select d from WebhookDeliveryEntity d
        where d.status = 'PENDING'
          and d.nextAttemptAt is not null
          and d.nextAttemptAt <= :now
        order by d.nextAttemptAt asc
        """)
    List<WebhookDeliveryEntity> findDueBatch(@Param("now") Instant now, Pageable pageable);

    long countByStatus(String status);
}
