package com.powercms.cms.media;

import com.powercms.cms.media.MediaAssetRegisteredEvent;
import com.powercms.cms.media.entity.MediaAssetEntity;
import com.powercms.cms.media.repo.MediaAssetRepository;
import com.powercms.cms.model.MediaClassifyService;
import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.repo.ContentTypeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
/**
 * kind/MIME 存量纠偏（FR-CM-MEDIA-040 扩展）：.ts 等传输流被误归 document 时，
 * 启动按扩展名表纠正 kind/mime_type 并重挂媒体类型，事件驱动内容项刷新。
 */
@ExtendWith(MockitoExtension.class)
class MediaKindBackfillJobTest {

    @Mock
    private MediaAssetRepository assetRepo;
    @Mock
    private ContentTypeRepository typeRepo;
    @Mock
    private MediaClassifyService classify;
    @Mock
    private ApplicationEventPublisher events;

    private MediaKindBackfillJob job;
    private final UUID siteId = UUID.randomUUID();
    private final ContentTypeEntity videoType = type("media_video", "video");
    private final ContentTypeEntity audioType = type("media_audio", "audio");
    private final ContentTypeEntity fontType = type("media_font", "font");

    @BeforeEach
    void setUp() {
        job = new MediaKindBackfillJob(assetRepo, typeRepo, classify, events);
        lenient().when(typeRepo.findActiveBySiteIdAndObjectTypeOrderByCodeAsc(siteId, "MEDIA"))
            .thenReturn(List.of(videoType, audioType, fontType));
    }

    @Test
    void misclassifiedTsVideoIsCorrectedToVideo() {
        MediaAssetEntity asset = asset("北京体育卫视.ts", "text/plain", "document");
        when(assetRepo.findByKindIn(anyList())).thenReturn(List.of(asset));
        when(assetRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        job.backfill();

        assertThat(asset.getKind()).isEqualTo("video");
        assertThat(asset.getMimeType()).isEqualTo("video/mp2t");
        assertThat(asset.getMediaTypeId()).isEqualTo(videoType.getId());
        verify(events).publishEvent(any(MediaAssetReadyEvent.class));
    }

    @Test
    void unclassifiedLegacyChunkAssetIsClassifiedAndRegistered() {
        MediaAssetEntity asset = asset("movie.mkv", "video/x-matroska", null);
        when(assetRepo.findByMediaTypeIdIsNullAndKindIsNull()).thenReturn(List.of(asset));
        when(classify.resolve(siteId, "movie.mkv", "video/x-matroska", 0L))
            .thenReturn(new MediaClassifyService.Classification(videoType.getId(), "media_video",
                "video", "2026/08/28/{objectId}/video", "2026/{mm}/{dd}/{objectId}/{kind}", false, null));
        lenient().when(classify.resolvePath(any(), any(), any(), any())).thenReturn("2026/08/28/x/video");
        when(assetRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        job.backfill();

        assertThat(asset.getKind()).isEqualTo("video");
        assertThat(asset.getMediaTypeId()).isEqualTo(videoType.getId());
        assertThat(asset.getFolderPath()).isNotBlank();
        verify(events).publishEvent(any(MediaAssetRegisteredEvent.class));
    }

    @Test
    void genuineDocumentIsUntouched() {
        MediaAssetEntity asset = asset("readme.txt", "text/plain", "document");
        when(assetRepo.findByKindIn(anyList())).thenReturn(List.of(asset));

        job.backfill();

        assertThat(asset.getKind()).isEqualTo("document");
        verify(events, never()).publishEvent(any());
    }

    @Test
    void alreadyVideoKindIsUntouched() {
        MediaAssetEntity asset = asset("clip.ts", "video/mp2t", "video");
        when(assetRepo.findByKindIn(anyList())).thenReturn(List.of(asset));

        job.backfill();

        assertThat(asset.getKind()).isEqualTo("video");
        assertThat(asset.getMimeType()).isEqualTo("video/mp2t");
        verify(events, never()).publishEvent(any());
    }

    @Test
    void audioMisclassifiedAsOtherIsCorrected() {
        MediaAssetEntity asset = asset("song.mp3", "audio/mpeg", "other");
        when(assetRepo.findByKindIn(anyList())).thenReturn(List.of(asset));
        when(assetRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        job.backfill();

        assertThat(asset.getKind()).isEqualTo("audio");
        assertThat(asset.getMediaTypeId()).isEqualTo(audioType.getId());
        verify(events).publishEvent(any(MediaAssetReadyEvent.class));
    }

    @Test
    void fontMisclassifiedAsOtherIsCorrectedToFont() {
        MediaAssetEntity asset = asset("ysbt.ttf", "font/ttf", "other");
        when(assetRepo.findByKindIn(anyList())).thenReturn(List.of(asset));
        when(assetRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        job.backfill();

        assertThat(asset.getKind()).isEqualTo("font");
        assertThat(asset.getMimeType()).isEqualTo("font/ttf");
        assertThat(asset.getMediaTypeId()).isEqualTo(fontType.getId());
        verify(events).publishEvent(any(MediaAssetReadyEvent.class));
    }

    @Test
    void fontSkippedWhenSiteHasNoFontType() {
        // 存量站点尚未补种 media_font 时不得误判：无目标类型则保持原样并跳过
        job = new MediaKindBackfillJob(assetRepo, typeRepo, classify, events);
        MediaAssetEntity asset = asset("ysbt.ttf", "font/ttf", "other");
        when(typeRepo.findActiveBySiteIdAndObjectTypeOrderByCodeAsc(siteId, "MEDIA"))
            .thenReturn(List.of(videoType, audioType));
        when(assetRepo.findByKindIn(anyList())).thenReturn(List.of(asset));

        job.backfill();

        assertThat(asset.getKind()).isEqualTo("other");
        verify(events, never()).publishEvent(any());
    }

    private ContentTypeEntity type(String code, String kind) {
        ContentTypeEntity t = new ContentTypeEntity();
        t.setId(UUID.randomUUID());
        t.setSiteId(siteId);
        t.setCode(code);
        t.setName(code);
        t.setObjectType("MEDIA");
        t.setMediaKind(kind);
        return t;
    }

    private MediaAssetEntity asset(String filename, String mimeType, String kind) {
        MediaAssetEntity a = new MediaAssetEntity();
        a.setId(UUID.randomUUID());
        a.setSiteId(siteId);
        a.setFilename(filename);
        a.setMimeType(mimeType);
        a.setKind(kind);
        return a;
    }
}
