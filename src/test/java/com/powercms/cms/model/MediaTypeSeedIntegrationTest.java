package com.powercms.cms.model;

import com.powercms.cms.identity.IdentityService;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.cms.site.SiteService;
import com.powercms.cms.site.dto.CreateSiteRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 复现：新建站点后媒体类型未出现的集成验证。
 * 注意：本测试类不加 @Transactional —— 站点创建事务提交后 AFTER_COMMIT 种子回调才会执行，
 * 从而验证 MediaTypeSeedService.onSiteCreated 的真实触发与落库。
 */
@SpringBootTest
class MediaTypeSeedIntegrationTest {

    @Autowired private IdentityService identityService;
    @Autowired private SiteService siteService;
    @Autowired private ContentTypeRepository typeRepo;

    @Test
    void newlyCreatedSiteGetsSixPresetMediaTypes() {
        UUID organizationId = identityService.ensureOrganization("media-seed-it-" + UUID.randomUUID());
        var site = siteService.create(organizationId,
            new CreateSiteRequest(
                "media-seed-site-" + System.nanoTime(),
                "媒体种子验证站",
                null, null, null, null),
            null);

        var codes = typeRepo.findBySiteIdOrderByCodeAsc(site.id()).stream()
            .filter(t -> t.getCode().startsWith("media_"))
            .map(t -> t.getCode())
            .toList();

        assertThat(codes).containsExactlyInAnyOrder(
            "media_video", "media_audio", "media_image", "media_document", "media_font", "media_other");
    }
}
