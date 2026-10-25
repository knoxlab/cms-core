package com.powercms.cms.model;

import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.sitepackage.entity.SiteContainerEntity;
import com.powercms.cms.sitepackage.entity.SiteDropZoneEntity;
import com.powercms.cms.sitepackage.entity.SiteTemplateEntity;
import com.powercms.cms.sitepackage.repo.SiteContainerRepository;
import com.powercms.cms.sitepackage.repo.SiteContainerStructureRepository;
import com.powercms.cms.sitepackage.repo.SiteDropZoneRepository;
import com.powercms.cms.sitepackage.repo.SiteTemplateRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 内容模型变更影响评估（FR-AGT-MODEL-008）：类型变更 dry-run 时联动统计
 * 「受影响内容条数 + 引用了该类型的容器映射 + 绑定了这些容器的模板」，
 * 供 Agent 在确认前向用户解释影响面（如「改这个字段会影响 347 篇内容和 2 个模板」）。
 *
 * <p>只读聚合，不产生任何副作用；跨站点对象一律不计入（容器/模板按站点过滤，
 * 容器映射通过 {@code structure_id} 反查，投放区经 {@code container_id} 反查模板）。
 */
@Service
public class ModelImpactService {

    private final ContentItemRepository contentItems;
    private final SiteContainerStructureRepository containerStructures;
    private final SiteContainerRepository containers;
    private final SiteDropZoneRepository dropZones;
    private final SiteTemplateRepository templates;

    public ModelImpactService(ContentItemRepository contentItems,
                             SiteContainerStructureRepository containerStructures,
                             SiteContainerRepository containers,
                             SiteDropZoneRepository dropZones,
                             SiteTemplateRepository templates) {
        this.contentItems = contentItems;
        this.containerStructures = containerStructures;
        this.containers = containers;
        this.dropZones = dropZones;
        this.templates = templates;
    }

    /** 受影响容器（引用了该内容类型的容器映射）。 */
    public record AffectedContainer(UUID id, String name) {}

    /** 受影响模板（绑定了受影响容器的投放区所在模板）。 */
    public record AffectedTemplate(UUID id, String name) {}

    /**
     * 影响评估结果。
     *
     * @param contentCount 受影响内容条数
     * @param containers   受影响容器映射（按名称去重、排序）
     * @param templates    受影响模板（按名称去重、排序）
     * @param destructive  是否为破坏性变更（本次移除字段/改类型）
     * @param summary      人类可读影响摘要
     */
    public record ImpactReport(long contentCount, List<AffectedContainer> containers,
                               List<AffectedTemplate> templates, boolean destructive, String summary) {

        /** 无影响（无内容引用、无容器/模板引用、非破坏性变更）。 */
        public static ImpactReport none() {
            return new ImpactReport(0, List.of(), List.of(), false, "无内容或容器/模板引用该模型");
        }
    }

    /**
     * 评估内容模型变更影响。
     *
     * @param siteId           站点 ID（容器/模板归属过滤）
     * @param contentTypeId    内容类型 ID
     * @param removedFieldCodes 本次变更移除的字段编码（非空即标记破坏性变更）
     */
    public ImpactReport assess(UUID siteId, UUID contentTypeId, Set<String> removedFieldCodes) {
        boolean destructive = removedFieldCodes != null && !removedFieldCodes.isEmpty();
        long contentCount = contentItems.countByContentTypeId(contentTypeId);

        List<UUID> containerIds = containerStructures.findByStructureId(contentTypeId).stream()
            .map(com.powercms.cms.sitepackage.entity.SiteContainerStructureEntity::getContainerId)
            .distinct()
            .toList();

        Map<UUID, String> containerNames = new LinkedHashMap<>();
        if (!containerIds.isEmpty()) {
            for (SiteContainerEntity container : containers.findAllById(containerIds)) {
                if (siteId == null || siteId.equals(container.getSiteId())) {
                    containerNames.putIfAbsent(container.getId(), container.getName());
                }
            }
        }
        Set<UUID> siteContainerIds = new LinkedHashSet<>(containerNames.keySet());

        Map<UUID, String> templateNames = new LinkedHashMap<>();
        if (!siteContainerIds.isEmpty()) {
            List<UUID> templateIds = dropZones.findByContainerIdIn(siteContainerIds).stream()
                .map(SiteDropZoneEntity::getTemplateId)
                .distinct()
                .toList();
            if (!templateIds.isEmpty()) {
                for (SiteTemplateEntity template : templates.findAllById(templateIds)) {
                    if (siteId == null || siteId.equals(template.getSiteId())) {
                        templateNames.putIfAbsent(template.getId(), template.getName());
                    }
                }
            }
        }

        List<AffectedContainer> affectedContainers = containerNames.entrySet().stream()
            .map(e -> new AffectedContainer(e.getKey(), e.getValue()))
            .sorted(java.util.Comparator.comparing(c -> c.name() == null ? "" : c.name(),
                String.CASE_INSENSITIVE_ORDER))
            .toList();
        List<AffectedTemplate> affectedTemplates = templateNames.entrySet().stream()
            .map(e -> new AffectedTemplate(e.getKey(), e.getValue()))
            .sorted(java.util.Comparator.comparing(t -> t.name() == null ? "" : t.name(),
                String.CASE_INSENSITIVE_ORDER))
            .toList();

        String summary = (destructive ? "破坏性变更：将移除字段 " + removedFieldCodes : "变更影响")
            + "，影响 " + contentCount + " 篇内容、"
            + affectedContainers.size() + " 个容器映射、"
            + affectedTemplates.size() + " 个模板";
        return new ImpactReport(contentCount, affectedContainers, affectedTemplates, destructive, summary);
    }
}
