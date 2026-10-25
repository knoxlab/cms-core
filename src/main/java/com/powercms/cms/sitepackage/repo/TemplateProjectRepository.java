package com.powercms.cms.sitepackage.repo;

import com.powercms.cms.sitepackage.entity.TemplateProjectEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TemplateProjectRepository extends JpaRepository<TemplateProjectEntity, UUID> {
    java.util.List<TemplateProjectEntity> findBySiteIdAndProjectType(UUID siteId, String projectType);
    List<TemplateProjectEntity> findBySiteId(UUID siteId);
    /** FR-AGT-TPL-008：按站点与模板功能类型筛选 */
    List<TemplateProjectEntity> findBySiteIdAndTemplateType(UUID siteId, String templateType);
    /** 版本分级（矩阵 C3）：社区版单站点 1 个模板工程的计数依据 */
    long countBySiteId(UUID siteId);
}
