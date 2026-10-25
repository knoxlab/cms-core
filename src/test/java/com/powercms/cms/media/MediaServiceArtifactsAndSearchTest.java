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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 批次 6：媒体 AI 产物快照（FR-AGT-MEDIA-004 前置校验）与检索面扩展（FR-AGT-MEDIA-001）。
 *
 * <p>关键口径：AI 产物面（alt_text/summary/transcript）默认不参与关键词匹配，须显式声明 match_in；
 * 转写稿全文命中必须透出命中面，便于 Agent 解释「为什么这条被检索到」。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MediaServiceArtifactsAndSearchTest {

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
    void artifactsOfRejectsAssetOutsideSite() {
        when(assetRepo.findByIdAndSiteId(assetId, siteId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.artifactsOf(siteId, assetId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("媒体资产不存在")
            .extracting(e -> ((BusinessException) e).getCode())
            .isEqualTo(ErrorCodes.NOT_FOUND);
    }

    @Test
    void artifactsOfExposesMarkersAndImagePredicate() {
        Instant syncedAt = Instant.parse("2026-09-10T08:00:00Z");
        MediaAssetEntity asset = asset("image", "image/png");
        asset.setAltText("蓝色封面");
        asset.setAltTextModel("gpt-4o-mini");
        asset.setTranscript("大家好，欢迎观看本期节目");
        asset.setTranscriptLang("zh");
        asset.setTranscriptModel("knox-whisper");
        asset.setTranscriptSyncedAt(syncedAt);
        when(assetRepo.findByIdAndSiteId(assetId, siteId)).thenReturn(Optional.of(asset));

        MediaService.ArtifactsView view = service.artifactsOf(siteId, assetId);

        assertThat(view.hasTranscript()).isTrue();
        assertThat(view.hasAltText()).isTrue();
        assertThat(view.hasSummary()).isFalse();
        assertThat(view.image()).isTrue();
        assertThat(view.transcriptModel()).isEqualTo("knox-whisper");
        assertThat(view.transcriptSyncedAt()).isEqualTo(syncedAt);
        assertThat(view.altTextModel()).isEqualTo("gpt-4o-mini");
    }

    @Test
    void videoWithoutImageMimeIsNotImage() {
        MediaAssetEntity asset = asset("video", "video/mp4");
        when(assetRepo.findByIdAndSiteId(assetId, siteId)).thenReturn(Optional.of(asset));

        assertThat(service.artifactsOf(siteId, assetId).image()).isFalse();
    }

    @Test
    void blankQueryReturnsAllAssetsWithNoMatchedFields() {
        when(assetRepo.findBySiteIdOrderByCreatedAtDesc(siteId))
            .thenReturn(List.of(asset("video", "video/mp4"), asset("image", "image/png")));

        List<MediaService.SearchHit> hits = service.search(siteId, null, null, null, "  ",
            MediaService.DEFAULT_SEARCH_FIELDS);

        assertThat(hits).hasSize(2);
        assertThat(hits).allSatisfy(hit -> assertThat(hit.matchedIn()).isEmpty());
    }

    @Test
    void transcriptOnlyKeywordIsNotMatchedByDefaultScope() {
        MediaAssetEntity asset = asset("video", "video/mp4");
        asset.setTranscript("本期讲的是容器编排与不可变基础设施");
        when(assetRepo.findBySiteIdOrderByCreatedAtDesc(siteId)).thenReturn(List.of(asset));

        assertThat(service.search(siteId, null, null, null, "容器编排", MediaService.DEFAULT_SEARCH_FIELDS))
            .isEmpty();
    }

    @Test
    void transcriptKeywordMatchesWhenScopeIsExplicit() {
        MediaAssetEntity asset = asset("video", "video/mp4");
        asset.setTranscript("本期讲的是容器编排与不可变基础设施");
        when(assetRepo.findBySiteIdOrderByCreatedAtDesc(siteId)).thenReturn(List.of(asset));

        List<MediaService.SearchHit> hits = service.search(siteId, null, null, null, "容器编排",
            Set.of("transcript"));

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).matchedIn()).containsExactly("transcript");
    }

    @Test
    void matchInReportsEveryHitFaceAndHonoursStructuredFilters() {
        MediaAssetEntity image = asset("image", "image/png");
        image.setTitle("2026 春季封面");
        image.setAltText("春季封面：标签含 2026");
        image.setStatus("READY");
        MediaAssetEntity video = asset("video", "video/mp4");
        video.setTitle("2026 年度回顾");
        video.setStatus("PROCESSING");
        when(assetRepo.findBySiteIdOrderByCreatedAtDesc(siteId)).thenReturn(List.of(image, video));

        List<MediaService.SearchHit> multiFace = service.search(siteId, null, null, "READY", "2026",
            MediaService.SEARCH_FIELDS);

        assertThat(multiFace).hasSize(1);
        assertThat(multiFace.get(0).matchedIn()).containsExactlyInAnyOrder("title", "alt_text");
        assertThat(multiFace.get(0).asset().status()).isEqualTo("READY");

        List<MediaService.SearchHit> kindFiltered = service.search(siteId, "VIDEO", null, null, "2026",
            MediaService.DEFAULT_SEARCH_FIELDS);
        assertThat(kindFiltered).hasSize(1);
        assertThat(kindFiltered.get(0).asset().kind()).isEqualTo("video");
    }

    private MediaAssetEntity asset(String kind, String mimeType) {
        MediaAssetEntity entity = new MediaAssetEntity();
        entity.setId(UUID.randomUUID());
        entity.setSiteId(siteId);
        entity.setFilename("asset-" + kind);
        entity.setContentHash("hash-" + kind);
        entity.setMimeType(mimeType);
        entity.setFileSize(1024);
        entity.setCurrentVersion(1);
        entity.setKind(kind);
        entity.setStatus("READY");
        return entity;
    }
}
