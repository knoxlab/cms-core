package com.powercms.cms.content.relation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** FR-AI-SRC-003 关系模板仓库。 */
public interface RelationTemplateRepository extends JpaRepository<RelationTemplateEntity, UUID> {

    List<RelationTemplateEntity> findBySiteIdOrderByNameAsc(UUID siteId);

    List<RelationTemplateEntity> findByDatasourceIdAndStatus(UUID datasourceId, String status);

    boolean existsBySiteIdAndName(UUID siteId, String name);
}
