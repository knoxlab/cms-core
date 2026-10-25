package com.powercms.cms.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.audit.AuditService;
import com.powercms.cms.media.entity.MediaAssetEntity;
import com.powercms.cms.media.repo.MediaAssetRepository;
import com.powercms.cms.media.repo.MediaVersionRepository;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FR-AGT-MEDIA-003/005：媒体元数据编辑与目录搬移的领域口径——
 * expected_version 乐观锁、空串清空、AI Alt 修订标记、站点域边界。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MediaServiceMetadataTest {

    @Mock MediaAssetRepository assetRepo;
    @Mock MediaVersionRepository versionRepo;
    @Mock ContentTypeRepository typeRepo;
    @Mock ApplicationEventPublisher events;
    @Mock AuditService audit;

    private MediaService service;
    private final UUID siteId = UUID.randomUUID();
    private final UUID assetId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new MediaService(assetRepo, versionRepo, typeRepo, events, audit, new ObjectMapper());
    }

    @Test
    void metadataOfRejectsAssetOutsideSite() {
        when(assetRepo.findByIdAndSiteId(assetId, siteId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.metadataOf(siteId, assetId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("媒体资产不存在")
            .extracting(e -> ((BusinessException) e).getCode())
            .isEqualTo(ErrorCodes.NOT_FOUND);
    }

    @Test
    void updateRejectsVersionDrift() {
        when(assetRepo.findByIdAndSiteId(assetId, siteId)).thenReturn(Optional.of(asset(3)));

        assertThatThrownBy(() -> service.updateMetadata(siteId, assetId,
            new MediaService.MetadataPatch("新标题", null, null, null, null), 2, "actor"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("版本已变化")
            .extracting(e -> ((BusinessException) e).getCode())
            .isEqualTo(ErrorCodes.CONFLICT);
        verify(assetRepo, never()).save(any());
    }

    @Test
    void updateAppliesFieldDiffAndClearsAiAltTextMarker() {
        MediaAssetEntity asset = asset(3);
        asset.setAltText("AI 生成的描述");
        asset.setAltTextModel("gpt-4o-mini");
        asset.setCopyright("旧版权");
        when(assetRepo.findByIdAndSiteId(assetId, siteId)).thenReturn(Optional.of(asset));

        MediaService.MetadataResult result = service.updateMetadata(siteId, assetId,
            new MediaService.MetadataPatch("人工标题", "人工修订后的描述", null, null, null), 3, "actor");

        assertThat(result.changedKeys()).containsExactlyInAnyOrder("title", "alt_text");
        assertThat(result.aiAltTextRevised()).isTrue();
        assertThat(asset.getAltTextModel()).isNull();
        assertThat(asset.getAltText()).isEqualTo("人工修订后的描述");
        assertThat(asset.getTitle()).isEqualTo("人工标题");
        assertThat(asset.getCopyright()).isEqualTo("旧版权");
        verify(assetRepo).save(asset);
        verify(audit).write(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void blankValueClearsFieldAndNoDiffSkipsWrite() {
        MediaAssetEntity asset = asset(1);
        asset.setTags("a,b");
        when(assetRepo.findByIdAndSiteId(assetId, siteId)).thenReturn(Optional.of(asset));

        MediaService.MetadataResult cleared = service.updateMetadata(siteId, assetId,
            new MediaService.MetadataPatch(null, null, null, "", null), 1, "actor");
        assertThat(cleared.changedKeys()).containsExactly("tags");
        assertThat(asset.getTags()).isNull();

        MediaService.MetadataResult same = service.updateMetadata(siteId, assetId,
            new MediaService.MetadataPatch(asset.getTitle(), null, null, null, null), 1, "actor");
        assertThat(same.changedKeys()).isEmpty();
    }

    @Test
    void moveToFolderValidatesSiteScopeAndWrites() {
        MediaAssetEntity asset = asset(1);
        asset.setFolderPath("/images");
        when(assetRepo.findByIdAndSiteId(assetId, siteId)).thenReturn(Optional.of(asset));

        List<MediaService.MoveResult> results =
            service.moveToFolder(siteId, List.of(assetId), "/images/2026", "actor");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).fromFolder()).isEqualTo("/images");
        assertThat(results.get(0).toFolder()).isEqualTo("/images/2026");
        assertThat(asset.getFolderPath()).isEqualTo("/images/2026");
        verify(assetRepo).save(asset);

        when(assetRepo.findByIdAndSiteId(assetId, siteId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.moveToFolder(siteId, List.of(assetId), "/archive", "actor"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("媒体资产不存在");
    }

    @Test
    void moveToRootClearsFolderPath() {
        MediaAssetEntity asset = asset(1);
        asset.setFolderPath("/images");
        when(assetRepo.findByIdAndSiteId(assetId, siteId)).thenReturn(Optional.of(asset));

        List<MediaService.MoveResult> results = service.moveToFolder(siteId, List.of(assetId), null, "actor");

        assertThat(results.get(0).toFolder()).isNull();
        assertThat(asset.getFolderPath()).isNull();
    }

    private MediaAssetEntity asset(int version) {
        MediaAssetEntity entity = new MediaAssetEntity();
        entity.setId(assetId);
        entity.setSiteId(siteId);
        entity.setFilename("photo.png");
        entity.setContentHash("hash");
        entity.setMimeType("image/png");
        entity.setFileSize(1024);
        entity.setCurrentVersion(version);
        return entity;
    }
}
