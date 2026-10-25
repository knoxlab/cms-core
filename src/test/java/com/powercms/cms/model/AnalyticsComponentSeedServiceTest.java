package com.powercms.cms.model;

import com.powercms.cms.model.dto.CreateContentTypeRequest;
import com.powercms.cms.model.dto.ContentTypeResponse;
import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.cms.site.SiteCreatedEvent;
import com.powercms.cms.site.repo.SiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 统计采集系统组件种子（FR-STAT-001 注入开关）：每站一种、幂等、system 标记。
 */
@ExtendWith(MockitoExtension.class)
class AnalyticsComponentSeedServiceTest {

    @Mock
    private ContentTypeService typeService;
    @Mock
    private ContentTypeRepository typeRepo;
    @Mock
    private SiteRepository siteRepo;

    private AnalyticsComponentSeedService service;
    private final UUID siteId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AnalyticsComponentSeedService(typeService, typeRepo, siteRepo);
    }

    @Test
    void seedsOneTrackerComponentPerSite() {
        when(typeRepo.existsBySiteIdAndCode(eq(siteId), any())).thenReturn(false);
        when(typeService.create(eq(siteId), any())).thenAnswer(inv -> {
            ContentTypeResponse r = org.mockito.Mockito.mock(ContentTypeResponse.class);
            UUID id = UUID.randomUUID();
            when(r.id()).thenReturn(id);
            when(typeRepo.findById(id)).thenReturn(Optional.of(new ContentTypeEntity()));
            return r;
        });
        when(typeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        int created = service.seed(siteId);

        assertThat(created).isEqualTo(1);
        ArgumentCaptor<CreateContentTypeRequest> captor = ArgumentCaptor.forClass(CreateContentTypeRequest.class);
        verify(typeService).create(eq(siteId), captor.capture());
        CreateContentTypeRequest request = captor.getValue();
        assertThat(request.code()).isEqualTo(AnalyticsComponentSeedService.ANALYTICS_TRACKER);
        assertThat(request.objectType()).isEqualTo("COMPONENT");
        assertThat(request.templateType()).isEqualTo("COMPONENT");
        verify(typeService).publish(eq(siteId), any(), eq("analytics-component-seed"), any());
    }

    @Test
    void seedIsIdempotentAndBackfillsSystemFlag() {
        when(typeRepo.existsBySiteIdAndCode(siteId, AnalyticsComponentSeedService.ANALYTICS_TRACKER))
            .thenReturn(true);
        ContentTypeEntity existing = new ContentTypeEntity();
        when(typeRepo.findBySiteIdAndCodeOrderByVersionAsc(siteId, AnalyticsComponentSeedService.ANALYTICS_TRACKER))
            .thenReturn(java.util.List.of(existing));
        when(typeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        int created = service.seed(siteId);

        assertThat(created).isZero();
        verify(typeService, never()).create(any(), any());
        assertThat(existing.isSystem()).isTrue();
    }

    @Test
    void siteCreatedEventTriggersSeed() {
        when(typeRepo.existsBySiteIdAndCode(any(), any())).thenReturn(true);
        when(typeRepo.findBySiteIdAndCodeOrderByVersionAsc(any(), any()))
            .thenReturn(java.util.List.of(new ContentTypeEntity()));

        service.onSiteCreated(new SiteCreatedEvent(siteId, UUID.randomUUID()));

        verify(typeRepo).existsBySiteIdAndCode(eq(siteId),
            eq(AnalyticsComponentSeedService.ANALYTICS_TRACKER));
    }
}
