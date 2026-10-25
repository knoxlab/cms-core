package com.powercms.cms.content.relation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** FR-AI-SRC-003 挂链审计仓库。 */
public interface RelationTemplateLinkLogRepository
        extends JpaRepository<RelationTemplateLinkLogEntity, UUID> {

    List<RelationTemplateLinkLogEntity>
        findByTemplateIdOrderByCreatedAtDesc(UUID templateId);

    boolean existsByTemplateIdAndRecordContentIdAndAnchorContentId(
        UUID templateId, UUID recordContentId, UUID anchorContentId);
}
