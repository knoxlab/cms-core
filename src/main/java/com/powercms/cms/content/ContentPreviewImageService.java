package com.powercms.cms.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.entity.ContentVersionEntity;
import com.powercms.cms.content.repo.ContentVersionRepository;
import com.powercms.cms.model.entity.FieldDefinitionEntity;
import com.powercms.cms.model.repo.FieldDefinitionRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 内容列表预览图解析（内容搜索 / 页面管理等列表的缩略图与列表视图）。
 *
 * <p>预览图取内容模型里的图片字段（{@code IMAGE}）：按字段排序取第一个有值的字段，
 * 媒体类模型预置的统一预览图字段（{@code systemFieldType=thumbnailUrl}）优先。
 * 返回值是字段原值——裸媒体资产编号或 mediaRef JSON 由前端换成
 * {@code /media-thumb/{siteId}/{assetId}} 代理地址，已保存的站内/外部 URL 原样使用。
 * 字段为空时不返回该条目，前端回退默认图片。</p>
 *
 * <p>列表按内容类型 + 当前版本批量取数，避免逐条查版本造成 N+1。</p>
 */
@Service
public class ContentPreviewImageService {

    /** 媒体类内容模型的统一预览图系统字段类型（见 ContentModelValidator）。 */
    static final String THUMBNAIL_SYSTEM_FIELD = "thumbnailUrl";

    /** 内容模型中的图片字段类型。 */
    static final String IMAGE_FIELD_TYPE = "IMAGE";

    private final ContentVersionRepository versionRepo;
    private final FieldDefinitionRepository fieldRepo;
    private final ObjectMapper om;

    public ContentPreviewImageService(ContentVersionRepository versionRepo,
                                      FieldDefinitionRepository fieldRepo,
                                      ObjectMapper om) {
        this.versionRepo = versionRepo;
        this.fieldRepo = fieldRepo;
        this.om = om;
    }

    /**
     * 批量解析条目的预览图字段值。
     *
     * @return 内容条目 ID → 预览图字段原值；没有图片字段/字段为空/版本缺失的条目不出现在结果里
     */
    public Map<UUID, String> resolve(Collection<ContentItemEntity> items) {
        if (items == null || items.isEmpty()) return Map.of();
        List<UUID> typeIds = items.stream()
            .map(ContentItemEntity::getContentTypeId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (typeIds.isEmpty()) return Map.of();

        Map<UUID, List<String>> codesByType = candidateCodes(typeIds);
        if (codesByType.isEmpty()) return Map.of();

        List<ContentItemEntity> relevant = items.stream()
            .filter(item -> item.getContentTypeId() != null)
            .filter(item -> !codesByType.getOrDefault(item.getContentTypeId(), List.of()).isEmpty())
            .toList();
        if (relevant.isEmpty()) return Map.of();

        List<UUID> itemIds = relevant.stream().map(ContentItemEntity::getId).filter(Objects::nonNull).toList();
        if (itemIds.isEmpty()) return Map.of();

        Map<UUID, ContentVersionEntity> latest = new LinkedHashMap<>();
        versionRepo.findLatestByContentItemIdIn(itemIds)
            .forEach(version -> latest.putIfAbsent(version.getContentItemId(), version));

        Map<UUID, String> previews = new LinkedHashMap<>();
        for (ContentItemEntity item : relevant) {
            ContentVersionEntity version = latest.get(item.getId());
            if (version == null) continue;
            String preview = firstNonBlank(version.getFieldValues(), codesByType.get(item.getContentTypeId()));
            if (!preview.isBlank()) previews.put(item.getId(), preview);
        }
        return previews;
    }

    /** 每个内容类型的候选图片字段码：统一预览图字段优先，其余 IMAGE 字段按设计器排序。 */
    private Map<UUID, List<String>> candidateCodes(List<UUID> typeIds) {
        List<FieldDefinitionEntity> fields =
            fieldRepo.findByContentTypeIdInOrderByContentTypeIdAscSortOrderAsc(typeIds);
        Map<UUID, LinkedHashSet<String>> codes = new LinkedHashMap<>();
        // 第一轮：媒体统一预览图字段（thumbnailUrl_s）——服务端同步/截帧产物优先于用户图片字段。
        for (FieldDefinitionEntity field : fields) {
            if (THUMBNAIL_SYSTEM_FIELD.equals(field.getSystemFieldType())) {
                codes.computeIfAbsent(field.getContentTypeId(), ignored -> new LinkedHashSet<>())
                    .add(field.getCode());
            }
        }
        // 第二轮：其余图片字段，按 sortOrder（仓库查询已排序）。
        for (FieldDefinitionEntity field : fields) {
            if (IMAGE_FIELD_TYPE.equalsIgnoreCase(field.getFieldType())) {
                codes.computeIfAbsent(field.getContentTypeId(), ignored -> new LinkedHashSet<>())
                    .add(field.getCode());
            }
        }
        Map<UUID, List<String>> result = new LinkedHashMap<>();
        codes.forEach((typeId, fieldCodes) -> result.put(typeId, new ArrayList<>(fieldCodes)));
        return result;
    }

    /** 按候选字段顺序取第一个非空值；字段值可能是裸值、mediaRef JSON 对象或多值数组。 */
    private String firstNonBlank(String fieldValues, List<String> codes) {
        if (fieldValues == null || fieldValues.isBlank() || codes == null || codes.isEmpty()) return "";
        JsonNode root;
        try {
            root = om.readTree(fieldValues);
        } catch (Exception ex) {
            return "";
        }
        if (root == null || !root.isObject()) return "";
        for (String code : codes) {
            String value = textValue(root.get(code));
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private String textValue(JsonNode node) {
        if (node == null || node.isNull()) return "";
        if (node.isTextual()) return node.asText("").trim();
        if (node.isObject()) {
            String assetId = node.path("assetId").asText("");
            if (!assetId.isBlank()) return assetId.trim();
            return node.path("url").asText("").trim();
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                String value = textValue(child);
                if (!value.isBlank()) return value;
            }
        }
        return "";
    }
}
