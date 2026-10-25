package com.powercms.cms.delivery.web;

import com.powercms.cms.delivery.DeliveryService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DEF-DELIVERY-02 回归：交付内容列表在**空集**（无已部署 Release / 该分类无内容）时必须返回
 * {@code 200 []}，而不是 500。
 *
 * <p>成因：{@code ResponseEntity.lastModified(null)} 会走 {@code HttpHeaders.setInstant(null)} →
 * {@code Objects.requireNonNull} NPE。空集恰恰是「无已部署 Release」这一**正确状态**下的正常响应
 * （FR-API-002：未发布不得返回，而非报错），此前该状态直接 500，把交付 API 的合法空态变成了故障。
 */
class DeliveryControllerEmptyListTest {

    private final DeliveryService service = mock(DeliveryService.class);
    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new DeliveryController(service))
        .build();

    @Test
    void emptyDeliveryListReturns200WithoutLastModified() throws Exception {
        UUID siteId = UUID.randomUUID();
        when(service.listPublishedViews(eq(siteId), any(), anyInt(), anyInt())).thenReturn(List.of());

        mvc.perform(get("/api/v1/delivery/sites/{siteId}/contents", siteId))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(content().json("[]"))
            // 空集没有可协商的修改时间：不下发 Last-Modified（ETag 仍为固定值，保持可缓存）
            .andExpect(header().doesNotExist("Last-Modified"))
            .andExpect(header().string("ETag", "W/\"0\""));
    }

    @Test
    void nonEmptyDeliveryListStillSendsLastModified() throws Exception {
        UUID siteId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        var updatedAt = java.time.Instant.parse("2026-09-01T00:00:00Z");
        when(service.listPublishedViews(eq(siteId), any(), anyInt(), anyInt())).thenReturn(List.of(
            new DeliveryService.DeliveryView(contentId, "标题", null, UUID.randomUUID(), 1, "{}",
                updatedAt, List.of())));

        mvc.perform(get("/api/v1/delivery/sites/{siteId}/contents", siteId))
            .andExpect(status().isOk())
            .andExpect(header().exists("Last-Modified"))
            .andExpect(header().string("ETag", "W/\"" + updatedAt.toEpochMilli() + "\""));
    }
}
