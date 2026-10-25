package com.powercms.cms.delivery;

import com.powercms.cms.model.AnalyticsComponentSeedService;
import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.cms.site.entity.SiteEntity;
import com.powercms.cms.site.repo.SiteRepository;
import com.powercms.cms.sitepackage.ComponentInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 统计采集注入器（FR-STAT-001）：组件命中、默认配置、伪协议拒绝、注入位置。
 */
@ExtendWith(MockitoExtension.class)
class AnalyticsInjectorTest {

    @Mock
    private ContentTypeRepository typeRepo;

    @Mock
    private SiteRepository siteRepo;

    private AnalyticsInjector injector;
    private final UUID siteId = UUID.randomUUID();
    private UUID trackerTypeId;

    @BeforeEach
    void setUp() {
        injector = new AnalyticsInjector(typeRepo, siteRepo);
        trackerTypeId = UUID.randomUUID();
        ContentTypeEntity type = new ContentTypeEntity();
        type.setId(trackerTypeId);
        org.mockito.Mockito.lenient()
            .when(typeRepo.findBySiteIdAndCodeOrderByVersionAsc(siteId, AnalyticsComponentSeedService.ANALYTICS_TRACKER))
            .thenReturn(List.of(type));
        SiteEntity site = new SiteEntity();
        site.setId(siteId);
        site.setSiteKey("demo-site");
        org.mockito.Mockito.lenient().when(siteRepo.findById(siteId)).thenReturn(Optional.of(site));
    }

    private ComponentInstance tracker(Map<String, Object> data) {
        return new ComponentInstance(UUID.randomUUID().toString(), ComponentInstance.Kind.EMBEDDED,
            null, null, trackerTypeId, "统计采集", data, null, null, Map.of(), 0, null, Map.of(), null, null);
    }

    @Test
    void detectsTrackerAndUsesDefaultsWhenFieldsBlank() {
        var hit = injector.findTracker(siteId, Map.of("footer", List.of(tracker(Map.of()))));
        assertThat(hit).isPresent();
        String tag = injector.renderTag(siteId, hit.get());
        // 采集端按 siteKey 解析站点：注入值必须是站点的 siteKey，不是站点 UUID
        assertThat(tag).contains("siteKey:\"demo-site\"");
        assertThat(tag).contains(AnalyticsInjector.DEFAULT_ENDPOINT);
        assertThat(tag).contains(AnalyticsInjector.DEFAULT_SCRIPT_URL);
    }

    @Test
    void siteAppTagInjectedWhenTrackerComponentTypeExists() {
        var tag = injector.renderSiteAppTag(siteId);
        assertThat(tag).isPresent();
        assertThat(tag.get()).contains("siteKey:\"demo-site\"");
        assertThat(tag.get()).contains(AnalyticsInjector.DEFAULT_SCRIPT_URL);
    }

    @Test
    void siteAppTagEmptyWhenTrackerComponentTypeDeleted() {
        org.mockito.Mockito.when(typeRepo.findBySiteIdAndCodeOrderByVersionAsc(siteId,
            AnalyticsComponentSeedService.ANALYTICS_TRACKER)).thenReturn(List.of());
        assertThat(injector.renderSiteAppTag(siteId)).isEmpty();
    }

    @Test
    void missesWhenTypeDiffersOrKindIsNotEmbedded() {
        ComponentInstance content = new ComponentInstance(UUID.randomUUID().toString(),
            ComponentInstance.Kind.CONTENT, null, null, trackerTypeId, "内容", Map.of(),
            null, null, Map.of(), 0, null, Map.of(), UUID.randomUUID(), 1);
        assertThat(injector.findTracker(siteId, Map.of("z", List.of(content)))).isEmpty();
    }

    @Test
    void customEndpointAcceptedButPseudoProtocolRejected() {
        var hit = injector.findTracker(siteId, Map.of("z", List.of(tracker(
            Map.of("endpoint_s", "javascript:alert(1)", "script_url_s", "/custom/analytics.js")))));
        assertThat(hit).isPresent();
        assertThat(hit.get().endpoint()).isEmpty();
        assertThat(hit.get().scriptUrl()).isEqualTo("/custom/analytics.js");
        String tag = injector.renderTag(siteId, hit.get());
        assertThat(tag).doesNotContain("javascript:");
        assertThat(tag).contains("/custom/analytics.js");
    }

    @Test
    void injectsExactlyOnceBeforeBodyEnd() {
        String html = "<html><body><p>hi</p></body></html>";
        String snippet = "<script src=\"x\"></script>";
        String out = AnalyticsInjector.injectBeforeBodyEnd(html, snippet);
        assertThat(out).endsWith(snippet + "</body></html>");
        assertThat(out.indexOf(snippet)).isEqualTo(out.lastIndexOf(snippet));
    }

    @Test
    void noTrackerWhenZoneMapEmpty() {
        AnalyticsInjector bare = new AnalyticsInjector(typeRepo, siteRepo);
        assertThat(bare.findTracker(siteId, Map.of())).isEmpty();
        assertThat(bare.findTracker(siteId, null)).isEmpty();
        assertThat(bare.findTracker(UUID.randomUUID(), Map.of("z",
            List.of(tracker(Map.of()))))).isEmpty();
    }

    @Test
    void trackerConfigRecordExposesFields() {
        var config = new AnalyticsInjector.TrackerConfig("/a", "/b");
        assertThat(config.endpoint()).isEqualTo("/a");
        assertThat(config.scriptUrl()).isEqualTo("/b");
        assertThat(Optional.of(config)).isPresent();
    }
}
