package com.powercms.cms.content;

import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.entity.ContentVersionEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.content.repo.ContentVersionRepository;
import com.powercms.cms.identity.ActorNameResolver;
import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.repo.ContentTypeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * FR-CV-VER-009/010 时间机器：按历史时间点回放站点的**只读快照**。
 *
 * <p>「某时刻的内容」= 该内容条目在 {@code at} 之前（含）保存的**最后一个版本**。内容版本本身是
 * 完整快照（FR-CV-VER-003），条目级标题 / 页面路径 / 页面模板自 {@code V100} 起也随版本落库，
 * 所以一次版本回溯就能把「那一刻这条内容长什么样」还原出来，无需重放增量日志。</p>
 *
 * <h2>明确的边界（不在本服务能力内，界面需如实说明）</h2>
 * <ul>
 *   <li><b>栏目树</b>：栏目没有版本表，只能给出**当前**栏目树（前端按只读渲染）。</li>
 *   <li><b>状态流转</b>：草稿/审核/发布等状态变化未纳入内容版本快照，故 {@code currentStatus}
 *       字段返回的是**当前**状态，仅作参考，不承诺等于 {@code at} 时刻的状态。</li>
 *   <li><b>已删除条目</b>：内容条目删除后版本行级联清理，历史快照无法再看到它——时间机器只回放
 *       仍然存在的内容。</li>
 * </ul>
 *
 * <p>本服务只读：不写任何业务表，也不触发版本生成，浏览历史不会影响当前数据（FR-CV-VER-009 验收项）。</p>
 */
@Service
@Transactional(readOnly = true)
public class ContentTimeMachineService {

    private final ContentItemRepository itemRepo;
    private final ContentVersionRepository versionRepo;
    private final ContentTypeRepository ctRepo;
    private final ActorNameResolver actorNames;

    public ContentTimeMachineService(ContentItemRepository itemRepo, ContentVersionRepository versionRepo,
                                     ContentTypeRepository ctRepo, ActorNameResolver actorNames) {
        this.itemRepo = itemRepo;
        this.versionRepo = versionRepo;
        this.ctRepo = ctRepo;
        this.actorNames = actorNames;
    }

    /** 快照里的一条内容：条目身份 + 该时刻生效版本的内容。 */
    public record SnapshotItem(UUID id, UUID categoryId, UUID folderId, UUID contentTypeId,
                               String contentTypeCode, String contentTypeName,
                               String title, String routePath, String templatePath,
                               int version, int modelVersion, Instant versionCreatedAt,
                               String createdBy, String createdByName, String fieldValues,
                               String currentStatus) {}

    /**
     * 某时刻的站点快照。
     *
     * @param at                       查询时刻（回显给界面，避免前端自己算时区）
     * @param earliestContentCreatedAt 站内内容最早的创建时间；快照为空且该时间晚于 {@code at} 时，
     *                                 界面即可给出「该时间点早于内容创建时间」的准确提示
     * @param total                    该时刻可见的内容条数
     * @param notYetCreated            站点**当前**存在、但在 {@code at} 时刻尚未创建（或尚无版本）的条数，
     *                                 供界面解释「为什么列表比现在少」
     */
    public record Snapshot(Instant at, Instant earliestContentCreatedAt, int total, int notYetCreated,
                           List<SnapshotItem> items) {}

    public Snapshot at(UUID siteId, Instant at) {
        Instant instant = at != null ? at : Instant.now();
        List<ContentItemEntity> items = itemRepo.findBySiteId(siteId);
        if (items.isEmpty()) {
            return new Snapshot(instant, null, 0, 0, List.of());
        }

        Map<UUID, ContentItemEntity> itemById = items.stream()
            .collect(Collectors.toMap(ContentItemEntity::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));

        List<ContentVersionEntity> versions = versionRepo.findVersionsNotAfter(itemById.keySet(), instant);
        // 仓储按 (contentItemId asc, version desc) 返回，故每个条目首次出现的那行就是该时刻生效的快照
        Map<UUID, ContentVersionEntity> effective = new LinkedHashMap<>();
        for (ContentVersionEntity version : versions) {
            effective.putIfAbsent(version.getContentItemId(), version);
        }

        Map<UUID, ContentTypeEntity> types = ctRepo.findBySiteIdOrderByCodeAsc(siteId).stream()
            .collect(Collectors.toMap(ContentTypeEntity::getId, Function.identity(), (a, b) -> a));
        // OBS-11-04：AI 版本落库为用户 ID、人工版本落库为用户名，展示层统一解析（一次批量查询）
        Map<String, String> names = actorNames.resolveAll(
            effective.values().stream().map(ContentVersionEntity::getCreatedBy).toList());

        List<SnapshotItem> out = new ArrayList<>(effective.size());
        for (Map.Entry<UUID, ContentVersionEntity> entry : effective.entrySet()) {
            ContentItemEntity item = itemById.get(entry.getKey());
            ContentVersionEntity version = entry.getValue();
            ContentTypeEntity type = types.get(item.getContentTypeId());
            out.add(new SnapshotItem(
                item.getId(), item.getCategoryId(), item.getFolderId(), item.getContentTypeId(),
                type == null ? null : type.getCode(),
                type == null ? null : type.getName(),
                // 条目级字段优先取**版本快照**（那才是该时刻的值）；历史行未落快照时退回条目当前值
                version.getTitle() != null ? version.getTitle() : item.getTitle(),
                version.getRoutePath() != null ? version.getRoutePath() : item.getRoutePath(),
                version.getTemplatePath() != null ? version.getTemplatePath() : item.getTemplatePath(),
                version.getVersion(), version.getModelVersion(), version.getCreatedAt(),
                version.getCreatedBy(), names.get(version.getCreatedBy()), version.getFieldValues(),
                item.getStatus()));
        }
        out.sort(Comparator
            .comparing((SnapshotItem s) -> s.contentTypeCode() == null ? "" : s.contentTypeCode())
            .thenComparing(s -> s.title() == null ? "" : s.title())
            .thenComparing(SnapshotItem::id));

        Instant earliest = items.stream()
            .map(ContentItemEntity::getCreatedAt)
            .filter(Objects::nonNull)
            .min(Comparator.naturalOrder())
            .orElse(null);

        return new Snapshot(instant, earliest, out.size(), items.size() - out.size(), out);
    }
}
