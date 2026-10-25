package com.powercms.cms.workflow.repo;

import com.powercms.cms.workflow.entity.NotificationPreferenceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreferenceEntity, UUID> {
    List<NotificationPreferenceEntity> findByUsername(String username);
    Optional<NotificationPreferenceEntity> findByUsernameAndEventTypeAndChannel(String username, String eventType, String channel);
}
