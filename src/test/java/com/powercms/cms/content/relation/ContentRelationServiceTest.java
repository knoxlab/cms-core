package com.powercms.cms.content.relation;

import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.common.error.BusinessException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ContentRelationServiceTest {
  private final ContentRelationRepository relations = mock(ContentRelationRepository.class);
  private final ContentRelationItemRepository items = mock(ContentRelationItemRepository.class);
  private final ContentTypeRepository types = mock(ContentTypeRepository.class);
  private final ContentItemRepository contents = mock(ContentItemRepository.class);
  private final org.springframework.context.ApplicationEventPublisher events =
      mock(org.springframework.context.ApplicationEventPublisher.class);
  private final ContentRelationService service =
      new ContentRelationService(relations, items, types, contents, events);

  @Test
  void createDefinitionRejectsDuplicateName() {
    UUID siteId = UUID.randomUUID();
    UUID sourceType = UUID.randomUUID();
    UUID targetType = UUID.randomUUID();
    ContentTypeEntity source = new ContentTypeEntity();
    source.setId(sourceType);
    source.setSiteId(siteId);
    ContentTypeEntity target = new ContentTypeEntity();
    target.setId(targetType);
    target.setSiteId(siteId);
    when(types.findById(sourceType)).thenReturn(Optional.of(source));
    when(types.findById(targetType)).thenReturn(Optional.of(target));
    when(relations.existsBySiteIdAndRelationName(siteId, "article_author")).thenReturn(true);

    assertThatThrownBy(
            () ->
                service.createDefinition(
                    siteId, "article_author", "MANY_TO_MANY", sourceType, targetType, null, "admin"))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getCode())
        .isEqualTo("RELATION_NAME_DUPLICATE");
  }

  @Test
  void createDefinitionRejectsInvalidCardinality() {
    UUID siteId = UUID.randomUUID();
    UUID sourceType = UUID.randomUUID();
    UUID targetType = UUID.randomUUID();
    ContentTypeEntity source = new ContentTypeEntity();
    source.setId(sourceType);
    source.setSiteId(siteId);
    ContentTypeEntity target = new ContentTypeEntity();
    target.setId(targetType);
    target.setSiteId(siteId);
    when(types.findById(sourceType)).thenReturn(Optional.of(source));
    when(types.findById(targetType)).thenReturn(Optional.of(target));

    assertThatThrownBy(
            () ->
                service.createDefinition(
                    siteId, "r", "INVALID", sourceType, targetType, null, "admin"))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getCode())
        .isEqualTo("RELATION_CARDINALITY_INVALID");
  }

  @Test
  void addRelationEnforcesOneToOneCardinality() {
    UUID siteId = UUID.randomUUID();
    UUID defId = UUID.randomUUID();
    UUID sourceContent = UUID.randomUUID();
    UUID targetContent = UUID.randomUUID();
    UUID sourceType = UUID.randomUUID();
    UUID targetType = UUID.randomUUID();

    ContentItemEntity source = item(sourceContent, siteId, sourceType);
    ContentItemEntity target = item(targetContent, siteId, targetType);
    when(contents.findById(sourceContent)).thenReturn(Optional.of(source));
    when(contents.findById(targetContent)).thenReturn(Optional.of(target));

    ContentRelationEntity def = new ContentRelationEntity();
    def.setId(defId);
    def.setSiteId(siteId);
    def.setRelationName("one_to_one");
    def.setCardinality("ONE_TO_ONE");
    def.setSourceTypeId(sourceType);
    def.setTargetTypeId(targetType);
    when(relations.findBySiteIdAndRelationName(siteId, "one_to_one")).thenReturn(Optional.of(def));

    ContentRelationItemEntity existing = new ContentRelationItemEntity();
    existing.setId(UUID.randomUUID());
    when(items.findByRelationIdAndSourceContentIdOrderBySortOrderAsc(defId, sourceContent))
        .thenReturn(List.of(existing));

    assertThatThrownBy(
            () -> service.addRelation(sourceContent, "one_to_one", targetContent, "admin"))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getCode())
        .isEqualTo("RELATION_CARDINALITY_INVALID");
  }

  @Test
  void relationsReturnsBothDirections() {
    UUID siteId = UUID.randomUUID();
    UUID defId = UUID.randomUUID();
    UUID contentId = UUID.randomUUID();
    UUID targetId = UUID.randomUUID();
    UUID sourceId = UUID.randomUUID();

    ContentItemEntity content = item(contentId, siteId, UUID.randomUUID());
    when(contents.findById(contentId)).thenReturn(Optional.of(content));

    ContentRelationEntity def = new ContentRelationEntity();
    def.setId(defId);
    def.setSiteId(siteId);
    def.setRelationName("article_author");
    def.setCardinality("MANY_TO_MANY");
    when(relations.findBySiteIdOrderByRelationNameAsc(siteId)).thenReturn(List.of(def));

    ContentRelationItemEntity outgoing = new ContentRelationItemEntity();
    outgoing.setTargetContentId(targetId);
    when(items.findByRelationIdAndSourceContentIdOrderBySortOrderAsc(defId, contentId))
        .thenReturn(List.of(outgoing));

    ContentRelationItemEntity incoming = new ContentRelationItemEntity();
    incoming.setSourceContentId(sourceId);
    when(items.findByRelationIdAndTargetContentIdOrderBySortOrderAsc(defId, contentId))
        .thenReturn(List.of(incoming));

    ContentItemEntity targetEntity = item(targetId, siteId, UUID.randomUUID());
    targetEntity.setTitle("Author");
    when(contents.findById(targetId)).thenReturn(Optional.of(targetEntity));
    ContentItemEntity sourceEntity = item(sourceId, siteId, UUID.randomUUID());
    sourceEntity.setTitle("Article");
    when(contents.findById(sourceId)).thenReturn(Optional.of(sourceEntity));

    var result = service.relations(contentId);
    assertThat(result).singleElement().satisfies(group -> {
      assertThat(group.get("relationName")).isEqualTo("article_author");
      assertThat((List<?>) group.get("outgoing")).hasSize(1);
      assertThat((List<?>) group.get("incoming")).hasSize(1);
    });
  }

  @Test
  void searchByRelatedReturnsOtherSide() {
    UUID siteId = UUID.randomUUID();
    UUID contentId = UUID.randomUUID();
    UUID targetId = UUID.randomUUID();
    UUID defId = UUID.randomUUID();

    ContentItemEntity content = item(contentId, siteId, UUID.randomUUID());
    content.setTitle("文章A");
    when(contents.findBySiteId(siteId)).thenReturn(List.of(content));

    ContentRelationItemEntity item = new ContentRelationItemEntity();
    item.setRelationId(defId);
    item.setSourceContentId(contentId);
    item.setTargetContentId(targetId);
    when(items.findBySourceContentIdIn(List.of(contentId))).thenReturn(List.of(item));
    when(items.findByTargetContentIdIn(List.of(contentId))).thenReturn(List.of());

    ContentItemEntity target = item(targetId, siteId, UUID.randomUUID());
    target.setTitle("作者B");
    when(contents.findById(targetId)).thenReturn(Optional.of(target));

    var result = service.searchByRelated(siteId, "文章A");
    assertThat(result).singleElement().satisfies(m -> {
      assertThat(m.get("id")).isEqualTo(targetId);
      assertThat(m.get("title")).isEqualTo("作者B");
    });
  }

  private static ContentItemEntity item(UUID id, UUID siteId, UUID typeId) {
    ContentItemEntity e = new ContentItemEntity();
    e.setId(id);
    e.setSiteId(siteId);
    e.setContentTypeId(typeId);
    return e;
  }

  /**
   * DEF-AI-07：关系实例/定义的变更必须通知图谱——此前关系服务完全不感知图，
   * 删关联后 {@code kg_edge} 残留陈旧边，且「全站对账重建」也清不掉（边只按现存定义遍历）。
   *
   * <p>通知方式为领域事件（content 模块不反向依赖 kg 模块，模块边界由 ModularityTests 校验）。
   */
  @Test
  void addRelationPublishesGraphSyncEvent() {
    UUID siteId = UUID.randomUUID();
    UUID typeId = UUID.randomUUID();
    UUID sourceId = UUID.randomUUID();
    UUID targetId = UUID.randomUUID();
    ContentRelationEntity def = new ContentRelationEntity();
    def.setId(UUID.randomUUID());
    def.setSiteId(siteId);
    def.setRelationName("rel");
    def.setCardinality("MANY_TO_MANY");
    def.setSourceTypeId(typeId);
    def.setTargetTypeId(typeId);
    when(relations.findBySiteIdAndRelationName(siteId, "rel")).thenReturn(Optional.of(def));
    when(contents.findById(sourceId)).thenReturn(Optional.of(item(sourceId, siteId, typeId)));
    when(contents.findById(targetId)).thenReturn(Optional.of(item(targetId, siteId, typeId)));
    when(items.findByRelationIdAndSourceContentIdAndTargetContentId(def.getId(), sourceId, targetId))
        .thenReturn(Optional.empty());
    when(items.findByRelationIdAndSourceContentIdOrderBySortOrderAsc(def.getId(), sourceId))
        .thenReturn(List.of());

    service.addRelation(sourceId, "rel", targetId, "admin");

    verify(events).publishEvent(new ContentRelationChangedEvent(siteId, def.getId(), false));
  }

  @Test
  void removeRelationPublishesGraphSyncEvent() {
    UUID siteId = UUID.randomUUID();
    UUID typeId = UUID.randomUUID();
    UUID sourceId = UUID.randomUUID();
    UUID targetId = UUID.randomUUID();
    ContentRelationEntity def = new ContentRelationEntity();
    def.setId(UUID.randomUUID());
    def.setSiteId(siteId);
    def.setRelationName("rel");
    when(relations.findBySiteIdAndRelationName(siteId, "rel")).thenReturn(Optional.of(def));
    when(contents.findById(sourceId)).thenReturn(Optional.of(item(sourceId, siteId, typeId)));
    when(items.findByRelationIdAndSourceContentIdAndTargetContentId(def.getId(), sourceId, targetId))
        .thenReturn(Optional.empty());

    service.removeRelation(sourceId, "rel", targetId);

    verify(events).publishEvent(new ContentRelationChangedEvent(siteId, def.getId(), false));
  }

  @Test
  void deleteDefinitionPublishesDefinitionRemovedEvent() {
    UUID siteId = UUID.randomUUID();
    UUID defId = UUID.randomUUID();
    ContentRelationEntity def = new ContentRelationEntity();
    def.setId(defId);
    def.setSiteId(siteId);
    when(relations.findById(defId)).thenReturn(Optional.of(def));
    when(items.existsByRelationId(defId)).thenReturn(false);

    service.deleteDefinition(siteId, defId);

    verify(relations).delete(def);
    // 定义删除后 relation_id 会悬空，必须通知图谱清边
    verify(events).publishEvent(new ContentRelationChangedEvent(siteId, defId, true));
  }

  /**
   * DEF-SEARCH-03：结果必须携带**关系类型与方向**（此前只有 {@code {id, title}}，方向/关系类型丢失，
   * 与 TC-M60-021「列出所有与之相关的内容（含方向/关系类型信息）」不符）。
   *
   * <p>本用例覆盖三种方向：锚点为 source → {@code outgoing}；锚点为 target → {@code incoming}；
   * 同一内容两向皆有 → {@code both}。
   */
  @Test
  void searchByRelatedCarriesRelationTypeAndDirection() {
    UUID siteId = UUID.randomUUID();
    UUID anchorId = UUID.randomUUID();
    UUID outId = UUID.randomUUID();
    UUID inId = UUID.randomUUID();
    UUID bothId = UUID.randomUUID();
    UUID outDefId = UUID.randomUUID();
    UUID inDefId = UUID.randomUUID();

    ContentItemEntity anchor = item(anchorId, siteId, UUID.randomUUID());
    anchor.setTitle("云平台");
    when(contents.findBySiteId(siteId)).thenReturn(List.of(anchor));

    ContentRelationEntity outDef = new ContentRelationEntity();
    outDef.setId(outDefId);
    outDef.setSiteId(siteId);
    outDef.setRelationName("references");
    outDef.setCardinality("MANY_TO_MANY");
    ContentRelationEntity inDef = new ContentRelationEntity();
    inDef.setId(inDefId);
    inDef.setSiteId(siteId);
    inDef.setRelationName("authored_by");
    inDef.setCardinality("ONE_TO_MANY");
    when(relations.findBySiteIdOrderByRelationNameAsc(siteId)).thenReturn(List.of(outDef, inDef));

    // 出向：锚点 → outId / bothId
    when(items.findBySourceContentIdIn(List.of(anchorId)))
        .thenReturn(List.of(relItem(outDefId, anchorId, outId), relItem(outDefId, anchorId, bothId)));
    // 入向：inId / bothId → 锚点
    when(items.findByTargetContentIdIn(List.of(anchorId)))
        .thenReturn(List.of(relItem(inDefId, inId, anchorId), relItem(inDefId, bothId, anchorId)));

    for (UUID id : List.of(outId, inId, bothId)) {
      when(contents.findById(id))
          .thenReturn(Optional.of(item(id, siteId, UUID.randomUUID())));
    }

    var result = service.searchByRelated(siteId, "云平台");
    assertThat(result).hasSize(3);

    java.util.Map<UUID, java.util.Map<String, Object>> byId = new java.util.LinkedHashMap<>();
    for (java.util.Map<String, Object> row : result) byId.put((UUID) row.get("id"), row);

    // 出向：锚点指向该内容
    assertThat(byId.get(outId).get("direction")).isEqualTo(ContentRelationService.DIRECTION_OUTGOING);
    List<?> outLinks = (List<?>) byId.get(outId).get("relations");
    assertThat(outLinks).hasSize(1);
    assertThat(link(outLinks, 0))
        .containsEntry("relationName", "references")
        .containsEntry("cardinality", "MANY_TO_MANY")
        .containsEntry("direction", ContentRelationService.DIRECTION_OUTGOING);

    // 入向：该内容指向锚点
    assertThat(byId.get(inId).get("direction")).isEqualTo(ContentRelationService.DIRECTION_INCOMING);
    List<?> inLinks = (List<?>) byId.get(inId).get("relations");
    assertThat(inLinks).hasSize(1);
    assertThat(link(inLinks, 0))
        .containsEntry("relationName", "authored_by")
        .containsEntry("cardinality", "ONE_TO_MANY")
        .containsEntry("direction", ContentRelationService.DIRECTION_INCOMING);

    // 双向：两个方向都存在时聚合为 both，且两条路径都列出
    assertThat(byId.get(bothId).get("direction")).isEqualTo(ContentRelationService.DIRECTION_BOTH);
    assertThat((List<?>) byId.get(bothId).get("relations")).hasSize(2);
  }

  @Test
  void searchByRelatedReturnsEmptyForBlankQuery() {
    UUID siteId = UUID.randomUUID();
    assertThat(service.searchByRelated(siteId, "   ")).isEmpty();
    assertThat(service.searchByRelated(siteId, null)).isEmpty();
    verifyNoInteractions(contents);
  }

  @Test
  void searchByRelatedReturnsEmptyWhenNoAnchorMatches() {
    UUID siteId = UUID.randomUUID();
    when(contents.findBySiteId(siteId)).thenReturn(List.of(item(UUID.randomUUID(), siteId, UUID.randomUUID())));
    assertThat(service.searchByRelated(siteId, "不存在的标题")).isEmpty();
  }

  private static ContentRelationItemEntity relItem(UUID defId, UUID source, UUID target) {
    ContentRelationItemEntity e = new ContentRelationItemEntity();
    e.setId(UUID.randomUUID());
    e.setRelationId(defId);
    e.setSourceContentId(source);
    e.setTargetContentId(target);
    return e;
  }

  @SuppressWarnings("unchecked")
  private static java.util.Map<String, Object> link(List<?> links, int index) {
    return (java.util.Map<String, Object>) links.get(index);
  }
}
