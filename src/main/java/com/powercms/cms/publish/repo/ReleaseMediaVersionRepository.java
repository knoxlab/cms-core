package com.powercms.cms.publish.repo;

import com.powercms.cms.publish.entity.ReleaseMediaVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReleaseMediaVersionRepository extends JpaRepository<ReleaseMediaVersionEntity, ReleaseMediaVersionEntity.Key> {
    List<ReleaseMediaVersionEntity> findByReleaseIdOrderByOutputPathAsc(UUID releaseId);

    /** 某资产被哪些 Release pin 引用（FR-KNX-050 删除前检查）。 */
    List<ReleaseMediaVersionEntity> findByMediaAssetId(UUID mediaAssetId);
}
