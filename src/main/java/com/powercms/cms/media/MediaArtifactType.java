package com.powercms.cms.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 媒体 AI 产物类型（FR-AGT-MEDIA-004）：异步任务与 MCP 工具共用的类型枚举与参数规范化。
 *
 * <p>产物分工（规划 §6.5）：{@code TRANSCRIPT} 由 Knox 转写产物回写（CMS 只消费）,
 * {@code SUMMARY} / {@code ALT_TEXT} 由 CMS LLM 生成。枚举顺序即串行执行顺序——
 * 摘要是转写稿的下游，必须先完成转写再生成摘要。
 */
public enum MediaArtifactType {

    TRANSCRIPT("转写", 0),
    SUMMARY("摘要", 1),
    ALT_TEXT("Alt 文本", 2);

    private final String label;
    private final int order;

    MediaArtifactType(String label, int order) {
        this.label = label;
        this.order = order;
    }

    /** 展示名（任务条目 / dry-run 回执）。 */
    public String label() {
        return label;
    }

    /** 执行顺序：数值小者先执行。 */
    public int order() {
        return order;
    }

    /** 解析单个类型名（大小写不敏感）；未知类型抛 {@code BAD_REQUEST}。 */
    public static MediaArtifactType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCodes.BAD_REQUEST, "缺少产物类型（type）");
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCodes.BAD_REQUEST,
                "不支持的产物类型：" + raw + "（可选 " + String.join("/", names()) + "）");
        }
    }

    /**
     * 规范化 {@code types} 数组参数：去重 + 按执行顺序排序。
     *
     * @throws BusinessException 缺失/非数组/为空/含未知类型
     */
    public static List<MediaArtifactType> normalize(JsonNode typesNode) {
        if (typesNode == null || typesNode.isNull()) {
            throw new BusinessException(ErrorCodes.BAD_REQUEST,
                "缺少必填字段 types（可选 " + String.join("/", names()) + "）");
        }
        if (!typesNode.isArray()) {
            throw new BusinessException(ErrorCodes.BAD_REQUEST, "types 必须是数组");
        }
        Set<MediaArtifactType> unique = new LinkedHashSet<>();
        for (JsonNode entry : typesNode) {
            String raw = entry.asText("").trim();
            if (raw.isEmpty()) {
                continue;
            }
            unique.add(parse(raw));
        }
        if (unique.isEmpty()) {
            throw new BusinessException(ErrorCodes.BAD_REQUEST, "types 不能为空数组");
        }
        return ordered(unique);
    }

    /** 按执行顺序排序（转写 → 摘要 → Alt 文本）。 */
    public static List<MediaArtifactType> ordered(Collection<MediaArtifactType> types) {
        List<MediaArtifactType> sorted = new ArrayList<>(types);
        sorted.sort(java.util.Comparator.comparingInt(MediaArtifactType::order));
        return List.copyOf(sorted);
    }

    /** 全部类型名（用于错误提示与文档口径）。 */
    public static List<String> names() {
        List<String> all = new ArrayList<>();
        for (MediaArtifactType type : values()) {
            all.add(type.name());
        }
        return all;
    }
}
