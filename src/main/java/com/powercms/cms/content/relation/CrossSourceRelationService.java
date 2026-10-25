package com.powercms.cms.content.relation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.audit.AuditService;
import com.powercms.cms.content.ContentService;
import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * FR-AI-SRC-003 跨源内容关系：关系模板（自动挂链）+ 来源域导航分组。
 *
 * <ul>
 *   <li>模板：站点级「外部记录 → 锚点内容」挂链规则。PIM 数据源（SRC-001）同步
 *       upsert 条目后回调 {@link #autoLink}，按锚点字段匹配把新条目与产品建立
 *       {@link ContentRelationItemEntity} 实例（复用 FR-CM-RELATION 既有关系）；</li>
 *   <li>来源域：content_item.source_domain（CMS / DAM:端点 / PIM:数据源），
 *       跨源导航按此分组（产品入口 → 关联资产/参数/FAQ）。</li>
 * </ul>
 */
@Service
public class CrossSourceRelationService {
    private static final Logger log = LoggerFactory.getLogger(CrossSourceRelationService.class);

    /** 来源域常量：人工/编辑器创建；DAM/PIM 由同步链路写入「前缀:名称」。 */
    public static final String DOMAIN_CMS = "CMS";
    public static final String DOMAIN_DAM_PREFIX = "DAM:";
    public static final String DOMAIN_PIM_PREFIX = "PIM:";

    private final RelationTemplateRepository templates;
    private final RelationTemplateLinkLogRepository linkLogs;
    private final ContentRelationRepository relationRepo;
    private final ContentRelationService relationService;
    private final ContentItemRepository items;
    private final ContentService contentService;
    private final AuditService audit;
    private final ObjectMapper mapper;

    public CrossSourceRelationService(RelationTemplateRepository templates,
                                      RelationTemplateLinkLogRepository linkLogs,
                                      ContentRelationRepository relationRepo,
                                      ContentRelationService relationService,
                                      ContentItemRepository items,
                                      ContentService contentService,
                                      AuditService audit,
                                      ObjectMapper mapper) {
        this.templates = templates;
        this.linkLogs = linkLogs;
        this.relationRepo = relationRepo;
        this.relationService = relationService;
        this.items = items;
        this.contentService = contentService;
        this.audit = audit;
        this.mapper = mapper;
    }

    // ---------- 模板管理 ----------

    public List<Map<String, Object>> listTemplates(UUID siteId) {
        return templates.findBySiteIdOrderByNameAsc(siteId).stream()
            .map(this::templateView).toList();
    }

    @Transactional
    public Map<String, Object> createTemplate(UUID siteId, String name, UUID datasourceId,
                                              String relationName, String anchorField,
                                              String recordField, String operator) {
        requireText(name, "name");
        requireText(relationName, "relationName");
        requireText(anchorField, "anchorField");
        requireText(recordField, "recordField");
        if (templates.existsBySiteIdAndName(siteId, name))
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "模板名称已存在: " + name);
        ContentRelationEntity def = relationRepo
            .findBySiteIdAndRelationName(siteId, relationName)
            .orElseThrow(() -> new BusinessException(ErrorCodes.RELATION_NOT_FOUND,
                "关系定义不存在: " + relationName));
        RelationTemplateEntity t = new RelationTemplateEntity();
        t.setSiteId(siteId);
        t.setName(name.trim());
        t.setDatasourceId(datasourceId);
        t.setRelationName(def.getRelationName());
        t.setAnchorField(anchorField.trim());
        t.setRecordField(recordField.trim());
        t.setStatus(RelationTemplateEntity.STATUS_ACTIVE);
        t.setCreatedBy(operator);
        templates.save(t);
        audit.write(operator, "relation.template.created", "RelationTemplate", t.getId().toString(),
            siteId, "success", "关系模板 " + t.getName() + " → " + relationName);
        return templateView(t);
    }

    @Transactional
    public Map<String, Object> updateTemplateStatus(UUID siteId, UUID id, String status,
                                                    String operator) {
        RelationTemplateEntity t = templates.findById(id)
            .filter(x -> x.getSiteId().equals(siteId))
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "模板不存在"));
        if (!RelationTemplateEntity.STATUS_ACTIVE.equals(status)
            && !RelationTemplateEntity.STATUS_PAUSED.equals(status))
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED,
                "status 必须为 ACTIVE / PAUSED");
        t.setStatus(status);
        templates.save(t);
        audit.write(operator, "relation.template.updated", "RelationTemplate", id.toString(),
            siteId, "success", "模板状态 → " + status);
        return templateView(t);
    }

    @Transactional
    public void deleteTemplate(UUID siteId, UUID id, String operator) {
        RelationTemplateEntity t = templates.findById(id)
            .filter(x -> x.getSiteId().equals(siteId))
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "模板不存在"));
        templates.delete(t);
        audit.write(operator, "relation.template.deleted", "RelationTemplate", id.toString(),
            siteId, "success", "删除关系模板 " + t.getName());
    }

    // ---------- 自动挂链（SRC-001 同步回调） ----------

    /**
     * 数据源同步 upsert 成功后调用：对该数据源的全部 ACTIVE 模板尝试挂链。
     * 单模板失败不阻塞同步（逐模板捕获，记审计）。
     *
     * @param recordContentId 同步创建/更新的内容条目（PIM 侧）
     * @param recordFieldValues 该条目最新版本 fieldValues（含 recordField 的锚点值）
     */
    @Transactional
    public void autoLink(UUID datasourceId, UUID recordContentId, String recordFieldValues) {
        List<RelationTemplateEntity> active =
            templates.findByDatasourceIdAndStatus(datasourceId, RelationTemplateEntity.STATUS_ACTIVE);
        if (active.isEmpty()) return;
        ContentItemEntity record = items.findById(recordContentId).orElse(null);
        if (record == null) return;
        for (RelationTemplateEntity t : active) {
            try {
                linkOne(t, record, recordFieldValues);
            } catch (Exception ex) {
                log.warn("relation template auto-link failed template={} record={} err={}",
                    t.getId(), recordContentId, ex.getMessage());
                audit.write("datasource-sync", "relation.template.link_failed",
                    "RelationTemplate", t.getId().toString(), t.getSiteId(), "failure",
                    "挂链失败 record=" + recordContentId + "：" + ex.getMessage());
            }
        }
    }

    private void linkOne(RelationTemplateEntity t, ContentItemEntity record, String recordFieldValues) {
        String anchorValue = extractField(recordFieldValues, t.getRecordField());
        if (anchorValue == null || anchorValue.isBlank()) return; // 记录未携带锚点值 → 跳过

        // 定位锚点：同站点扫描 anchorField 值匹配的条目（复用 SRC-001 键定位语义，
        // 数据量由站点范围约束；锚点必须在关系定义的目标/源类型内）。
        ContentRelationEntity def = relationRepo
            .findBySiteIdAndRelationName(t.getSiteId(), t.getRelationName())
            .orElseThrow(() -> new BusinessException(ErrorCodes.RELATION_NOT_FOUND,
                "关系定义不存在: " + t.getRelationName()));
        UUID anchorId = findAnchor(t.getSiteId(), t.getAnchorField(), anchorValue, def, record);
        if (anchorId == null || anchorId.equals(record.getId())) return;

        // 去重：同一模板对同一对内容只挂一次（实例幂等 + 审计去重）。
        if (linkLogs.existsByTemplateIdAndRecordContentIdAndAnchorContentId(
                t.getId(), record.getId(), anchorId)) {
            return;
        }
        // addRelation 内部对类型不匹配自动换向、基数校验、实例幂等。
        relationService.addRelation(record.getId(), t.getRelationName(), anchorId, "datasource-sync");
        RelationTemplateLinkLogEntity entry = new RelationTemplateLinkLogEntity();
        entry.setTemplateId(t.getId());
        entry.setDatasourceId(t.getDatasourceId());
        entry.setRecordContentId(record.getId());
        entry.setAnchorContentId(anchorId);
        entry.setRelationId(def.getId());
        linkLogs.save(entry);
        audit.write("datasource-sync", "relation.template.linked", "RelationTemplate",
            t.getId().toString(), t.getSiteId(), "success",
            "自动挂链 record=" + record.getId() + " → anchor=" + anchorId
                + "（" + t.getRelationName() + "）");
    }

    /** 锚点扫描：最新版本 fieldValues 中 anchorField == anchorValue，且类型满足关系定义。 */
    private UUID findAnchor(UUID siteId, String anchorField, String anchorValue,
                            ContentRelationEntity def, ContentItemEntity record) {
        // 记录在关系的哪一端决定锚点应在哪一端。
        boolean recordIsSource = record.getContentTypeId().equals(def.getSourceTypeId());
        boolean recordIsTarget = record.getContentTypeId().equals(def.getTargetTypeId());
        if (!recordIsSource && !recordIsTarget) return null;
        UUID expectedAnchorType = recordIsSource ? def.getTargetTypeId() : def.getSourceTypeId();
        int page = 0;
        var candidates = items.findBySiteId(siteId, PageRequest.of(page, 200));
        while (!candidates.isEmpty()) {
            for (ContentItemEntity c : candidates) {
                if (!expectedAnchorType.equals(c.getContentTypeId())) continue;
                var versions = contentService.versions(c.getId());
                if (versions.isEmpty()) continue;
                String v = extractField(versions.get(0).fieldValues(), anchorField);
                if (anchorValue.equals(v)) return c.getId();
            }
            candidates = items.findBySiteId(siteId, PageRequest.of(++page, 200));
        }
        return null;
    }

    // ---------- 跨源导航（来源域分组） ----------

    /**
     * 产品入口跨源导航：返回内容项的全部关联（复用 FR-CM-RELATION 双向视图），
     * 每个关联条目带 sourceDomain 与 sourceType（CMS/DAM/PIM）分组标注。
     */
    public Map<String, Object> navigation(UUID contentId) {
        ContentItemEntity item = items.findById(contentId)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "内容不存在"));
        List<Map<String, Object>> groups = new ArrayList<>();
        for (Map<String, Object> g : relationService.relations(contentId)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> group = (Map<String, Object>) g;
            enrichGroup(group, "outgoing");
            enrichGroup(group, "incoming");
            groups.add(group);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", item.getId());
        result.put("title", item.getTitle());
        result.put("sourceDomain", item.getSourceDomain());
        result.put("relations", groups);
        return result;
    }

    @SuppressWarnings("unchecked")
    private void enrichGroup(Map<String, Object> group, String direction) {
        Object val = group.get(direction);
        if (!(val instanceof List<?> list)) return;
        List<Map<String, Object>> enriched = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> raw)) continue;
            Map<String, Object> view = (Map<String, Object>) raw;
            Object id = view.get("id");
            if (id != null) {
                items.findById((UUID) id).ifPresent(c -> {
                    view.put("sourceDomain", c.getSourceDomain());
                    view.put("sourceType", sourceType(c.getSourceDomain()));
                });
            }
            enriched.add(view);
        }
        group.put(direction, enriched);
    }

    /** 来源域 → 分组类型（CMS / DAM / PIM）。 */
    public static String sourceType(String sourceDomain) {
        if (sourceDomain == null) return DOMAIN_CMS;
        if (sourceDomain.startsWith(DOMAIN_DAM_PREFIX)) return "DAM";
        if (sourceDomain.startsWith(DOMAIN_PIM_PREFIX)) return "PIM";
        return DOMAIN_CMS;
    }

    private Map<String, Object> templateView(RelationTemplateEntity t) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", t.getId());
        v.put("name", t.getName());
        v.put("datasourceId", t.getDatasourceId());
        v.put("relationName", t.getRelationName());
        v.put("anchorField", t.getAnchorField());
        v.put("recordField", t.getRecordField());
        v.put("status", t.getStatus());
        v.put("createdAt", t.getCreatedAt());
        return v;
    }

    private String extractField(String fieldValuesJson, String fieldCode) {
        if (fieldValuesJson == null || fieldValuesJson.isBlank()) return null;
        try {
            JsonNode v = mapper.readTree(fieldValuesJson).get(fieldCode);
            if (v == null || v.isNull()) return null;
            String s = v.asText();
            return s.isBlank() ? null : s;
        } catch (Exception ex) {
            return null;
        }
    }

    private void requireText(String v, String field) {
        if (v == null || v.isBlank())
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, field + " 不能为空");
    }
}
