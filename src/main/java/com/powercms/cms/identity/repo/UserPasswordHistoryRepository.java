package com.powercms.cms.identity.repo;

import com.powercms.cms.identity.entity.UserPasswordHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface UserPasswordHistoryRepository extends JpaRepository<UserPasswordHistoryEntity, UUID> {

    List<UserPasswordHistoryEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
