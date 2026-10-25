package com.powercms.cms.delivery.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.content.repo.ContentVersionRepository;
import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;

/** Only content read facade exposed to declarative controllers; every lookup is forcibly site scoped. */
@Service
public class SiteScopedContentQueryService {
  private static final int MAX_LIMIT = 50;

  private final ContentItemRepository items;
  private final ContentVersionRepository versions;
  private final ContentTypeRepository types;
  private final ObjectMapper mapper;

  SiteScopedContentQueryService(
      ContentItemRepository items,
      ContentVersionRepository versions,
      ContentTypeRepository types,
      ObjectMapper mapper) {
    this.items = items;
    this.versions = versions;
    this.types = types;
    this.mapper = mapper;
  }

  public List<Map<String, Object>> query(UUID siteId, String status, int limit) {
    int safe = clamp(limit);
    String wanted = status == null ? ContentItemEntity.STATUS_PUBLISHED : status;
    return items.findBySiteIdAndStatus(siteId, wanted).stream()
        .limit(safe)
        .map(i -> toMap(siteId, i, true))
        .toList();
  }

  public Map<String, Object> byRoute(UUID siteId, String route, String status) {
    ContentItemEntity item =
        items
            .findBySiteIdAndRoutePathAndStatus(
                siteId, route, status == null ? ContentItemEntity.STATUS_PUBLISHED : status)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "站点内容不存在"));
    return toMap(siteId, item, true);
  }

  /**
   * Query published/shared content by model code with ordering.
   *
   * <p>orderBy: viewCount, updatedAt, createdAt, title, sortOrder (field sort_order_i)
   */
  public List<Map<String, Object>> queryByType(
      UUID siteId,
      String contentTypeCode,
      String status,
      int limit,
      String orderBy,
      boolean publishedOnly) {
    return queryByType(siteId, contentTypeCode, status, limit, orderBy, publishedOnly, null);
  }

  /**
   * @param visible 非空时只保留查看者可见的内容，并在截断条数之前过滤
   */
  public List<Map<String, Object>> queryByType(
      UUID siteId,
      String contentTypeCode,
      String status,
      int limit,
      String orderBy,
      boolean publishedOnly,
      Predicate<UUID> visible) {
    if (contentTypeCode == null || contentTypeCode.isBlank()) {
      throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "contentType 不能为空");
    }
    ContentTypeEntity type =
        types.findBySiteIdAndCodeOrderByVersionAsc(siteId, contentTypeCode.trim()).stream()
            .reduce((left, right) -> right)
            .orElseThrow(
                () -> new BusinessException(ErrorCodes.NOT_FOUND, "内容模型不存在: " + contentTypeCode));
    String wanted = status == null || status.isBlank() ? ContentItemEntity.STATUS_PUBLISHED : status.trim();
    int safe = clamp(limit);
    Comparator<Map<String, Object>> comparator = comparatorFor(normalizeOrderBy(orderBy));
    List<Map<String, Object>> rows = new ArrayList<>();
    for (ContentItemEntity item : items.findByContentTypeId(type.getId())) {
      if (!siteId.equals(item.getSiteId()) || ContentItemEntity.STATUS_ARCHIVED.equals(item.getStatus())) {
        continue;
      }
      if (!wanted.equals(item.getStatus())) continue;
      if (publishedOnly && item.getPublishedVersion() == null) continue;
      if (visible != null && !visible.test(item.getId())) continue;
      rows.add(toMap(siteId, item, publishedOnly));
    }
    rows.sort(comparator);
    if (rows.size() > safe) return List.copyOf(rows.subList(0, safe));
    return List.copyOf(rows);
  }

  private Comparator<Map<String, Object>> comparatorFor(String orderBy) {
    return switch (orderBy) {
      case "updatedat", "updatetime" ->
          Comparator.<Map<String, Object>, String>comparing(
                  row -> String.valueOf(row.getOrDefault("updatedAt", "")),
                  Comparator.reverseOrder())
              .thenComparing(row -> String.valueOf(row.get("title")), String.CASE_INSENSITIVE_ORDER);
      case "createdat", "createtime" ->
          Comparator.<Map<String, Object>, String>comparing(
                  row -> String.valueOf(row.getOrDefault("createdAt", "")),
                  Comparator.reverseOrder())
              .thenComparing(row -> String.valueOf(row.get("title")), String.CASE_INSENSITIVE_ORDER);
      case "title" ->
          Comparator.comparing(row -> String.valueOf(row.get("title")), String.CASE_INSENSITIVE_ORDER);
      case "sortorder", "sort_order" ->
          Comparator.<Map<String, Object>, Integer>comparing(
                  row -> intField(row, "sort_order_i"), Comparator.reverseOrder())
              .thenComparing(row -> String.valueOf(row.get("title")), String.CASE_INSENSITIVE_ORDER);
      default ->
          Comparator.<Map<String, Object>, Long>comparing(
                  row -> longField(row, "viewCount"), Comparator.reverseOrder())
              .thenComparing(row -> String.valueOf(row.get("title")), String.CASE_INSENSITIVE_ORDER);
    };
  }

  private static String normalizeOrderBy(String orderBy) {
    if (orderBy == null || orderBy.isBlank()) return "viewcount";
    return orderBy.trim().toLowerCase(Locale.ROOT).replace('_', ' ').replace(" ", "");
  }

  private static int clamp(int limit) {
    return Math.max(1, Math.min(limit, MAX_LIMIT));
  }

  private static int intField(Map<String, Object> row, String code) {
    Object value = row.get(code);
    if (value instanceof Number number) return number.intValue();
    if (value == null) return 0;
    try {
      return Integer.parseInt(String.valueOf(value));
    } catch (NumberFormatException ex) {
      return 0;
    }
  }

  private static long longField(Map<String, Object> row, String code) {
    Object value = row.get(code);
    if (value instanceof Number number) return number.longValue();
    if (value == null) return 0L;
    try {
      return Long.parseLong(String.valueOf(value));
    } catch (NumberFormatException ex) {
      return 0L;
    }
  }

  private Map<String, Object> toMap(UUID siteId, ContentItemEntity item, boolean publishedOnly) {
    if (!siteId.equals(item.getSiteId())) {
      throw new BusinessException(ErrorCodes.FORBIDDEN, "禁止跨站查询");
    }
    int version =
        publishedOnly && ContentItemEntity.STATUS_PUBLISHED.equals(item.getStatus())
                && item.getPublishedVersion() != null
            ? item.getPublishedVersion()
            : item.getCurrentVersion();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", item.getId().toString());
    out.put("folderId", item.getFolderId() == null ? "" : item.getFolderId().toString());
    out.put("title", item.getTitle());
    out.put("routePath", item.getRoutePath());
    out.put("status", item.getStatus());
    out.put("viewCount", item.getViewCount());
    out.put("hits", item.getViewCount());
    out.put("contentTypeId", item.getContentTypeId().toString());
    out.put("createdAt", item.getCreatedAt() == null ? "" : item.getCreatedAt().toString());
    out.put("updatedAt", item.getUpdatedAt() == null ? "" : item.getUpdatedAt().toString());
    // FR-AI-GOV-007：隐式标识随公开 API/导出保留，供前台在站点开启显式角标时渲染「AI 生成」
    out.put("aiGenerated", item.isAiGenerated());
    out.put("link", item.getRoutePath() == null || item.getRoutePath().isBlank() ? "#" : item.getRoutePath());
    Map<String, Object> fields = Map.of();
    if (version > 0) {
      fields =
          versions
              .findByContentItemIdAndVersion(item.getId(), version)
              .map(
                  v -> {
                    try {
                      return mapper.readValue(
                          v.getFieldValues(), new TypeReference<Map<String, Object>>() {});
                    } catch (Exception ex) {
                      return Map.<String, Object>of();
                    }
                  })
              .orElse(Map.of());
    }
    out.put("fields", fields);
    fields.forEach(
        (key, value) -> {
          if (!out.containsKey(key)) out.put(key, value);
        });
    return out;
  }
}
