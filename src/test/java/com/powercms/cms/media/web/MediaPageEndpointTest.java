package com.powercms.cms.media.web;

import com.powercms.cms.audit.AuditService;
import com.powercms.cms.media.MediaService;
import com.powercms.cms.media.MediaSort;
import com.powercms.cms.media.repo.MediaAssetRepository;
import com.powercms.cms.media.repo.MediaVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * 媒体库分页端点契约。
 *
 * <p>两个关键点：
 * ① {@code /media/page} 必须被**字面量**路由命中，而不是被 {@code @GetMapping("/{id}")} 当 UUID 吃掉
 *   （否则会以 400/404 形式静默失效）；
 * ② 全量端点 {@code GET /media} 的数组契约保持不变（站点浏览器、媒体选择器仍依赖它）。
 */
class MediaPageEndpointTest {

    private final UUID siteId = UUID.randomUUID();
    private final MediaService service = mock(MediaService.class);
    private final MediaVersionRepository versionRepo = mock(MediaVersionRepository.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        MediaController controller = new MediaController(service, versionRepo,
            mock(MediaAssetRepository.class), mock(AuditService.class));
        mvc = standaloneSetup(controller).build();
        when(versionRepo.findByMediaAssetIdOrderByVersionDesc(org.mockito.ArgumentMatchers.any()))
            .thenReturn(List.of());
    }

    private MediaService.AssetInfo asset(String filename, long size) {
        return new MediaService.AssetInfo(UUID.randomUUID(), siteId, null, filename, "image/png",
            size, "hash-" + filename, 1, "ACTIVE", "image", null, null, null, null);
    }

    private MediaService.SearchHit hit(String filename, long size) {
        return new MediaService.SearchHit(asset(filename, size), List.of());
    }

    private List<MediaService.SearchHit> fiveAssets() {
        List<MediaService.SearchHit> hits = new ArrayList<>();
        for (int i = 0; i < 5; i++) hits.add(hit("img" + i + ".png", 100L + i));
        return hits;
    }

    @Test
    void pagePathIsMatchedByLiteralRouteNotPathVariable() throws Exception {
        when(service.page(eq(siteId), isNull(), isNull(), isNull(), isNull(), eq("size-desc"), eq(2), eq(1), isNull()))
            .thenReturn(MediaSort.slice(fiveAssets(), 2, 1));

        mvc.perform(get("/api/v1/sites/{siteId}/media/page", siteId)
                .param("sort", "size-desc").param("page", "2").param("size", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(5))
            .andExpect(jsonPath("$.page").value(2))
            .andExpect(jsonPath("$.size").value(1))
            .andExpect(jsonPath("$.hasMore").value(true))
            .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void pageWithoutParamsUsesDefaultPageAndSize() throws Exception {
        when(service.page(eq(siteId), isNull(), isNull(), isNull(), isNull(), isNull(), eq(1), eq(24), isNull()))
            .thenReturn(MediaSort.slice(fiveAssets(), 1, 24));

        mvc.perform(get("/api/v1/sites/{siteId}/media/page", siteId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.size").value(24))
            .andExpect(jsonPath("$.hasMore").value(false))
            .andExpect(jsonPath("$.items.length()").value(5));
    }

    /** 筛选参数需透传到服务端排序 + 切页，而不是被忽略。 */
    @Test
    void pageForwardsFiltersAndSortToService() throws Exception {
        when(service.page(eq(siteId), eq("image"), isNull(), eq("logo"), eq("ACTIVE"),
            eq("kind-asc"), eq(3), eq(10), isNull()))
            .thenReturn(MediaSort.slice(fiveAssets(), 3, 10));

        mvc.perform(get("/api/v1/sites/{siteId}/media/page", siteId)
                .param("kind", "image").param("q", "logo").param("status", "ACTIVE")
                .param("sort", "kind-asc").param("page", "3").param("size", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(0))
            .andExpect(jsonPath("$.hasMore").value(false));
    }

    /** 全量端点契约不得因新增分页端点而改变（仍是数组，不是分页信封）。 */
    @Test
    void fullListEndpointKeepsPlainArrayContract() throws Exception {
        when(service.list(siteId, null, null, null, null, null))
            .thenReturn(List.of(asset("a.png", 1), asset("b.png", 2)));

        mvc.perform(get("/api/v1/sites/{siteId}/media", siteId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].asset.filename").value("a.png"))
            // 信封字段不得出现，避免调用方误判
            .andExpect(jsonPath("$.items").doesNotExist());
    }

    /** 图片/视频给出本站代理封面地址。 */
    @Test
    void detailExposesCoverProxyUrl() throws Exception {
        UUID id = stubDetail("video", "video/mp4");

        mvc.perform(get("/api/v1/sites/{siteId}/media/{id}/detail", siteId, id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.posterUrl").value("/media-thumb/" + siteId + "/" + id));
    }

    /** 音频没有封面概念：给 null，前端据此不渲染坏图（不是发一个必 404 的地址）。 */
    @Test
    void detailOmitsCoverForAudio() throws Exception {
        UUID id = stubDetail("audio", "audio/mpeg");

        mvc.perform(get("/api/v1/sites/{siteId}/media/{id}/detail", siteId, id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.posterUrl").value(nullValue()));
    }

    private UUID stubDetail(String kind, String mime) {
        UUID id = UUID.randomUUID();
        when(service.get(id)).thenReturn(new MediaService.AssetInfo(id, siteId, null, "clip.mp4", mime,
            1234L, "hash", 1, "ACTIVE", kind, null, null, null, null));
        return id;
    }
}
