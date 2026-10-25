package com.powercms.cms.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.content.repo.ContentVersionRepository;
import com.powercms.cms.model.entity.FieldDefinitionEntity;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.cms.model.repo.FieldDefinitionRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** 自由标签（TAG）支持：标签池建议与标签云（FR-CM-FTSUP-001~003，19.11 节）。 */
@Service
public class TagService {
  private final ContentTypeRepository ctRepo;
  private final FieldDefinitionRepository fieldRepo;
  private final ContentItemRepository itemRepo;
  private final ContentVersionRepository versionRepo;
  private final ObjectMapper om;

  TagService(
      ContentTypeRepository c,
      FieldDefinitionRepository f,
      ContentItemRepository i,
      ContentVersionRepository v,
      ObjectMapper o) {
    ctRepo = c;
    fieldRepo = f;
    itemRepo = i;
    versionRepo = v;
    om = o;
  }

  public record TagStat(String tag, int count) {}

  /** 标签池建议：返回匹配前缀的已有标签（FR-CM-FTSUP-001）。 */
  public List<String> suggestions(UUID siteId, String prefix) {
    String p = prefix == null ? "" : prefix.trim().toLowerCase(Locale.ROOT);
    return collect(siteId).keySet().stream()
        .filter(t -> p.isEmpty() || t.toLowerCase(Locale.ROOT).startsWith(p))
        .sorted()
        .toList();
  }

  /** 标签云：按关联内容数量降序返回（FR-CM-FTSUP-003）。 */
  public List<TagStat> cloud(UUID siteId) {
    return collect(siteId).entrySet().stream()
        .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
        .map(e -> new TagStat(e.getKey(), e.getValue()))
        .toList();
  }

  private Map<String, Integer> collect(UUID siteId) {
    Set<String> codes = tagFieldCodes(siteId);
    if (codes.isEmpty()) return Map.of();
    Map<String, Integer> counts = new HashMap<>();
    for (ContentItemEntity item : itemRepo.findBySiteId(siteId)) {
      versionRepo.findByContentItemIdOrderByVersionDesc(item.getId()).stream()
          .findFirst()
          .ifPresent(
              v -> {
                JsonNode values = parse(v.getFieldValues());
                if (values == null || !values.isObject()) return;
                for (String code : codes) {
                  JsonNode tags = values.get(code);
                  if (tags != null && tags.isArray())
                    for (JsonNode t : tags)
                      if (t.isTextual() && !t.asText().isBlank())
                        counts.merge(t.asText().trim(), 1, Integer::sum);
                }
              });
    }
    return counts;
  }

  private Set<String> tagFieldCodes(UUID siteId) {
    return ctRepo.findBySiteIdOrderByCodeAsc(siteId).stream()
        .flatMap(ct -> fieldRepo.findByContentTypeIdOrderBySortOrder(ct.getId()).stream())
        .filter(f -> "TAG".equals(f.getFieldType()))
        .map(FieldDefinitionEntity::getCode)
        .collect(Collectors.toSet());
  }

  private JsonNode parse(String s) {
    if (s == null || s.isBlank()) return null;
    try {
      return om.readTree(s);
    } catch (Exception e) {
      return null;
    }
  }
}
