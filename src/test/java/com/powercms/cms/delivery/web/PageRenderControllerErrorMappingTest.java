package com.powercms.cms.delivery.web;

import com.powercms.cms.delivery.PageRenderService;
import com.powercms.cms.delivery.navigation.UrlRewriteService;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * FR-DELIVERY-PERM-001：SSR 错误映射测试。
 * 权限拒绝 / 页面不存在（NOT_FOUND）→ HTTP 404 + no-store；
 * 其他 BusinessException → HTTP 500 + no-store + traceId。
 */
class PageRenderControllerErrorMappingTest {

    private final UUID siteId = UUID.randomUUID();
    private final PageRenderService service = mock(PageRenderService.class);
    private final UrlRewriteService rewrites = mock(UrlRewriteService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        PageRenderController controller = new PageRenderController(service, rewrites,
            mock(com.powercms.cms.site.repo.SiteRepository.class), "/login");
        mvc = standaloneSetup(controller).build();
    }

    private void stubRewrite(String route) throws Exception {
        when(rewrites.rewrite(eq(siteId), eq(route), eq(false))).thenReturn(route);
    }

    private void stubRenderThrows(String route, BusinessException ex) {
        when(service.renderPublished(eq(siteId), eq(route), any(), any(), any(), any(), isNull(), isNull()))
            .thenThrow(ex);
    }

    @Test
    void permissionDeniedMapsTo404WithNoStore() throws Exception {
        stubRewrite("/secret");
        stubRenderThrows("/secret", new BusinessException(ErrorCodes.NOT_FOUND, "页面不存在"));

        mvc.perform(get("/api/v1/delivery/sites/{id}/pages", siteId.toString()).param("route", "/secret"))
            .andExpect(status().isNotFound())
            .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void missingPageMapsTo404WithNoStore() throws Exception {
        stubRewrite("/gone");
        when(service.renderPublished(eq(siteId), eq("/gone"), any(), any(), any(), any(), isNull(), isNull()))
            .thenThrow(new BusinessException(ErrorCodes.NOT_FOUND, "已发布页面不存在: /gone"));

        mvc.perform(get("/api/v1/delivery/sites/{id}/pages", siteId.toString()).param("route", "/gone"))
            .andExpect(status().isNotFound())
            .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void otherBusinessErrorMapsTo500WithTraceId() throws Exception {
        stubRewrite("/boom");
        stubRenderThrows("/boom", new BusinessException(ErrorCodes.TEMPLATE_RENDER_ERROR, "模板渲染失败"));

        mvc.perform(get("/api/v1/delivery/sites/{id}/pages", siteId.toString()).param("route", "/boom"))
            .andExpect(status().isInternalServerError())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().exists("X-PowerCMS-Trace-Id"));
    }
}
