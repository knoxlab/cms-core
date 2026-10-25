package com.powercms.cms.content.relation;

import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.common.error.ApiError;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 内容关系系统（FR-CM-RELATION-001~014）：双向内容关联的定义与实例管理。 */
@Service
@Transactional
public class ContentRelationService {
  private static final Set<String> CARDINALITIES =
      Set.of("ONE_TO_ONE", "ONE_TO_MANY", "MANY_TO_ONE", "MANY_TO_MANY");

  private final ContentRelationRepository relationRepo;
  private final ContentRelationItemRepository itemRepo;
  private final ContentTypeRepository ctRepo;
  private final ContentItemRepository contentRepo;
  /** DEF-AI-07：关系实例/定义变更后发布事件，由 kg 模块增量同步图谱边（避免 content → kg 依赖）。 */
  private final ApplicationEventPublisher events;

  ContentRelationService(
      ContentRelationRepository r,
      ContentRelationItemRepository i,
      ContentTypeRepository c,
      ContentItemRepository content,
      ApplicationEventPublisher events) {
    relationRepo = r;
    itemRepo = i;
    ctRepo = c;
    contentRepo = content;
    this.events = events;
  }

  public record RelationDef(
      UUID id,
      UUID siteId,
      String relationName,
      String cardinality,
      UUID sourceTypeId,
      UUID targetTypeId,
      String description,
      String createdBy) {
    public static RelationDef from(ContentRelationEntity e) {
      return new RelationDef(
          e.getId(), e.getSiteId(), e.getRelationName(), e.getCardinality(),
          e.getSourceTypeId(), e.getTargetTypeId(), e.getDescription(), e.getCreatedBy());
    }
  }

  public List<RelationDef> listDefinitions(UUID siteId) {
    return relationRepo.findBySiteIdOrderByRelationNameAsc(siteId).stream()
        .map(RelationDef::from)
        .toList();
  }

  public RelationDef createDefinition(
      UUID siteId, String relationName, String cardinality, UUID sourceTypeId, UUID targetTypeId,
      String description, String operator) {
    if (relationName == null || relationName.isBlank())
      throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "关系名称不能为空");
    String normalized = normalize(cardinality);
    if (!CARDINALITIES.contains(normalized))
      throw new BusinessException(
          ErrorCodes.RELATION_CARDINALITY_INVALID,
          "基数必须是 ONE_TO_ONE / ONE_TO_MANY / MANY_TO_ONE / MANY_TO_MANY",
          List.of(new ApiError.FieldError("cardinality", cardinality)));
    requireType(siteId, sourceTypeId, "sourceTypeId");
    requireType(siteId, targetTypeId, "targetTypeId");
    if (relationRepo.existsBySiteIdAndRelationName(siteId, relationName))
      throw new BusinessException(ErrorCodes.RELATION_NAME_DUPLICATE, "关系名称已存在");
    ContentRelationEntity e = new ContentRelationEntity();
    e.setSiteId(siteId);
    e.setRelationName(relationName);
    e.setCardinality(normalized);
    e.setSourceTypeId(sourceTypeId);
    e.setTargetTypeId(targetTypeId);
    e.setDescription(description);
    e.setCreatedBy(operator);
    return RelationDef.from(relationRepo.save(e));
  }

  public void deleteDefinition(UUID siteId, UUID id) {
    ContentRelationEntity e = require(siteId, id);
    if (itemRepo.existsByRelationId(id))
      throw new BusinessException(ErrorCodes.RELATION_HAS_INSTANCES, "关系存在关联实例，无法删除");
    relationRepo.delete(e);
    // DEF-AI-07：定义行删掉后其边会成为 relation_id 悬空边（重建只遍历现存定义，永不清），故显式通知清边
    events.publishEvent(new ContentRelationChangedEvent(siteId, id, true));
  }

  /** 返回内容项的全部关联（按关系名分组，双向）。FR-CM-RELATION-013。 */
  public List<Map<String, Object>> relations(UUID contentId) {
    ContentItemEntity content =
        contentRepo
            .findById(contentId)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "内容不存在"));
    List<ContentRelationEntity> defs =
        relationRepo.findBySiteIdOrderByRelationNameAsc(content.getSiteId());
    Map<String, List<Map<String, Object>>> outgoing = new LinkedHashMap<>();
    Map<String, List<Map<String, Object>>> incoming = new LinkedHashMap<>();
    for (ContentRelationEntity def : defs) {
      for (ContentRelationItemEntity item :
          itemRepo.findByRelationIdAndSourceContentIdOrderBySortOrderAsc(def.getId(), contentId)) {
        outgoing
            .computeIfAbsent(def.getRelationName(), k -> new ArrayList<>())
            .add(itemView(item.getTargetContentId()));
      }
      for (ContentRelationItemEntity item :
          itemRepo.findByRelationIdAndTargetContentIdOrderBySortOrderAsc(def.getId(), contentId)) {
        incoming
            .computeIfAbsent(def.getRelationName(), k -> new ArrayList<>())
            .add(itemView(item.getSourceContentId()));
      }
    }
    List<Map<String, Object>> result = new ArrayList<>();
    Set<String> names = new LinkedHashSet<>();
    names.addAll(outgoing.keySet());
    names.addAll(incoming.keySet());
    for (String name : names) {
      ContentRelationEntity def = defs.stream().filter(d -> name.equals(d.getRelationName())).findFirst().orElse(null);
      Map<String, Object> group = new LinkedHashMap<>();
      group.put("relationName", name);
      group.put("cardinality", def == null ? null : def.getCardinality());
      group.put("outgoing", outgoing.getOrDefault(name, List.of()));
      group.put("incoming", incoming.getOrDefault(name, List.of()));
      result.add(group);
    }
    return result;
  }

  public Map<String, Object> addRelation(
      UUID contentId, String relationName, UUID targetId, String operator) {
    ContentItemEntity source =
        contentRepo
            .findById(contentId)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "内容不存在"));
    ContentItemEntity target =
        contentRepo
            .findById(targetId)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "目标内容不存在"));
    ContentRelationEntity def =
        relationRepo
            .findBySiteIdAndRelationName(source.getSiteId(), relationName)
            .orElseThrow(() -> new BusinessException(ErrorCodes.RELATION_NOT_FOUND, "关系定义不存在"));

    UUID sId = source.getId(), tId = target.getId();
    if (!source.getContentTypeId().equals(def.getSourceTypeId())
        || !target.getContentTypeId().equals(def.getTargetTypeId())) {
      if (source.getContentTypeId().equals(def.getTargetTypeId())
          && target.getContentTypeId().equals(def.getSourceTypeId())) {
        sId = target.getId();
        tId = source.getId();
      } else {
        throw new BusinessException(
            ErrorCodes.RELATION_TYPE_INVALID, "内容类型与关系定义不匹配");
      }
    }
    enforceCardinality(def, sId, tId);
    var existing =
        itemRepo.findByRelationIdAndSourceContentIdAndTargetContentId(def.getId(), sId, tId);
    if (existing.isPresent()) return itemView(target.getId());
    ContentRelationItemEntity item = new ContentRelationItemEntity();
    item.setRelationId(def.getId());
    item.setSourceContentId(sId);
    item.setTargetContentId(tId);
    item.setSortOrder(
        itemRepo.findByRelationIdAndSourceContentIdOrderBySortOrderAsc(def.getId(), sId).size() + 1);
    item.setCreatedBy(operator);
    itemRepo.save(item);
    // DEF-AI-07：新实例立即投影到图（原先只在内容事件/重建时入图）
    events.publishEvent(new ContentRelationChangedEvent(def.getSiteId(), def.getId(), false));
    return itemView(target.getId());
  }

  public void removeRelation(UUID contentId, String relationName, UUID targetId) {
    ContentItemEntity source =
        contentRepo
            .findById(contentId)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "内容不存在"));
    ContentRelationEntity def =
        relationRepo
            .findBySiteIdAndRelationName(source.getSiteId(), relationName)
            .orElseThrow(() -> new BusinessException(ErrorCodes.RELATION_NOT_FOUND, "关系定义不存在"));
    itemRepo
        .findByRelationIdAndSourceContentIdAndTargetContentId(def.getId(), contentId, targetId)
        .ifPresent(itemRepo::delete);
    itemRepo
        .findByRelationIdAndSourceContentIdAndTargetContentId(def.getId(), targetId, contentId)
        .ifPresent(itemRepo::delete);
    // DEF-AI-07：删实例后按定义重算边——不清理的话 kg_edge 会留下已删关联的陈旧边
    events.publishEvent(new ContentRelationChangedEvent(def.getSiteId(), def.getId(), false));
  }

  /** 关系方向（相对**被查询锚点**）：锚点指向关联内容。 */
  public static final String DIRECTION_OUTGOING = "outgoing";

  /** 关系方向（相对**被查询锚点**）：关联内容指向锚点。 */
  public static final String DIRECTION_INCOMING = "incoming";

  /** 关系方向（相对**被查询锚点**）：两个方向的连通同时存在。 */
  public static final String DIRECTION_BOTH = "both";

  /**
   * 按关系字段过滤（FR-CM-RELATION-012）：输入关联内容的标题或 ID，返回与之存在关系的所有内容。
   *
   * <p><b>DEF-SEARCH-03</b>：此前每条结果只有 {@code {id, title}}，**方向与关系类型信息丢失**，与
   * TC-M60-021「列出所有与之相关的内容（含方向/关系类型信息）」不符。现补齐：
   * <ul>
   *   <li>{@code relations}：命中的关系路径明细（{@code relationName} / {@code cardinality} /
   *       {@code direction}）；同一对内容经多条关系连通时逐条列出（按关系名去重）；</li>
   *   <li>{@code direction}：**聚合方向**——相对被查询锚点而言，{@code outgoing}=锚点指向该内容、
   *       {@code incoming}=该内容指向锚点、{@code both}=两向皆有（多锚点/多路径时可能出现）。</li>
   * </ul>
   *
   * <p>方向与关系名的口径与 {@link #relations(UUID)}（FR-CM-RELATION-013）保持一致，避免同一份图数据在
   * 两个入口给出互相矛盾的方向语义。
   */
  public List<Map<String, Object>> searchByRelated(UUID siteId, String query) {
    if (query == null || query.isBlank()) return List.of();
    String needle = query.trim();
    String lower = needle.toLowerCase(Locale.ROOT);
    List<ContentItemEntity> anchors =
        contentRepo.findBySiteId(siteId).stream()
            .filter(
                i ->
                    i.getId().toString().equals(needle)
                        || (i.getTitle() != null && i.getTitle().toLowerCase(Locale.ROOT).contains(lower)))
            .toList();
    if (anchors.isEmpty()) return List.of();
    List<UUID> anchorIds = anchors.stream().map(ContentItemEntity::getId).toList();
    Set<UUID> anchorSet = new LinkedHashSet<>(anchorIds);

    // 关系定义索引：关系 id → 定义（回填关系名与基数；定义缺失时降级为 null 而非丢结果）
    Map<UUID, ContentRelationEntity> defsById = new LinkedHashMap<>();
    for (ContentRelationEntity def : relationRepo.findBySiteIdOrderByRelationNameAsc(siteId)) {
      defsById.put(def.getId(), def);
    }

    // 关联内容 id → 关系路径明细（保序去重；锚点自身不作为结果）
    Map<UUID, List<Map<String, Object>>> links = new LinkedHashMap<>();
    for (ContentRelationItemEntity item : itemRepo.findBySourceContentIdIn(anchorIds)) {
      if (anchorSet.contains(item.getTargetContentId())) continue;
      addLink(links, item.getTargetContentId(), defsById.get(item.getRelationId()),
          DIRECTION_OUTGOING);
    }
    for (ContentRelationItemEntity item : itemRepo.findByTargetContentIdIn(anchorIds)) {
      if (anchorSet.contains(item.getSourceContentId())) continue;
      addLink(links, item.getSourceContentId(), defsById.get(item.getRelationId()),
          DIRECTION_INCOMING);
    }

    List<Map<String, Object>> result = new ArrayList<>();
    for (Map.Entry<UUID, List<Map<String, Object>>> entry : links.entrySet()) {
      if (contentRepo.findById(entry.getKey()).isEmpty()) continue;
      List<Map<String, Object>> rels = entry.getValue();
      Map<String, Object> view = itemView(entry.getKey());
      view.put("relations", rels);
      boolean outgoing =
          rels.stream().anyMatch(r -> DIRECTION_OUTGOING.equals(r.get("direction")));
      boolean incoming =
          rels.stream().anyMatch(r -> DIRECTION_INCOMING.equals(r.get("direction")));
      view.put(
          "direction",
          outgoing && incoming ? DIRECTION_BOTH : (outgoing ? DIRECTION_OUTGOING : DIRECTION_INCOMING));
      result.add(view);
    }
    return result;
  }

  /** 累积一条关系路径；同一 (关系名, 方向) 只记一次，避免多锚点造成重复行。 */
  private void addLink(
      Map<UUID, List<Map<String, Object>>> links,
      UUID relatedContentId,
      ContentRelationEntity def,
      String direction) {
    if (relatedContentId == null) return;
    String name = def == null ? null : def.getRelationName();
    List<Map<String, Object>> entries = links.computeIfAbsent(relatedContentId, k -> new ArrayList<>());
    boolean duplicated =
        entries.stream()
            .anyMatch(
                e ->
                    Objects.equals(e.get("relationName"), name)
                        && Objects.equals(e.get("direction"), direction));
    if (duplicated) return;
    Map<String, Object> link = new LinkedHashMap<>();
    link.put("relationName", name);
    link.put("cardinality", def == null ? null : def.getCardinality());
    link.put("direction", direction);
    entries.add(link);
  }

  private void enforceCardinality(ContentRelationEntity def, UUID sId, UUID tId) {
    boolean sourceBusy =
        !itemRepo.findByRelationIdAndSourceContentIdOrderBySortOrderAsc(def.getId(), sId).isEmpty();
    boolean targetBusy =
        !itemRepo.findByRelationIdAndTargetContentIdOrderBySortOrderAsc(def.getId(), tId).isEmpty();
    switch (def.getCardinality()) {
      case "ONE_TO_ONE" -> {
        if (sourceBusy || targetBusy)
          throw new BusinessException(ErrorCodes.RELATION_CARDINALITY_INVALID, "一对一关系已存在关联");
      }
      case "ONE_TO_MANY" -> {
        if (targetBusy)
          throw new BusinessException(ErrorCodes.RELATION_CARDINALITY_INVALID, "目标内容已被关联");
      }
      case "MANY_TO_ONE" -> {
        if (sourceBusy)
          throw new BusinessException(ErrorCodes.RELATION_CARDINALITY_INVALID, "源内容已存在关联");
      }
      default -> {}
    }
  }

  private Map<String, Object> itemView(UUID contentId) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", contentId);
    contentRepo
        .findById(contentId)
        .ifPresent(c -> view.put("title", c.getTitle()));
    return view;
  }

  private ContentRelationEntity require(UUID siteId, UUID id) {
    ContentRelationEntity e =
        relationRepo
            .findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCodes.RELATION_NOT_FOUND, "关系定义不存在"));
    if (!e.getSiteId().equals(siteId))
      throw new BusinessException(ErrorCodes.RELATION_NOT_FOUND, "关系定义不存在");
    return e;
  }

  private void requireType(UUID siteId, UUID typeId, String field) {
    ctRepo
        .findById(typeId)
        .filter(t -> t.getSiteId().equals(siteId))
        .orElseThrow(
            () ->
                new BusinessException(
                    ErrorCodes.RELATION_TYPE_INVALID,
                    "关系类型不存在或不属于当前站点",
                    List.of(new ApiError.FieldError(field, String.valueOf(typeId)))));
  }

  private static String normalize(String v) {
    return v == null ? "" : v.trim().toUpperCase(Locale.ROOT);
  }
}
