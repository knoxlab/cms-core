package com.powercms.cms.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.audit.AuditService;
import com.powercms.cms.content.compose.ClassComposeService;
import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.entity.ContentVersionEntity;
import com.powercms.cms.content.repo.CategoryRepository;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.content.repo.ContentVersionRepository;
import com.powercms.cms.identity.ActorNameResolver;
import com.powercms.cms.identity.permission.ObjectPermissionService;
import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.cms.model.repo.FieldDefinitionRepository;
import com.powercms.cms.outbox.OutboxService;
import com.powercms.cms.sitefolder.repo.SiteFolderRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FR-EP-BATCH-002：批量创建分集必须校验「所属季 + 集号」唯一，且重复时**整批拒绝、不产生半成品数据**。
 *
 * <p>对应域 11 缺陷 `DEF-11-03`（`TC-M53-017` 步骤 3：季内已有第 1 集时仍能重复创建）。</p>
 */
@ExtendWith(MockitoExtension.class)
class ContentServiceBatchEpisodesTest {

    @Mock ContentItemRepository itemRepo;
    @Mock ContentVersionRepository versionRepo;
    @Mock ContentTypeRepository ctRepo;
    @Mock FieldDefinitionRepository fieldRepo;
    @Mock ClassComposeService classCompose;
    @Mock AuditService audit;
    @Mock AiDirectPublishGate gate;

    private ContentService service;
    private final UUID site = UUID.randomUUID();
    private final UUID seasonId = UUID.randomUUID();
    private final UUID episodeTypeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ContentService(itemRepo, mock(CategoryRepository.class), versionRepo, ctRepo,
            fieldRepo, mock(SiteFolderRepository.class), classCompose, null,
            new ObjectMapper(), mock(OutboxService.class), mock(ApplicationEventPublisher.class),
            mock(ObjectPermissionService.class), audit, gate,
            ActorNameResolver.passThrough(), null);
        // 组合字段与媒体引用校验都是「改写/校验后原样返回」，直通以免把 field_values 抹成 null。
        lenient().when(classCompose.applyOnSave(any(), any(), any())).thenAnswer(inv -> inv.getArgument(2));
        lenient().when(fieldRepo.findByContentTypeIdOrderBySortOrder(any())).thenReturn(List.of());
    }

    /** 站点内的 video_season / video_episode 两个模型（均为 SHARED，不需要页面路径）。 */
    private void stubSeason() {
        ContentTypeEntity seasonType = new ContentTypeEntity();
        seasonType.setId(UUID.randomUUID());
        seasonType.setSiteId(site);
        seasonType.setObjectType("SHARED");

        ContentItemEntity season = new ContentItemEntity();
        season.setId(seasonId);
        season.setSiteId(site);
        season.setContentTypeId(seasonType.getId());
        season.setTitle("第 1 季");
        when(itemRepo.findById(seasonId)).thenReturn(Optional.of(season));

        ContentTypeEntity episodeType = new ContentTypeEntity();
        episodeType.setId(episodeTypeId);
        episodeType.setSiteId(site);
        episodeType.setObjectType("SHARED");
        when(ctRepo.findBySiteIdAndCodeOrderByVersionAsc(site, "video_episode"))
            .thenReturn(List.of(episodeType));
    }

    /** 已存在的分集条目（不含字段值 stub，避免把 when(...) 嵌进另一个 when(...) 的入参）。 */
    private ContentItemEntity episodeItem(UUID itemSite, String title) {
        ContentItemEntity item = new ContentItemEntity();
        item.setId(UUID.randomUUID());
        item.setSiteId(itemSite);
        item.setContentTypeId(episodeTypeId);
        item.setTitle(title);
        return item;
    }

    /** 让该条目在当前版本里报告「所属季 season、集号 number」。 */
    private ContentItemEntity episodeInSeason(UUID itemSite, UUID season, int number) {
        ContentItemEntity item = episodeItem(itemSite, "第 " + number + " 集");
        stubEpisodeFields(item.getId(),
            "{\"episode_number_i\":" + number + ",\"season_ref_s\":\"" + season + "\"}");
        return item;
    }

    private void stubEpisodeFields(UUID itemId, String fieldValues) {
        when(versionRepo.findByContentItemIdOrderByVersionDesc(itemId))
            .thenReturn(List.of(ContentVersionEntity.create(itemId, 1, fieldValues, 1, "system", "初始版本")));
    }

    private void stubCreatePipeline() {
        when(itemRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(ctRepo.findById(episodeTypeId)).thenReturn(Optional.of(episodeType()));
    }

    private ContentTypeEntity episodeType() {
        ContentTypeEntity type = new ContentTypeEntity();
        type.setId(episodeTypeId);
        type.setSiteId(site);
        type.setObjectType("SHARED");
        return type;
    }

    @Test
    void batchCreateRejectsSeasonEpisodeNumberAlreadyTaken() {
        stubSeason();
        ContentItemEntity taken = episodeInSeason(site, seasonId, 1);
        when(itemRepo.findByContentTypeId(episodeTypeId)).thenReturn(List.of(taken));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.batchCreateEpisodes(site, seasonId, 1, 3, null, "system"));

        assertEquals(ErrorCodes.CONFLICT, ex.getCode());
        assertTrue(ex.getMessage().contains("第 1 集"), "应指明冲突集号: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("未创建任何分集"), "应表明整批未创建: " + ex.getMessage());
        // 关键：先整批校验再创建，重复时不得写入任何条目（不产生半成品数据）
        verify(itemRepo, never()).save(any());
    }

    @Test
    void batchCreateRejectsWholeRangeWhenAnyNumberCollides() {
        stubSeason();
        ContentItemEntity third = episodeInSeason(site, seasonId, 3);
        ContentItemEntity fourth = episodeInSeason(site, seasonId, 4);
        when(itemRepo.findByContentTypeId(episodeTypeId)).thenReturn(List.of(third, fourth));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.batchCreateEpisodes(site, seasonId, 2, 3, null, "system"));

        assertEquals(ErrorCodes.CONFLICT, ex.getCode());
        assertTrue(ex.getMessage().contains("第 3"), "应列出全部冲突集号: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("第 4"), "应列出全部冲突集号: " + ex.getMessage());
        verify(itemRepo, never()).save(any());
    }

    @Test
    void batchCreateAllowsEpisodeNumbersOfOtherSeasonOrSite() {
        stubSeason();
        stubCreatePipeline();
        UUID otherSeason = UUID.randomUUID();
        UUID otherSite = UUID.randomUUID();
        ContentItemEntity otherSeasonEpisode = episodeInSeason(site, otherSeason, 1);      // 同站点、别的季
        ContentItemEntity otherSiteEpisode = episodeItem(otherSite, "第 1 集");            // 别的站点（读字段值前即被跳过）
        ContentItemEntity farEpisode = episodeInSeason(site, seasonId, 9);                 // 本季第 9 集，不在请求区间
        when(itemRepo.findByContentTypeId(episodeTypeId))
            .thenReturn(List.of(otherSeasonEpisode, otherSiteEpisode, farEpisode));

        List<ContentService.ContentInfo> created =
            service.batchCreateEpisodes(site, seasonId, 1, 2, null, "system");

        assertEquals(2, created.size());
        ArgumentCaptor<ContentVersionEntity> captor = ArgumentCaptor.forClass(ContentVersionEntity.class);
        verify(versionRepo, times(2)).save(captor.capture());
        List<String> values = new ArrayList<>();
        captor.getAllValues().forEach(v -> values.add(v.getFieldValues()));
        assertTrue(values.get(0).contains("\"episode_number_i\":1"), values.get(0));
        assertTrue(values.get(1).contains("\"episode_number_i\":2"), values.get(1));
        assertTrue(values.get(0).contains(seasonId.toString()), values.get(0));
    }

    @Test
    void batchCreateIgnoresEpisodesWithoutUsableFieldValues() {
        stubSeason();
        stubCreatePipeline();
        // ① 没有版本行；② 字段值不是 JSON；③ 季字段不是文本
        ContentItemEntity noVersion = episodeItem(site, "缺版本");
        when(versionRepo.findByContentItemIdOrderByVersionDesc(noVersion.getId())).thenReturn(List.of());
        ContentItemEntity brokenJson = episodeItem(site, "坏 JSON");
        stubEpisodeFields(brokenJson.getId(), "{not-json");
        ContentItemEntity numericSeason = episodeItem(site, "季字段非文本");
        stubEpisodeFields(numericSeason.getId(), "{\"episode_number_i\":1,\"season_ref_s\":123}");
        when(itemRepo.findByContentTypeId(episodeTypeId))
            .thenReturn(List.of(noVersion, brokenJson, numericSeason));

        List<ContentService.ContentInfo> created =
            service.batchCreateEpisodes(site, seasonId, 1, 1, null, "system");

        assertEquals(1, created.size());
    }

    @Test
    void batchCreateKeepsCountGuard() {
        // 数量校验先于任何仓储访问，故此处不 stub 任何依赖
        assertThrows(BusinessException.class,
            () -> service.batchCreateEpisodes(site, seasonId, 1, 0, null, "system"));
        assertThrows(BusinessException.class,
            () -> service.batchCreateEpisodes(site, seasonId, 1, 201, null, "system"));
    }
}
