package com.powercms.cms.workflow.repo;

import com.powercms.cms.workflow.entity.NotificationTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplateEntity, UUID> {
    List<NotificationTemplateEntity> findBySiteIdIsNullOrderByTemplateKey();
    Optional<NotificationTemplateEntity> findBySiteIdIsNullAndTemplateKey(String templateKey);
    Optional<NotificationTemplateEntity> findBySiteIdAndTemplateKey(UUID siteId, String templateKey);
    List<NotificationTemplateEntity> findBySiteIdOrderByTemplateKey(UUID siteId);
}
