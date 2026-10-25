package com.powercms.cms.search;

import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.publish.ReleaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 访客索引**人群唯一真源**（FR-SEARCH-002 / FR-API-002 / FR-SEARCH-007）。
 *
 * <p>「访客索引里该有哪些文档」此前散落在三条写路径里，各自一套口径：
 * <ol>
 *   <li>{@code PublicSearchService.rebuild} 按**已部署 Release 成员**建，但文档缺 {@code status} 等字段；</li>
 *   <li>{@code PublicSearchService.onPublished} 按**任意内容发布**增量写（不看 Release）；</li>
 *   <li>{@code EmbeddingRebuildService}（「重建向量」）按 {@code status=PUBLISHED} 扫全站，
 *       且不写 {@code releaseId}、不登记 {@code last_release_id}。</li>
 * </ol>
 * 三套口径叠加的后果实测可复现：同一站点（{@code releases=0}）匿名交付检索能命中 1 条，
 * 而交付读取按 Release 口径应为 0 条——访客索引 ⊋ 交付可见集；同时「重建索引」按 Release 重建后
 * 文档不带 {@code status}，与查询期 {@code status=PUBLISHED} 过滤互相打架（匿名检索全空）。
 *
 * <p><b>统一口径 = 交付可见集</b>（与 {@link com.powercms.cms.delivery.DeliveryService#listPublished}
 * 逐条同谓词，避免再分叉）：
 * <pre>
 *   已部署 Release 的成员(contentItemId, version)
 *     ∩ 条目仍存在 且 同站点
 *     ∩ {@code status == PUBLISHED}（草稿/审核中/已撤回/归档一律不入）
 * </pre>
 * **无已部署 Release → 空集**（规格原文：访客索引「只包含当前已部署 Release 的公开内容」）。
 * 对象级 ACL（{@code hasAnonymousRight}）不在此处收窄：它是**读时**判定，
 * 放进索引人群会让「ACL 变更」变成「必须重建索引」，故仍由查询期闸门承担（R3）。
 *
 * <p>本类只回答「哪些条目、哪个版本」；文档形态由
 * {@link AdminSearchService#buildDocument(ContentItemEntity, int, boolean, com.powercms.cms.content.ContentSearchTextAssembler.Target, UUID)}
 * 统一构造（含 {@code releaseId}/{@code contentVersion}，FR-SEARCH-003）。
 */
@Component
public class PublicIndexScope {

    private static final Logger log = LoggerFactory.getLogger(PublicIndexScope.class);

    private final ReleaseService releaseService;
    private final ContentItemRepository itemRepo;

    public PublicIndexScope(ReleaseService releaseService, ContentItemRepository itemRepo) {
        this.releaseService = releaseService;
        this.itemRepo = itemRepo;
    }

    /** 人群中的一个成员：内容条目 + **Release 冻结时固定的版本**（不是 currentVersion）。 */
    public record Entry(UUID contentItemId, int version) {}

    /**
     * 一轮访客索引人群快照。
     *
     * @param releaseId 口径来源的 Release；{@code null} 表示站点当前**无已部署 Release**（人群恒空）
     * @param frozenAt  Release 冻结时刻（本轮快照的时刻标记，供进度展示与续跑判定）
     * @param entries   成员清单，按 {@code contentItemId} 升序（批量重建的稳定游标序）
     */
    public record Scope(UUID releaseId, Instant frozenAt, List<Entry> entries) {
        public static Scope empty() { return new Scope(null, null, List.of()); }
        public boolean isEmpty() { return entries.isEmpty(); }
        public int size() { return entries.size(); }
    }

    /** 站点**当前已部署 Release** 的人群（三条写路径的统一入口）。 */
    public Scope deployed(UUID siteId) {
        var release = releaseService.getDeployed(siteId);
        return release == null ? Scope.empty() : of(siteId, release);
    }

    /**
     * **指定** Release 的人群（部署时「按待部署 Release 建立候选访客索引」用，FR-SEARCH-007 门禁自愈）。
     *
     * @return 该 Release 不存在或不属于该站点时返回空集（fail-closed，不猜）
     */
    public Scope forRelease(UUID siteId, UUID releaseId) {
        if (releaseId == null) return Scope.empty();
        ReleaseService.ReleaseInfo release;
        try {
            release = releaseService.get(releaseId);
        } catch (RuntimeException ex) {
            log.warn("按指定 Release 解析访客索引人群失败 release={}：{}", releaseId, ex.getMessage());
            return Scope.empty();
        }
        if (release == null || !siteId.equals(release.siteId())) return Scope.empty();
        return of(siteId, release);
    }

    /**
     * 站点已部署 Release 中该条目对应的成员（发布事件增量用，避免每次发布都全量解析人群）。
     *
     * @return 不在已部署 Release 内 → {@link Optional#empty()}（此时**不得**写访客索引）
     */
    public Optional<Entry> deployedEntry(UUID siteId, UUID contentItemId) {
        var release = releaseService.getDeployed(siteId);
        if (release == null || contentItemId == null) return Optional.empty();
        return release.contents().stream()
            .filter(rc -> contentItemId.equals(rc.contentItemId()))
            .findFirst()
            .map(rc -> new Entry(rc.contentItemId(), rc.version()));
    }

    /** 已部署 Release 的 id（查询期严格口径判定用；无部署返回 {@code null}）。 */
    public UUID deployedReleaseId(UUID siteId) {
        var release = releaseService.getDeployed(siteId);
        return release == null ? null : release.id();
    }

    private Scope of(UUID siteId, ReleaseService.ReleaseInfo release) {
        List<Entry> entries = new ArrayList<>();
        for (ReleaseService.ReleaseContentInfo member : release.contents()) {
            ContentItemEntity item = itemRepo.findById(member.contentItemId()).orElse(null);
            if (item == null || !siteId.equals(item.getSiteId())) continue;
            // 与交付读取同谓词：Release 冻结点之后被撤回/归档/降级为草稿的条目不再对外可见
            if (!ContentItemEntity.STATUS_PUBLISHED.equals(item.getStatus())) continue;
            entries.add(new Entry(member.contentItemId(), member.version()));
        }
        entries.sort(Comparator.comparing(Entry::contentItemId));
        return new Scope(release.id(), release.frozenAt(), List.copyOf(entries));
    }
}
