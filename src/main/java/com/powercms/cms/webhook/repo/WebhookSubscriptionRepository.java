package com.powercms.cms.webhook.repo;

import com.powercms.cms.webhook.entity.WebhookSubscriptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WebhookSubscriptionRepository extends JpaRepository<WebhookSubscriptionEntity, UUID> {

    List<WebhookSubscriptionEntity> findBySiteIdOrderByNameAsc(UUID siteId);

    List<WebhookSubscriptionEntity> findBySiteIdAndActiveTrue(UUID siteId);

    /** License 用量水位：启用中的 Webhook 订阅数。 */
    long countByActiveTrue();
}
