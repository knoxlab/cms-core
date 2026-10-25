package com.powercms.cms.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.audit.AuditService;
import com.powercms.cms.media.entity.MediaAssetEntity;
import com.powercms.cms.media.repo.MediaAssetRepository;
import com.powercms.cms.media.repo.MediaVersionRepository;
import com.powercms.cms.model.repo.ContentTypeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 媒体库服务端分页（FR-KNX-033）：分页必须建立在**过滤 + 全序排序之后**。
 *
 * <p>三条回归口径：
 * ① {@code total} 是过滤后的总数（不是全站资产数）；
 * ② 逐页遍历必须每个资产恰好出现一次（页边界不重复、不遗漏）——分页最致命的缺陷；
 * ③ 排序模式在分页下生效（而非仅页内有序）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MediaServicePageTest {

    @Mock MediaAssetRepository assetRepo;
    @Mock MediaVersionRepository versionRepo;
    @Mock ContentTypeRepository typeRepo;
    @Mock ApplicationEventPublisher events;
    @Mock AuditService audit;

    private MediaService service;
    private final UUID siteId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new MediaService(assetRepo, versionRepo, typeRepo, events, audit, new ObjectMapper());
    }

    private MediaAssetEntity asset(String filename, long size, String kind, String status) {
        MediaAssetEntity entity = new MediaAssetEntity();
        entity.setId(UUID.randomUUID());
        entity.setSiteId(siteId);
        entity.setFilename(filename);
        entity.setContentHash("hash-" + filename);
        entity.setMimeType("image/png");
        entity.setFileSize(size);
        entity.setCurrentVersion(1);
        entity.setKind(kind);
        entity.setStatus(status);
        return entity;
    }

    /** 20 个文件名刻意包含数字段，用于验证分页顺序与前端自然排序一致。 */
    private List<MediaAssetEntity> library() {
        List<MediaAssetEntity> assets = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            assets.add(asset("img" + i + ".png", 100L + i,
                i % 5 == 0 ? "video" : "image", i % 2 == 0 ? "READY" : "PROCESSING"));
        }
        return assets;
    }

    @Test
    void pageWalksEveryAssetExactlyOnceAcrossPages() {
        when(assetRepo.findBySiteIdOrderByCreatedAtDesc(siteId)).thenReturn(library());

        Set<UUID> seen = new LinkedHashSet<>();
        int page = 1;
        boolean hasMore = true;
        int guard = 0;
        while (hasMore) {
            var slice = service.page(siteId, null, null, null, null, "filename-asc", page, 3);
            slice.items().forEach(hit -> assertThat(seen.add(hit.asset().id()))
                .as("资产 %s 在分页中被重复返回", hit.asset().filename())
                .isTrue());
            hasMore = slice.hasMore();
            page++;
            assertThat(guard++).isLessThan(50);
        }

        assertThat(seen).hasSize(20);
    }

    @Test
    void totalReflectsFilteredCountNotWholeLibrary() {
        when(assetRepo.findBySiteIdOrderByCreatedAtDesc(siteId)).thenReturn(library());

        // 库内 20 条，其中 PROCESSING 10 条
        var slice = service.page(siteId, null, null, null, "PROCESSING", "filename-asc", 1, 3);

        assertThat(slice.total()).isEqualTo(10);
        assertThat(slice.items()).hasSize(3);
        assertThat(slice.items()).allSatisfy(hit -> assertThat(hit.asset().status()).isEqualTo("PROCESSING"));
    }

    @Test
    void kindFilterAppliesBeforeSlicing() {
        when(assetRepo.findBySiteIdOrderByCreatedAtDesc(siteId)).thenReturn(library());

        var first = service.page(siteId, "video", null, null, null, "filename-asc", 1, 2);

        assertThat(first.total()).isEqualTo(4); // img0/5/10/15
        assertThat(first.items()).allSatisfy(hit -> assertThat(hit.asset().kind()).isEqualTo("video"));
    }

    @Test
    void sortModeIsHonouredAcrossPageBoundaries() {
        when(assetRepo.findBySiteIdOrderByCreatedAtDesc(siteId)).thenReturn(library());

        var sizeDesc = service.page(siteId, null, null, null, null, "size-desc", 1, 5);

        // size = 100 + i，最大的是 img19
        assertThat(sizeDesc.items().get(0).asset().filename()).isEqualTo("img19.png");
        assertThat(sizeDesc.items().get(1).asset().filename()).isEqualTo("img18.png");
    }

    /** 自然排序必须跨页生效：img2 要排在 img10 之前（字典序会反过来）。 */
    @Test
    void naturalOrderIsAppliedServerSide() {
        when(assetRepo.findBySiteIdOrderByCreatedAtDesc(siteId)).thenReturn(library());

        var first = service.page(siteId, null, null, null, null, "filename-asc", 1, 4);

        assertThat(first.items().stream().map(hit -> hit.asset().filename()).toList())
            .containsExactly("img0.png", "img1.png", "img2.png", "img3.png");
    }

    @Test
    void pageBeyondEndReturnsEmptyWithoutError() {
        when(assetRepo.findBySiteIdOrderByCreatedAtDesc(siteId)).thenReturn(library());

        var slice = service.page(siteId, null, null, null, null, "filename-asc", 99, 10);

        assertThat(slice.items()).isEmpty();
        assertThat(slice.total()).isEqualTo(20);
        assertThat(slice.hasMore()).isFalse();
    }

    @Test
    void folderFilterKeepsTheDirectoryAndItsChildren() {
        MediaAssetEntity root = asset("root.png", 1, "image", "ACTIVE");
        MediaAssetEntity images = asset("cover.png", 2, "image", "ACTIVE");
        images.setFolderPath("/images");
        MediaAssetEntity nested = asset("banner.png", 3, "image", "PROCESSING");
        nested.setFolderPath("/images/2026");
        MediaAssetEntity docs = asset("note.pdf", 4, "document", "ACTIVE");
        docs.setFolderPath("/docs");
        when(assetRepo.findBySiteIdOrderByCreatedAtDesc(siteId)).thenReturn(List.of(root, images, nested, docs));

        var slice = service.page(siteId, null, null, null, null, "filename-asc", 1, 10, "/images");
        assertThat(slice.items().stream().map(hit -> hit.asset().filename()).toList())
            .containsExactly("banner.png", "cover.png");

        var onlyRoot = service.page(siteId, null, null, null, null, "filename-asc", 1, 10, "/");
        assertThat(onlyRoot.items()).extracting(hit -> hit.asset().filename()).containsExactly("root.png");
    }

    @Test
    void pageSizeIsClampedToPreventFullDump() {
        when(assetRepo.findBySiteIdOrderByCreatedAtDesc(siteId)).thenReturn(library());

        var slice = service.page(siteId, null, null, null, null, "filename-asc", 1, 10_000);

        assertThat(slice.size()).isEqualTo(MediaSort.MAX_PAGE_SIZE);
        assertThat(slice.items()).hasSize(20);
        assertThat(slice.hasMore()).isFalse();
    }
}
