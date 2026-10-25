package com.powercms.cms.delivery.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.content.repo.ContentVersionRepository;
import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.repo.ContentTypeRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SiteScopedContentQueryServiceTest {
  @Test
  void neverReturnsOtherSiteItems() {
    UUID requested = UUID.randomUUID(), other = UUID.randomUUID();
    ContentItemEntity item = new ContentItemEntity();
    item.setId(UUID.randomUUID());
    item.setSiteId(other);
    item.setStatus("PUBLISHED");
    item.setCurrentVersion(1);
    item.setPublishedVersion(1);
    ContentItemRepository repo = mock(ContentItemRepository.class);
    when(repo.findBySiteIdAndStatus(requested, "PUBLISHED")).thenReturn(List.of(item));
    var service =
        new SiteScopedContentQueryService(
            repo,
            mock(ContentVersionRepository.class),
            mock(ContentTypeRepository.class),
            new ObjectMapper());
    assertThrows(RuntimeException.class, () -> service.query(requested, "PUBLISHED", 10));
    verify(repo).findBySiteIdAndStatus(requested, "PUBLISHED");
  }

  @Test
  void queryByTypeOrdersByViewCountDesc() {
    UUID siteId = UUID.randomUUID();
    UUID typeId = UUID.randomUUID();
    ContentTypeEntity type = new ContentTypeEntity();
    type.setId(typeId);
    type.setSiteId(siteId);
    type.setCode("travel_destination");

    ContentItemEntity low = item(siteId, typeId, "Low", 10);
    ContentItemEntity high = item(siteId, typeId, "High", 500);
    ContentItemEntity mid = item(siteId, typeId, "Mid", 120);

    ContentItemRepository repo = mock(ContentItemRepository.class);
    when(repo.findByContentTypeId(typeId)).thenReturn(List.of(low, high, mid));

    ContentTypeRepository types = mock(ContentTypeRepository.class);
    when(types.findBySiteIdAndCodeOrderByVersionAsc(siteId, "travel_destination"))
        .thenReturn(List.of(type));

    var service =
        new SiteScopedContentQueryService(
            repo, mock(ContentVersionRepository.class), types, new ObjectMapper());

    List<Map<String, Object>> rows =
        service.queryByType(siteId, "travel_destination", "PUBLISHED", 2, "viewCount", true);

    assertEquals(2, rows.size());
    assertEquals("High", rows.get(0).get("title"));
    assertEquals(500L, rows.get(0).get("viewCount"));
    assertEquals("Mid", rows.get(1).get("title"));
  }

  @Test
  void deliveryPayloadCarriesImplicitAiMarker() {
    UUID siteId = UUID.randomUUID();
    ContentItemEntity ai = item(siteId, UUID.randomUUID(), "AI 生成文章", 5);
    ai.setAiGenerated(true);
    ai.setAiModel("gpt-4o");
    ContentItemEntity plain = item(siteId, UUID.randomUUID(), "人工写作", 3);

    ContentItemRepository repo = mock(ContentItemRepository.class);
    when(repo.findBySiteIdAndStatus(siteId, "PUBLISHED")).thenReturn(List.of(ai, plain));

    var service =
        new SiteScopedContentQueryService(
            repo, mock(ContentVersionRepository.class), mock(ContentTypeRepository.class),
            new ObjectMapper());

    List<Map<String, Object>> rows = service.query(siteId, "PUBLISHED", 10);
    Map<String, Object> aiRow = rows.stream()
        .filter(r -> r.get("aiGenerated").equals(Boolean.TRUE)).findFirst().orElseThrow();
    assertEquals("AI 生成文章", aiRow.get("title"));
    Map<String, Object> plainRow = rows.stream()
        .filter(r -> r.get("title").equals("人工写作")).findFirst().orElseThrow();
    assertEquals(Boolean.FALSE, plainRow.get("aiGenerated"));
  }

  private static ContentItemEntity item(UUID siteId, UUID typeId, String title, long views) {
    ContentItemEntity item = new ContentItemEntity();
    item.setId(UUID.randomUUID());
    item.setSiteId(siteId);
    item.setContentTypeId(typeId);
    item.setTitle(title);
    item.setStatus(ContentItemEntity.STATUS_PUBLISHED);
    item.setCurrentVersion(1);
    item.setPublishedVersion(1);
    item.setViewCount(views);
    return item;
  }
}
