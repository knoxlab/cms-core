package com.powercms.cms.content;

import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.entity.ContentVersionEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.content.repo.ContentVersionRepository;
import com.powercms.cms.identity.ActorNameResolver;
import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.repo.ContentTypeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FR-CV-VER-009/010：时间机器「某时刻的快照 = 该条目在当时最后一个版本」。
 *
 * <p>真正的「版本创建时间 <= 时间点」由仓储 SQL 落定（`ContentVersionRepository#findVersionsNotAfter`），
 * 本类锁定服务层契约：① 仓储必须收到**站点内容条目集合**与**精确时间点**；② 每个条目取版本号最大的
 * 一行（仓储按 version desc 返回，取首行）；③ 时间点之后才创建 / 尚无版本的条目不计入快照但计入
 * 「当时还没创建」的差额；④ 条目级字段优先取版本快照、缺失时退回条目当前值；⑤ 不写库。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContentTimeMachineServiceTest {

    @Mock ContentItemRepository itemRepo;
    @Mock ContentVersionRepository versionRepo;
    @Mock ContentTypeRepository ctRepo;

    private final UUID site = UUID.randomUUID();
    private final UUID typeId = UUID.randomUUID();
    private final Instant at = Instant.parse("2026-09-18T10:00:00Z");

    private ContentTimeMachineService service() {
        return new ContentTimeMachineService(itemRepo, versionRepo, ctRepo, ActorNameResolver.passThrough());
    }

    private ContentItemEntity item(UUID id, String title, Instant createdAt) {
        ContentItemEntity item = new ContentItemEntity();
        item.setId(id);
        item.setSiteId(site);
        item.setContentTypeId(typeId);
        item.setTitle(title);
        item.setRoutePath("/now/" + title);
        item.setTemplatePath("/t/now");
        item.setStatus(ContentItemEntity.STATUS_PUBLISHED);
        item.setCreatedBy("editor");
        item.setCreatedAt(createdAt);
        item.setUpdatedAt(createdAt);
        return item;
    }

    private ContentVersionEntity version(UUID itemId, int version, Instant createdAt, String fields) {
        ContentVersionEntity v = ContentVersionEntity.create(itemId, version, fields, 1, "editor", "备注");
        v.setCreatedAt(createdAt);
        return v;
    }

    private ContentTypeEntity type(String code, String name) {
        ContentTypeEntity type = new ContentTypeEntity();
        type.setId(typeId);
        type.setSiteId(site);
        type.setCode(code);
        type.setName(name);
        return type;
    }

    @Test
    void picksHighestVersionPerItemAndPassesExactInstantToRepository() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(itemRepo.findBySiteId(site)).thenReturn(List.of(
            item(a, "甲", at.minus(10, ChronoUnit.DAYS)), item(b, "乙", at.minus(5, ChronoUnit.DAYS))));
        when(ctRepo.findBySiteIdOrderByCodeAsc(site)).thenReturn(List.of(type("article", "文章")));
        // 仓储契约：按 (contentItemId asc, version desc) 返回
        when(versionRepo.findVersionsNotAfter(any(), eq(at))).thenReturn(List.of(
            version(a, 3, at.minus(1, ChronoUnit.HOURS), "{\"body\":\"甲-最新\"}"),
            version(a, 2, at.minus(2, ChronoUnit.DAYS), "{\"body\":\"甲-旧\"}"),
            version(b, 5, at.minus(30, ChronoUnit.MINUTES), "{\"body\":\"乙-最新\"}"),
            version(b, 4, at.minus(3, ChronoUnit.DAYS), "{\"body\":\"乙-旧\"}")));

        ContentTimeMachineService.Snapshot snapshot = service().at(site, at);

        assertEquals(at, snapshot.at());
        assertEquals(2, snapshot.total());
        assertEquals(0, snapshot.notYetCreated());
        // 每个条目取版本号最大的一行，版本号更小的历史行不得成为快照
        assertEquals(List.of(3, 5), snapshot.items().stream().map(ContentTimeMachineService.SnapshotItem::version).sorted().toList());
        var byTitle = snapshot.items().stream().collect(
            java.util.stream.Collectors.toMap(ContentTimeMachineService.SnapshotItem::title, i -> i));
        assertEquals("{\"body\":\"甲-最新\"}", byTitle.get("甲").fieldValues());
        assertEquals("{\"body\":\"乙-最新\"}", byTitle.get("乙").fieldValues());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<UUID>> ids = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(versionRepo).findVersionsNotAfter(ids.capture(), eq(at));
        assertEquals(java.util.Set.of(a, b), java.util.Set.copyOf(ids.getValue()));
    }

    @Test
    void itemsWithoutVersionAtInstantAreExcludedButCountedAsNotYetCreated() {
        UUID existing = UUID.randomUUID();
        UUID later = UUID.randomUUID();
        when(itemRepo.findBySiteId(site)).thenReturn(List.of(
            item(existing, "已有", at.minus(2, ChronoUnit.DAYS)),
            item(later, "后来创建", at.plus(1, ChronoUnit.DAYS))));
        when(ctRepo.findBySiteIdOrderByCodeAsc(site)).thenReturn(List.of(type("article", "文章")));
        when(versionRepo.findVersionsNotAfter(any(), eq(at))).thenReturn(List.of(
            version(existing, 1, at.minus(2, ChronoUnit.DAYS), "{}")));

        ContentTimeMachineService.Snapshot snapshot = service().at(site, at);

        assertEquals(1, snapshot.total());
        assertEquals(1, snapshot.notYetCreated());
        assertEquals("已有", snapshot.items().get(0).title());
        // earliestContentCreatedAt 覆盖**全部**当前条目，界面据此判断「时间点早于内容创建时间」
        assertEquals(at.minus(2, ChronoUnit.DAYS), snapshot.earliestContentCreatedAt());
    }

    @Test
    void timePointBeforeAnyContentReturnsEmptySnapshotWithCreationHint() {
        when(itemRepo.findBySiteId(site)).thenReturn(List.of(
            item(UUID.randomUUID(), "后来的内容", at.plus(3, ChronoUnit.DAYS))));
        when(ctRepo.findBySiteIdOrderByCodeAsc(site)).thenReturn(List.of());
        when(versionRepo.findVersionsNotAfter(any(), eq(at))).thenReturn(List.of());

        ContentTimeMachineService.Snapshot snapshot = service().at(site, at);

        assertEquals(0, snapshot.total());
        assertEquals(1, snapshot.notYetCreated());
        assertTrue(snapshot.items().isEmpty());
        // 该时间点早于内容创建时间——界面提示的依据
        assertTrue(snapshot.earliestContentCreatedAt().isAfter(at));
    }

    @Test
    void emptySiteSkipsVersionQuery() {
        when(itemRepo.findBySiteId(site)).thenReturn(List.of());

        ContentTimeMachineService.Snapshot snapshot = service().at(site, at);

        assertEquals(0, snapshot.total());
        assertEquals(0, snapshot.notYetCreated());
        assertNull(snapshot.earliestContentCreatedAt());
        verify(versionRepo, never()).findVersionsNotAfter(any(), any());
    }

    @Test
    void itemLevelFieldsPreferVersionSnapshotAndFallBackToCurrentItem() {
        UUID snapshotted = UUID.randomUUID();
        UUID legacy = UUID.randomUUID();
        when(itemRepo.findBySiteId(site)).thenReturn(List.of(
            item(snapshotted, "当前标题", at.minus(2, ChronoUnit.DAYS)),
            item(legacy, "兼容标题", at.minus(2, ChronoUnit.DAYS))));
        when(ctRepo.findBySiteIdOrderByCodeAsc(site)).thenReturn(List.of(type("article", "文章")));

        ContentVersionEntity withSnapshot = version(snapshotted, 2, at.minus(1, ChronoUnit.HOURS), "{}")
            .withItemSnapshot("当时标题", "/then/route", "/t/then");
        // V100 之前的历史行没有条目级快照（title/routePath/templatePath 为 null），必须退回条目当前值
        ContentVersionEntity withoutSnapshot = version(legacy, 1, at.minus(1, ChronoUnit.HOURS), "{}");
        when(versionRepo.findVersionsNotAfter(any(), eq(at)))
            .thenReturn(List.of(withSnapshot, withoutSnapshot));

        var byId = service().at(site, at).items().stream()
            .collect(java.util.stream.Collectors.toMap(ContentTimeMachineService.SnapshotItem::id, i -> i));

        assertEquals("当时标题", byId.get(snapshotted).title());
        assertEquals("/then/route", byId.get(snapshotted).routePath());
        assertEquals("/t/then", byId.get(snapshotted).templatePath());
        assertEquals("兼容标题", byId.get(legacy).title());
        assertEquals("/now/兼容标题", byId.get(legacy).routePath());
    }

    @Test
    void resolvesContentTypeLabelsAndStatusAndAiActorName() {
        UUID id = UUID.randomUUID();
        when(itemRepo.findBySiteId(site)).thenReturn(List.of(item(id, "标题", at.minus(1, ChronoUnit.DAYS))));
        when(ctRepo.findBySiteIdOrderByCodeAsc(site)).thenReturn(List.of(type("video", "视频")));
        when(versionRepo.findVersionsNotAfter(any(), eq(at)))
            .thenReturn(List.of(version(id, 1, at.minus(1, ChronoUnit.DAYS), "{}")));

        ContentTimeMachineService.SnapshotItem row = service().at(site, at).items().get(0);

        assertEquals("video", row.contentTypeCode());
        assertEquals("视频", row.contentTypeName());
        // 状态未被版本化，接口如实以 currentStatus 命名（不承诺等于该时刻的状态）
        assertEquals(ContentItemEntity.STATUS_PUBLISHED, row.currentStatus());
        assertEquals("editor", row.createdBy());
        assertEquals("editor", row.createdByName());
        assertEquals(1, row.modelVersion());
    }

    @Test
    void missingContentModelYieldsNullLabelsInsteadOfFailing() {
        UUID id = UUID.randomUUID();
        when(itemRepo.findBySiteId(site)).thenReturn(List.of(item(id, "标题", at.minus(1, ChronoUnit.DAYS))));
        when(ctRepo.findBySiteIdOrderByCodeAsc(site)).thenReturn(List.of());
        when(versionRepo.findVersionsNotAfter(any(), eq(at)))
            .thenReturn(List.of(version(id, 1, at.minus(1, ChronoUnit.DAYS), "{}")));

        ContentTimeMachineService.SnapshotItem row = service().at(site, at).items().get(0);

        assertNull(row.contentTypeCode());
        assertNull(row.contentTypeName());
    }

    @Test
    void missingInstantDefaultsToServerNow() {
        when(itemRepo.findBySiteId(site)).thenReturn(List.of());

        Instant before = Instant.now().minus(1, ChronoUnit.SECONDS);
        Instant resolved = service().at(site, null).at();

        assertTrue(resolved.isAfter(before));
    }

    @Test
    void doesNotWriteAnything() {
        when(itemRepo.findBySiteId(site)).thenReturn(List.of());
        service().at(site, at);
        // 只读：不允许任何保存/删除（浏览历史不得影响当前数据，FR-CV-VER-009 验收项）
        verify(itemRepo, never()).save(any());
        verify(itemRepo, never()).deleteById(any());
        verify(versionRepo, never()).save(any());
        verify(versionRepo, never()).deleteById(any());
    }
}
