package com.powercms.cms.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.entity.ContentVersionEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.content.repo.ContentVersionRepository;
import com.powercms.cms.media.MediaAssetDeletedEvent;
import com.powercms.cms.media.MediaAssetReadyEvent;
import com.powercms.cms.media.MediaAssetRegisteredEvent;
import com.powercms.cms.media.MediaContentItemBridge;
import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.cms.search.AdminSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaContentItemSyncServiceTest {

    @Mock MediaContentItemBridge bridge;
    @Mock ContentItemRepository itemRepo;
    @Mock ContentVersionRepository versionRepo;
    @Mock ContentTypeRepository typeRepo;
    @Mock com.powercms.cms.model.repo.FieldDefinitionRepository fieldRepo;
    @Mock AdminSearchService search;
    @Mock com.powercms.cms.model.repo.ContentTypeLifecycleRepository lifecycleRepo;
    @Mock com.powercms.cms.model.sandbox.ScriptSandboxService sandbox;

    MediaContentItemSyncService service;

    UUID siteId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    UUID mediaTypeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new MediaContentItemSyncService(bridge, itemRepo, versionRepo, typeRepo, fieldRepo, search,
            new ObjectMapper(), lifecycleRepo, sandbox);
        // 无 WEBHOOK 钩子时脚本查找返回空，字段构建不受影响
        org.mockito.Mockito.lenient().when(lifecycleRepo.findByContentTypeIdAndEvent(any(), any()))
            .thenReturn(java.util.Optional.empty());
        // 缺省无字段定义：钩子补丁白名单为空
        org.mockito.Mockito.lenient().when(fieldRepo.findByContentTypeIdOrderBySortOrder(any()))
            .thenReturn(java.util.List.of());
    }

    private ContentTypeEntity mediaType() {
        ContentTypeEntity ct = new ContentTypeEntity();
        ct.setId(mediaTypeId);
        ct.setSiteId(siteId);
        ct.setCode("media_video");
        ct.setName("视频");
        ct.setObjectType("MEDIA");
        ct.setVersion(3);
        return ct;
    }

    private MediaContentItemBridge.AssetDetail detail(UUID contentItemId) {
        return new MediaContentItemBridge.AssetDetail(assetId, siteId, null, "clip.mp4",
            "video/mp4", 1024, "abc123", "knx://ep/42", "PROCESSING", "video",
            mediaTypeId, "{\"posterPath\":\"/posters/42.jpg\",\"width\":1920,\"height\":1080}", contentItemId,
            null, null, null);
    }

    @Test
    void registeredEventCreatesContentItemAndLinks() {
        when(bridge.findById(assetId)).thenReturn(Optional.of(detail(null)));
        when(typeRepo.findById(mediaTypeId)).thenReturn(Optional.of(mediaType()));
        when(itemRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.onRegistered(new MediaAssetRegisteredEvent(siteId, assetId));

        verify(itemRepo, times(2)).save(any());
        verify(versionRepo).save(any());
        verify(bridge).linkContentItem(eq(assetId), any());
        verify(search).indexItem(any());
    }

    @Test
    void createdContentItemCarriesSystemFieldValues() {
        when(bridge.findById(assetId)).thenReturn(Optional.of(detail(null)));
        when(typeRepo.findById(mediaTypeId)).thenReturn(Optional.of(mediaType()));
        when(itemRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.onRegistered(new MediaAssetRegisteredEvent(siteId, assetId));

        ArgumentCaptor<ContentItemEntity> itemCaptor = ArgumentCaptor.forClass(ContentItemEntity.class);
        verify(itemRepo, times(2)).save(itemCaptor.capture());
        assertThat(itemCaptor.getAllValues().get(0).getSiteId()).isEqualTo(siteId);
        assertThat(itemCaptor.getAllValues().get(0).getContentTypeId()).isEqualTo(mediaTypeId);
        assertThat(itemCaptor.getAllValues().get(0).getTitle()).isEqualTo("clip.mp4");
        assertThat(itemCaptor.getAllValues().get(0).getStatus()).isEqualTo(ContentItemEntity.STATUS_DRAFT);

        ArgumentCaptor<ContentVersionEntity> verCaptor = ArgumentCaptor.forClass(ContentVersionEntity.class);
        verify(versionRepo).save(verCaptor.capture());
        String values = verCaptor.getValue().getFieldValues();
        assertThat(values).contains("\"fileName_s\":\"clip.mp4\"")
            .contains("\"mimeType_s\":\"video/mp4\"")
            .contains("\"fileSize_i\":1024")
            .contains("\"storageKey_s\":\"knx://ep/42\"")
            .contains("\"kind_s\":\"video\"")
            .contains("\"status_s\":\"PROCESSING\"")
            .contains("\"thumbnailUrl_s\":\"/posters/42.jpg\"")
            .contains("\"posterUrl_s\":\"/posters/42.jpg\"")
            .contains("\"width_i\":1920")
            .contains("\"height_i\":1080");
    }

    /** FR-AI-MEDIA-001：转写稿随资产详情落 MEDIA 内容项字段（transcript_t/lang/model）。 */
    @Test
    void contentItemCarriesTranscriptFieldsWhenPresent() {
        when(bridge.findById(assetId)).thenReturn(Optional.of(
            new MediaContentItemBridge.AssetDetail(assetId, siteId, null, "clip.mp4",
                "video/mp4", 1024, "abc123", "knx://ep/42", "ACTIVE", "video",
                mediaTypeId, null, null,
                "欢迎使用 PowerCMS", "zh", "knox-whisper")));
        when(typeRepo.findById(mediaTypeId)).thenReturn(Optional.of(mediaType()));
        when(itemRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.onRegistered(new MediaAssetRegisteredEvent(siteId, assetId));

        ArgumentCaptor<ContentVersionEntity> verCaptor = ArgumentCaptor.forClass(ContentVersionEntity.class);
        verify(versionRepo).save(verCaptor.capture());
        String values = verCaptor.getValue().getFieldValues();
        assertThat(values).contains("\"transcript_t\":\"欢迎使用 PowerCMS\"")
            .contains("\"transcriptLang_s\":\"zh\"")
            .contains("\"transcriptModel_s\":\"knox-whisper\"");
    }

    @Test
    void syncSkipsAssetWithoutMediaType() {
        when(bridge.findById(assetId)).thenReturn(Optional.of(
            new MediaContentItemBridge.AssetDetail(assetId, siteId, null, "x.mp4", "video/mp4",
                1, "h", "k", "ACTIVE", "video", null, null, null, null, null, null)));

        service.onRegistered(new MediaAssetRegisteredEvent(siteId, assetId));

        verify(itemRepo, never()).save(any());
        verify(versionRepo, never()).save(any());
    }

    @Test
    void syncSkipsNonMediaContentType() {
        ContentTypeEntity ct = new ContentTypeEntity();
        ct.setId(mediaTypeId);
        ct.setObjectType("PAGE");
        when(bridge.findById(assetId)).thenReturn(Optional.of(detail(null)));
        when(typeRepo.findById(mediaTypeId)).thenReturn(Optional.of(ct));

        service.onRegistered(new MediaAssetRegisteredEvent(siteId, assetId));

        verify(itemRepo, never()).save(any());
    }

    @Test
    void readyEventAppendsNewVersionOnExistingContent() {
        UUID contentItemId = UUID.randomUUID();
        ContentItemEntity item = new ContentItemEntity();
        item.setId(contentItemId);
        item.setSiteId(siteId);
        item.setTitle("old");
        item.setCurrentVersion(1);
        when(bridge.findById(assetId)).thenReturn(Optional.of(detail(contentItemId)));
        when(typeRepo.findById(mediaTypeId)).thenReturn(Optional.of(mediaType()));
        when(itemRepo.findById(contentItemId)).thenReturn(Optional.of(item));
        when(itemRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.onReady(new MediaAssetReadyEvent(siteId, assetId));

        verify(versionRepo).save(argThat(v -> v.getVersion() == 2
            && v.getContentItemId().equals(contentItemId)));
        verify(itemRepo).save(item);
        verify(bridge, never()).linkContentItem(any(), any());
        verify(search).indexItem(contentItemId);
    }

    @Test
    void readyRebuildsContentWhenLinkIsDangling() {
        UUID contentItemId = UUID.randomUUID();
        when(bridge.findById(assetId)).thenReturn(Optional.of(detail(contentItemId)));
        when(typeRepo.findById(mediaTypeId)).thenReturn(Optional.of(mediaType()));
        when(itemRepo.findById(contentItemId)).thenReturn(Optional.empty());
        when(itemRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.onReady(new MediaAssetReadyEvent(siteId, assetId));

        // 先解除悬空关联，再重建并回填新内容项 ID
        verify(bridge, times(2)).linkContentItem(eq(assetId), any());
    }

    @Test
    void deletedEventRemovesLinkedContentItemAndVersions() {
        UUID contentItemId = UUID.randomUUID();
        ContentVersionEntity v = ContentVersionEntity.create(contentItemId, 1, "{}", 3, "media-sync", "n");
        when(versionRepo.findByContentItemIdOrderByVersionDesc(contentItemId)).thenReturn(List.of(v));

        service.onDeleted(new MediaAssetDeletedEvent(siteId, assetId, contentItemId));

        verify(versionRepo).delete(v);
        verify(itemRepo).deleteById(contentItemId);
    }

    @Test
    void deletedEventSkipsWhenNoLink() {
        service.onDeleted(new MediaAssetDeletedEvent(siteId, assetId, null));

        verify(itemRepo, never()).deleteById(any());
        verify(versionRepo, never()).delete(any());
    }

    @Test
    void backfillCreatesContentForClassifiedAssets() {
        MediaContentItemBridge.AssetDetail missing = detail(null);
        when(bridge.findAllClassifiedWithoutContentItem()).thenReturn(List.of(missing));
        when(bridge.findById(assetId)).thenReturn(Optional.of(missing));
        when(typeRepo.findById(mediaTypeId)).thenReturn(Optional.of(mediaType()));
        when(itemRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.backfill();

        verify(itemRepo, times(2)).save(any());
        verify(bridge).linkContentItem(eq(assetId), any());
        verify(search).indexItem(any());
    }

    // ---- WEBHOOK 生命周期钩子（FR-CM-MEDIA-012）----

    private com.powercms.cms.model.entity.ContentTypeLifecycleEntity enabledHook(String script) {
        return enabledHook(script, null);
    }

    private com.powercms.cms.model.entity.ContentTypeLifecycleEntity enabledHook(String script, String listenEvents) {
        var hook = new com.powercms.cms.model.entity.ContentTypeLifecycleEntity();
        hook.setContentTypeId(mediaTypeId);
        hook.setEvent("WEBHOOK");
        hook.setScriptLang("GROOVY");
        hook.setScriptContent(script);
        hook.setListenEvents(listenEvents);
        hook.setEnabled(true);
        return hook;
    }

    private com.powercms.cms.model.entity.FieldDefinitionEntity field(String code) {
        var f = new com.powercms.cms.model.entity.FieldDefinitionEntity();
        f.setCode(code);
        return f;
    }

    @Test
    void webhookHookReceivesCallbackMediaAndWritesMetadataFields() {
        when(bridge.findById(assetId)).thenReturn(Optional.of(detail(null)));
        when(typeRepo.findById(mediaTypeId)).thenReturn(Optional.of(mediaType()));
        when(itemRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(lifecycleRepo.findByContentTypeIdAndEvent(mediaTypeId, "WEBHOOK"))
            .thenReturn(Optional.of(enabledHook("return [width_i: 1920, format_s: 'mp4']")));
        when(sandbox.evaluate(any(), eq("GROOVY"), any()))
            .thenReturn(Map.of("width_i", 1920, "format_s", "mp4"));
        when(fieldRepo.findByContentTypeIdOrderBySortOrder(mediaTypeId))
            .thenReturn(List.of(field("width_i"), field("format_s"), field("thumbnailUrl_s"), field("posterUrl_s")));

        service.onRegistered(new MediaAssetRegisteredEvent(siteId, assetId));

        ArgumentCaptor<ContentVersionEntity> verCaptor = ArgumentCaptor.forClass(ContentVersionEntity.class);
        verify(versionRepo).save(verCaptor.capture());
        String values = verCaptor.getValue().getFieldValues();
        // 数值补丁保留数值类型（不字符串化），格式字段按字符串写入
        assertThat(values).contains("\"width_i\":1920")
            .contains("\"format_s\":\"mp4\"");
    }

    @Test
    void webhookHookIsPassedParsedMediaBinding() {
        when(bridge.findById(assetId)).thenReturn(Optional.of(detail(null)));
        when(typeRepo.findById(mediaTypeId)).thenReturn(Optional.of(mediaType()));
        when(itemRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(lifecycleRepo.findByContentTypeIdAndEvent(mediaTypeId, "WEBHOOK"))
            .thenReturn(Optional.of(enabledHook("return [:]")));
        when(sandbox.evaluate(any(), eq("GROOVY"), any())).thenReturn(Map.of());

        service.onRegistered(new MediaAssetRegisteredEvent(siteId, assetId));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> bindingCaptor = ArgumentCaptor.forClass(Map.class);
        verify(sandbox).evaluate(any(), eq("GROOVY"), bindingCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> media = (Map<String, Object>) bindingCaptor.getValue().get("media");
        assertThat(media).containsEntry("width", 1920).containsEntry("height", 1080)
            .containsEntry("posterPath", "/posters/42.jpg");
        assertThat(bindingCaptor.getValue()).containsKey("fields").containsKey("asset");
    }

    @Test
    void webhookHookPatchDropsFieldsNotDefinedOnType() {
        when(bridge.findById(assetId)).thenReturn(Optional.of(detail(null)));
        when(typeRepo.findById(mediaTypeId)).thenReturn(Optional.of(mediaType()));
        when(itemRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(lifecycleRepo.findByContentTypeIdAndEvent(mediaTypeId, "WEBHOOK"))
            .thenReturn(Optional.of(enabledHook("return [custom_s: 'x', injected_s: 'y']")));
        when(sandbox.evaluate(any(), eq("GROOVY"), any()))
            .thenReturn(Map.of("custom_s", "x", "injected_s", "y"));
        // 仅 custom_s 在模型中定义；injected_s 未知 → 丢弃
        when(fieldRepo.findByContentTypeIdOrderBySortOrder(mediaTypeId))
            .thenReturn(List.of(field("width_i"), field("custom_s")));

        service.onRegistered(new MediaAssetRegisteredEvent(siteId, assetId));

        ArgumentCaptor<ContentVersionEntity> verCaptor = ArgumentCaptor.forClass(ContentVersionEntity.class);
        verify(versionRepo).save(verCaptor.capture());
        String values = verCaptor.getValue().getFieldValues();
        // custom_s 为已定义字段，即使字段值先前不存在也会由钩子写入
        assertThat(values).contains("\"custom_s\":\"x\"")
            .doesNotContain("injected_s");
    }

    @Test
    void webhookHookSkipsWhenEventNotInListenWhitelist() {
        when(bridge.findById(assetId)).thenReturn(Optional.of(detail(null)));
        when(typeRepo.findById(mediaTypeId)).thenReturn(Optional.of(mediaType()));
        when(itemRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // 钩子只监听 poster.obtained，本次是 metadata.obtained → 不执行脚本
        when(lifecycleRepo.findByContentTypeIdAndEvent(mediaTypeId, "WEBHOOK"))
            .thenReturn(Optional.of(enabledHook("return [width_i: 1]", "poster.obtained")));

        service.onReady(new MediaAssetReadyEvent(siteId, assetId, "metadata.obtained"));

        verify(sandbox, never()).evaluate(any(), any(), any());
    }

    @Test
    void webhookHookRunsWhenEventMatchesListenWhitelistAndPassesEvent() {
        when(bridge.findById(assetId)).thenReturn(Optional.of(detail(null)));
        when(typeRepo.findById(mediaTypeId)).thenReturn(Optional.of(mediaType()));
        when(itemRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(lifecycleRepo.findByContentTypeIdAndEvent(mediaTypeId, "WEBHOOK"))
            .thenReturn(Optional.of(enabledHook("return [coverReady_b: true]", "poster.obtained")));
        when(sandbox.evaluate(any(), eq("GROOVY"), any())).thenReturn(Map.of("coverReady_b", true));
        when(fieldRepo.findByContentTypeIdOrderBySortOrder(mediaTypeId))
            .thenReturn(List.of(field("coverReady_b"), field("width_i")));

        service.onReady(new MediaAssetReadyEvent(siteId, assetId, "poster.obtained"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> bindingCaptor = ArgumentCaptor.forClass(Map.class);
        verify(sandbox).evaluate(any(), eq("GROOVY"), bindingCaptor.capture());
        // 脚本收到实际 Knox 事件名（而非固定 "WEBHOOK"）
        assertThat(bindingCaptor.getValue().get("event")).isEqualTo("poster.obtained");
    }
}
