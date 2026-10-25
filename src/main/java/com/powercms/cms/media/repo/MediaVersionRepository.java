package com.powercms.cms.media.repo;

import com.powercms.cms.media.entity.MediaVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MediaVersionRepository extends JpaRepository<MediaVersionEntity, UUID> {
    List<MediaVersionEntity> findByMediaAssetIdOrderByVersionDesc(UUID mediaAssetId);
    Optional<MediaVersionEntity> findByMediaAssetIdAndVersion(UUID mediaAssetId, int version);
}
