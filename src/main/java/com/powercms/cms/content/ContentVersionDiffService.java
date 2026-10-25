package com.powercms.cms.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.content.entity.ContentVersionEntity;
import com.powercms.cms.content.repo.ContentVersionRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 内容版本字段级 Diff（FR-AI-AGENT-002 / FR-AGT-FLOW-003 审批辅助的数据基础）。
 *
 * <p>只比较版本快照中的字段值 JSON（{@code content_version.field_values}），按字段给出
 * ADDED / REMOVED / MODIFIED 三类变更。标题等条目级属性不随版本落快照，故不在 Diff 范围内
 * （审核摘要另行透出当前标题与版本号，不伪造标题级 Diff）。
 */
@Service
public class ContentVersionDiffService {

    /** 单字段展示值截断长度，避免长正文把摘要撑爆。 */
    public static final int MAX_VALUE_LENGTH = 500;

    private final ContentVersionRepository versionRepo;
    private final ObjectMapper objectMapper;

    public ContentVersionDiffService(ContentVersionRepository versionRepo, ObjectMapper objectMapper) {
        this.versionRepo = versionRepo;
        this.objectMapper = objectMapper;
    }

    /** 字段变更：changeType ∈ {ADDED, REMOVED, MODIFIED}。 */
    public record FieldChange(String field, String changeType, String before, String after) {}

    /**
     * 版本差异；{@code fromVersion=0} 表示与空版本比较（初始版本全部为 ADDED）。
     *
     * <p>{@code unchangedFields} 是参与比对但取值相同的字段数（规范 §15.4 的「未变 N」摘要），
     * 由「参与比对的键集合 − 变更条数」得出，避免前端自行猜测比对范围。</p>
     */
    public record VersionDiff(int fromVersion, int toVersion, int changedFields,
                              int unchangedFields, List<FieldChange> changes) {
        /**
         * 兼容旧签名（新增 {@code unchangedFields} 之前的构造方/用例）：未变计数按 0 处理，
         * 摘要里「未变」显示 0 而不是让调用方编译不过。
         */
        public VersionDiff(int fromVersion, int toVersion, int changedFields, List<FieldChange> changes) {
            this(fromVersion, toVersion, changedFields, 0, changes);
        }

        public static VersionDiff empty(int version) {
            return new VersionDiff(0, version, 0, 0, List.of());
        }
    }

    /** 比较任意两个版本；任一版本缺失即视为空版本（不抛错，保证审核辅助不阻断）。 */
    public VersionDiff diff(UUID contentItemId, int fromVersion, int toVersion) {
        JsonNode before = fieldsOf(contentItemId, fromVersion);
        JsonNode after = fieldsOf(contentItemId, toVersion);
        Set<String> keys = new LinkedHashSet<>();
        before.properties().forEach(e -> keys.add(e.getKey()));
        after.properties().forEach(e -> keys.add(e.getKey()));

        List<FieldChange> changes = new ArrayList<>();
        for (String key : keys) {
            JsonNode b = before.get(key);
            JsonNode a = after.get(key);
            String bv = display(b);
            String av = display(a);
            if (same(b, a)) {
                continue;
            }
            String type = b == null || b.isNull() ? "ADDED" : a == null || a.isNull() ? "REMOVED" : "MODIFIED";
            changes.add(new FieldChange(key, type, truncate(bv), truncate(av)));
        }
        return new VersionDiff(fromVersion, toVersion, changes.size(),
            keys.size() - changes.size(), List.copyOf(changes));
    }

    /** 比较当前版本与其上一版本；当前为初始版本时返回全量 ADDED。 */
    public VersionDiff diffAgainstPrevious(UUID contentItemId, int currentVersion) {
        if (currentVersion <= 1) {
            return diff(contentItemId, 0, currentVersion);
        }
        return diff(contentItemId, currentVersion - 1, currentVersion);
    }

    /** 条目级系统字段的展示名（与编辑器字段码一致，前端可直接套用字段标签）。 */
    public static final String ITEM_TITLE = "title";
    public static final String ITEM_ROUTE_PATH = "routePath";
    public static final String ITEM_TEMPLATE_PATH = "templatePath";

    /**
     * FR-CV-VER-008：版本对比除模型字段外，还须展示**条目级系统字段**（标题 / 页面路径 / 页面模板）的差异。
     *
     * <p>这三列自 {@code V100} 起随版本落快照，此前只存在于内容条目上——所以
     * {@link #diff(UUID, int, int)}（审核摘要用）保持原语义不做条目级比较，本方法供
     * 版本历史「对比」交互使用，两者互不影响。</p>
     */
    public VersionDiff diffWithItemFields(UUID contentItemId, int fromVersion, int toVersion) {
        VersionDiff base = diff(contentItemId, fromVersion, toVersion);
        List<FieldChange> itemChanges = itemFieldChanges(contentItemId, fromVersion, toVersion);
        List<FieldChange> changes = new ArrayList<>(itemChanges);
        changes.addAll(base.changes());
        // 条目级三个系统字段同样纳入「参与比对」的口径：未变 = 模型字段未变 + 条目级未变
        int itemUnchanged = ITEM_FIELD_COUNT - itemChanges.size();
        return new VersionDiff(fromVersion, toVersion, changes.size(),
            base.unchangedFields() + itemUnchanged, List.copyOf(changes));
    }

    /** 条目级参与比对的系统字段数（title / routePath / templatePath）。 */
    private static final int ITEM_FIELD_COUNT = 3;

    private List<FieldChange> itemFieldChanges(UUID contentItemId, int fromVersion, int toVersion) {
        ContentVersionEntity before = snapshot(contentItemId, fromVersion);
        ContentVersionEntity after = snapshot(contentItemId, toVersion);
        List<FieldChange> out = new ArrayList<>(3);
        addItemChange(out, ITEM_TITLE,
            before == null ? null : before.getTitle(), after == null ? null : after.getTitle());
        addItemChange(out, ITEM_ROUTE_PATH,
            before == null ? null : before.getRoutePath(), after == null ? null : after.getRoutePath());
        addItemChange(out, ITEM_TEMPLATE_PATH,
            before == null ? null : before.getTemplatePath(), after == null ? null : after.getTemplatePath());
        return out;
    }

    private static void addItemChange(List<FieldChange> out, String field, String before, String after) {
        String b = blankToNull(before);
        String a = blankToNull(after);
        if (java.util.Objects.equals(b, a)) return;
        String type = b == null ? "ADDED" : a == null ? "REMOVED" : "MODIFIED";
        out.add(new FieldChange(field, type, truncate(b), truncate(a)));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private ContentVersionEntity snapshot(UUID contentItemId, int version) {
        if (version <= 0) {
            return null;
        }
        return versionRepo.findByContentItemIdAndVersion(contentItemId, version).orElse(null);
    }

    private JsonNode fieldsOf(UUID contentItemId, int version) {
        if (version <= 0) {
            return objectMapper.createObjectNode();
        }
        return versionRepo.findByContentItemIdAndVersion(contentItemId, version)
            .map(ContentVersionEntity::getFieldValues)
            .map(this::parse)
            .orElseGet(objectMapper::createObjectNode);
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return node != null && node.isObject() ? node : objectMapper.createObjectNode();
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private static boolean same(JsonNode a, JsonNode b) {
        if (a == null && b == null) return true;
        if (a == null) return b.isNull();
        if (b == null) return a.isNull();
        return a.equals(b);
    }

    private static String display(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.isTextual() ? node.asText() : node.toString();
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= MAX_VALUE_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_VALUE_LENGTH) + "…";
    }
}
