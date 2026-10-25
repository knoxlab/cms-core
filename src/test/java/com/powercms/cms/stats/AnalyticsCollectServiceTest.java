package com.powercms.cms.stats;

import com.powercms.common.error.BusinessException;
import com.powercms.cms.site.entity.SiteEntity;
import com.powercms.cms.site.repo.SiteRepository;
import com.powercms.cms.stats.entity.AnalyticsEventEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FR-STAT-002/003 éªæ¶ï¼siteKey/å»ç»/æ¹éä¸é/ç½åå/æºå¨äººè¿æ»¤/enrichment å­æ®µã
 */
class AnalyticsCollectServiceTest {

    private final SiteRepository siteRepo = mock(SiteRepository.class);
    private final AnalyticsIngestPipeline pipeline = mock(AnalyticsIngestPipeline.class);
    private final AnalyticsPlaybackService playbackService = mock(AnalyticsPlaybackService.class);
    private final AnalyticsMemberResolver memberResolver = mock(AnalyticsMemberResolver.class);
    private final com.powercms.cms.content.repo.ContentItemRepository contentItemRepo =
        mock(com.powercms.cms.content.repo.ContentItemRepository.class);
    private final AnalyticsCollectService service =
        new AnalyticsCollectService(siteRepo, pipeline, playbackService, memberResolver, contentItemRepo, "node-test", 2);
    private final SiteEntity site = new SiteEntity();

    @BeforeEach
    void setUp() {
        site.setId(UUID.randomUUID());
        site.setSiteKey("demo");
        site.setTimezone("Asia/Shanghai");
        when(siteRepo.findBySiteKey("demo")).thenReturn(Optional.of(site));
    }

    private AnalyticsCollectService.InboundEvent event(String type) {
        return new AnalyticsCollectService.InboundEvent(
            UUID.randomUUID().toString(), type, Instant.now(), "s-1", "v-1", Instant.now(), 1,
            "/news/a", "æ é¢", null, null, "https://www.baidu.com/s?wd=x", null, null, null,
            null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null,
            false, null);
    }

    @Test
    void rejectsUnknownSiteKey() {
        assertThatThrownBy(() -> service.collect("bad", null, null, "1.2.3.4", "UA", "zh", List.of(), null))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsBatchOverLimit() {
        var events = java.util.stream.IntStream.range(0, 51)
            .mapToObj(i -> event("PAGEVIEW")).toList();
        assertThatThrownBy(() -> service.collect("demo", "https://demo.example.com", null,
            "1.2.3.4", "UA", "zh", events, null))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void botTrafficIsDroppedWithoutEnqueue() {
        var result = service.collect("demo", "https://demo.example.com", null,
            "1.2.3.4", "Googlebot/2.1", "zh", List.of(event("PAGEVIEW")), null);
        assertThat(result.accepted()).isZero();
        verify(pipeline, never()).offer(any());
    }

    @Test
    void unknownEventTypeIsRejectedIndividually() {
        var result = service.collect("demo", "https://demo.example.com",
            "8.8.8.8, 10.0.0.1, 10.0.0.2", "172.16.0.9",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            "zh-CN",
            List.of(event("PAGEVIEW"), event("HACK")), null);
        assertThat(result.accepted()).isEqualTo(1);
        assertThat(result.rejected()).isEqualTo(1);

        ArgumentCaptor<AnalyticsEventEntity> captor = ArgumentCaptor.forClass(AnalyticsEventEntity.class);
        verify(pipeline).offer(captor.capture());
        AnalyticsEventEntity stored = captor.getValue();
        assertThat(stored.getSiteId()).isEqualTo(site.getId());
        assertThat(stored.getServerNode()).isEqualTo("node-test");
        // trusted-proxies=2ï¼XFF å³æ°è·³è¿ 2 ä¸ªå¯ä¿¡ä»£ç â å®¢æ·ç«¯ 8.8.8.8 â masked 8.8.8.0
        assertThat(stored.getIpMasked()).isEqualTo("8.8.8.0");
        assertThat(stored.getIpHash()).isNotBlank();
        // UA enrichment
        assertThat(stored.getOs()).isEqualTo("Windows");
        assertThat(stored.getBrowser()).isEqualTo("Chrome");
        // referer å½ç±»ï¼baidu.com â SEARCH_ENGINE
        assertThat(stored.getSourceType()).isEqualTo("SEARCH_ENGINE");
        assertThat(stored.getLanguage()).isEqualTo("zh-CN");
    }

    @Test
    void videoEventRequiresContentOrAsset() {
        // è§é¢äºä»¶ç¼º contentId/mediaAssetIdï¼æ¹éåçä¸­è¯¥æ¡è¢«æç»ï¼rejected=1ï¼ï¼ä¸ä¸­æ­æ´æ¹
        var result = service.collect("demo", "https://demo.example.com", null,
            "1.2.3.4", "Mozilla/5.0", "zh", List.of(event("VIDEO_PLAY")), null);
        assertThat(result.accepted()).isZero();
        assertThat(result.rejected()).isEqualTo(1);
    }

    @Test
    void acceptedVideoEventIsEnrichedWithMemberAndQueued() {
        var video = new AnalyticsCollectService.InboundEvent(
            UUID.randomUUID().toString(), "VIDEO_PLAY", Instant.now(), "s-1", "v-1", Instant.now(), 1,
            "/video/a", "è¯¾ç¨", UUID.randomUUID(), null, null, null, null, null,
            "ps-1", 0L, 0L, false, 0L, null, 0L, 300000L,
            null, null, null, null, null, null, null, null, null,
            false, null);
        var memberId = UUID.randomUUID();
        var result = service.collect("demo", "https://demo.example.com", null,
            "1.2.3.4", "Mozilla/5.0", "zh", List.of(video), memberId);
        assertThat(result.accepted()).isEqualTo(1);

        ArgumentCaptor<AnalyticsEventEntity> captor = ArgumentCaptor.forClass(AnalyticsEventEntity.class);
        verify(pipeline).offer(captor.capture());
        // ä¼åèº«ä»½ç±æå¡ç«¯æ³¨å¥å¹¶éäºä»¶è½åºï¼FR-STAT-037ï¼ï¼æ­æ¾é¾è·¯å¨è½åºåç±ç®¡éè§¦å
        assertThat(captor.getValue().getMemberId()).isEqualTo(memberId);
    }
}
