package com.powercms.cms.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.entity.ContentVersionEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.content.repo.ContentVersionRepository;
import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.entity.FieldDefinitionEntity;
import com.powercms.cms.model.repo.FieldDefinitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 模型版本迁移执行器（FR-CM-CT-015 扩展）：新版本发布后把存量内容迁移到新模型。
 *
 * <p>迁移语义：
 * <ul>
 *   <li>内容项重绑：contentTypeId 指向新版本行，此后编辑/校验/交付统一走新模型；</li>
 *   <li>字段值迁移：按 migrationRules 重命名（renames）、回填默认值（defaults）、
 *       删除字段清理（drop）；</li>
 *   <li>版本历史不可变：仅基于最新字段值补写一条迁移快照（version+1，modelVersion=新版本号），
 *       已发布内容同步指向迁移快照，交付层立刻可见迁移结果。</li>
 * </ul>
 *
 * <p>migrationRules 载荷（publish 请求体，null 时走默认规则）：
 * <pre>{@code
 * {
 *   "mode": "MIGRATE" | "SKIP",          // 缺省 MIGRATE；SKIP 只重绑不迁移字段值
 *   "renames":  { "old_code": "new_code" },
 *   "defaults": { "new_code": "默认值" },
 *   "drop":     ["removed_code"]
 * }
 * }</pre>
 *
 * <p>默认规则（无 rules）：新模型新增字段若声明 defaultValue 则回填；旧 fieldValues 中
 * 新模型不存在的代码原样保留（不静默丢数据），由 drop 显式清理。
 */
@Component
public class ContentTypeMigrationService {

    public static final Logger log = LoggerFactory.getLogger(ContentTypeMigrationService.class);

    public static final String MODE_MIGRATE = "MIGRATE";
    public static final String MODE_SKIP = "SKIP";

    private final ContentItemRepository itemRepo;
    private final ContentVersionRepository versionRepo;
    private final FieldDefinitionRepository fieldRepo;
    private final ObjectMapper om;

    public ContentTypeMigrationService(ContentItemRepository itemRepo,
                                       ContentVersionRepository versionRepo,
                                       FieldDefinitionRepository fieldRepo,
                                       ObjectMapper om) {
        this.itemRepo = itemRepo;
        this.versionRepo = versionRepo;
        this.fieldRepo = fieldRepo;
        this.om = om;
    }

    public record MigrationResult(int itemsRebound, int itemsMigrated, int versionsWritten, String mode) {}

    /**
     * 把绑定在 fromTypeId 的全部内容迁移到 toType（同站点同 code 的两个版本行）。
     */
    @Transactional
    public MigrationResult migrate(UUID siteId, UUID fromTypeId, ContentTypeEntity toType, JsonNode rules) {
        String mode = resolveMode(rules);
        if (!toType.getSiteId().equals(siteId)) {
            throw new IllegalArgumentException("target type does not belong to site " + siteId);
        }
        Map<String, String> renames = stringMap(rules, "renames");
        Map<String, JsonNode> defaults = nodeMap(rules, "defaults");
        List<String> drop = stringList(rules, "drop");

        Map<String, FieldDefinitionEntity> newFields = new HashMap<>();
        for (FieldDefinitionEntity f : fieldRepo.findByContentTypeIdOrderBySortOrder(toType.getId())) {
            newFields.put(f.getCode(), f);
        }
        for (String code : drop) {
            if (!renames.containsKey(code) && !newFields.containsKey(code) && !defaults.containsKey(code)) {
                log.warn("migration drop rule references unknown field: type={} code={}", toType.getCode(), code);
            }
        }

        List<ContentItemEntity> items = itemRepo.findByContentTypeId(fromTypeId);
        int rebound = 0, migratedItems = 0, versionsWritten = 0;
        for (ContentItemEntity item : items) {
            if (!siteId.equals(item.getSiteId())) continue;
            item.setContentTypeId(toType.getId());
            itemRepo.save(item);
            rebound++;
            if (MODE_MIGRATE.equals(mode)) {
                versionsWritten += writeMigrationSnapshot(item, toType, renames, defaults, drop, newFields);
                migratedItems++;
            }
        }
        log.info("content type migration done: site={} fromType={} toType={}({} v{}) mode={} items={} versions={}",
            siteId, fromTypeId, toType.getId(), toType.getCode(), toType.getVersion(), mode,
            rebound, versionsWritten);
        return new MigrationResult(rebound, migratedItems, versionsWritten, mode);
    }

    private String resolveMode(JsonNode rules) {
        String mode = rules != null && rules.hasNonNull("mode")
            ? rules.path("mode").asText(MODE_MIGRATE).trim().toUpperCase()
            : MODE_MIGRATE;
        if (!MODE_MIGRATE.equals(mode) && !MODE_SKIP.equals(mode)) {
            throw new IllegalArgumentException("migrationRules.mode 仅允许 MIGRATE/SKIP: " + mode);
        }
        return mode;
    }

    private int writeMigrationSnapshot(ContentItemEntity item, ContentTypeEntity toType,
                                       Map<String, String> renames, Map<String, JsonNode> defaults,
                                       List<String> drop, Map<String, FieldDefinitionEntity> newFields) {
        ContentVersionEntity latest = versionRepo
            .findByContentItemIdOrderByVersionDesc(item.getId())
            .stream().findFirst().orElse(null);
        if (latest == null) return 0;
        try {
            JsonNode values = om.readTree(latest.getFieldValues());
            ObjectNode migrated = transform(values, renames, defaults, drop, newFields);
            int snapshotVersion = latest.getVersion() + 1;
            versionRepo.save(ContentVersionEntity.create(item.getId(), snapshotVersion,
                om.writeValueAsString(migrated), toType.getVersion(), "system",
                "模型迁移快照 v" + toType.getVersion()));
            item.setCurrentVersion(snapshotVersion);
            if (item.getPublishedVersion() != null) {
                // 已发布内容同步指向迁移快照，交付层立刻可见迁移结果
                item.setPublishedVersion(snapshotVersion);
            }
            itemRepo.save(item);
            return 1;
        } catch (Exception ex) {
            log.warn("content type migration snapshot failed item={} err={}", item.getId(), ex.getMessage());
            return 0;
        }
    }

    ObjectNode transform(JsonNode values, Map<String, String> renames,
                         Map<String, JsonNode> defaults, List<String> drop,
                         Map<String, FieldDefinitionEntity> newFields) {
        ObjectNode out = om.createObjectNode();
        if (values != null && values.isObject()) {
            values.fields().forEachRemaining(e -> out.set(renames.getOrDefault(e.getKey(), e.getKey()), e.getValue()));
        }
        for (Map.Entry<String, JsonNode> d : defaults.entrySet()) {
            out.set(d.getKey(), d.getValue().deepCopy());
        }
        for (FieldDefinitionEntity f : newFields.values()) {
            if (!out.has(f.getCode()) && f.getDefaultValue() != null && !f.getDefaultValue().isBlank()) {
                out.put(f.getCode(), f.getDefaultValue());
            }
        }
        for (String code : drop) {
            out.remove(code);
        }
        return out;
    }

    private Map<String, String> stringMap(JsonNode rules, String key) {
        Map<String, String> out = new HashMap<>();
        if (rules != null && rules.path(key).isObject()) {
            rules.path(key).fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
        }
        return out;
    }

    private Map<String, JsonNode> nodeMap(JsonNode rules, String key) {
        Map<String, JsonNode> out = new HashMap<>();
        if (rules != null && rules.path(key).isObject()) {
            rules.path(key).fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue()));
        }
        return out;
    }

    private List<String> stringList(JsonNode rules, String key) {
        if (rules == null || !rules.path(key).isArray()) return List.of();
        return om.convertValue(rules.path(key), om.getTypeFactory()
            .constructCollectionType(List.class, String.class));
    }
}
