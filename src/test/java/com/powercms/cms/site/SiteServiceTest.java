package com.powercms.cms.site;

import com.powercms.cms.identity.IdentityService;
import com.powercms.cms.site.dto.SiteResponse;
import com.powercms.cms.site.entity.SiteEntity;
import com.powercms.cms.site.repo.EnvironmentRepository;
import com.powercms.cms.site.repo.SiteRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 站点生命周期与列表（FR-SITE-LC-001/004/005）：冻结站点保留在默认列表，
 * 已归档站点仅在显式查询时返回；冻结后才能归档。
 */
@ExtendWith(MockitoExtension.class)
class SiteServiceTest {

    @Mock private SiteRepository siteRepo;
    @Mock private EnvironmentRepository envRepo;
    @Mock private IdentityService identityService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private SiteService service;
    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new SiteService(siteRepo, envRepo, identityService, eventPublisher);
    }

    private static SiteEntity site(UUID id, String status) {
        SiteEntity s = new SiteEntity();
        s.setId(id);
        s.setOrganizationId(UUID.randomUUID());
        s.setSiteKey("key-" + id);
        s.setName("site-" + id);
        s.setStatus(status);
        return s;
    }

    @Test
    void defaultListReturnsActiveAndFrozen() {
        UUID activeId = UUID.randomUUID();
        UUID frozenId = UUID.randomUUID();
        when(siteRepo.findByOrganizationIdAndStatusIn(eq(orgId),
            eq(List.of(SiteEntity.STATUS_ACTIVE, SiteEntity.STATUS_FROZEN))))
            .thenReturn(List.of(site(activeId, SiteEntity.STATUS_ACTIVE), site(frozenId, SiteEntity.STATUS_FROZEN)));

        List<SiteResponse> result = service.list(orgId, null);

        assertThat(result).extracting(SiteResponse::id)
            .containsExactlyInAnyOrder(activeId, frozenId);
        verify(siteRepo, never()).findByOrganizationIdAndStatus(any(), any());
    }

    @Test
    void archivedOnlyWhenStatusParamProvided() {
        UUID archivedId = UUID.randomUUID();
        when(siteRepo.findByOrganizationIdAndStatus(eq(orgId), eq(SiteEntity.STATUS_ARCHIVED)))
            .thenReturn(List.of(site(archivedId, SiteEntity.STATUS_ARCHIVED)));

        List<SiteResponse> result = service.list(orgId, SiteEntity.STATUS_ARCHIVED);

        assertThat(result).extracting(SiteResponse::id).containsExactly(archivedId);
    }

    @Test
    void freezeMarksFrozen() {
        UUID id = UUID.randomUUID();
        SiteEntity s = site(id, SiteEntity.STATUS_ACTIVE);
        when(siteRepo.findById(id)).thenReturn(Optional.of(s));
        when(siteRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.freeze(id);

        assertThat(s.getStatus()).isEqualTo(SiteEntity.STATUS_FROZEN);
    }

    @Test
    void archiveRequiresFrozen() {
        UUID activeId = UUID.randomUUID();
        when(siteRepo.findById(activeId)).thenReturn(Optional.of(site(activeId, SiteEntity.STATUS_ACTIVE)));

        assertThatThrownBy(() -> service.archive(activeId))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getCode())
            .isEqualTo(ErrorCodes.CONFLICT);
    }

    @Test
    void archiveFromFrozenSucceeds() {
        UUID frozenId = UUID.randomUUID();
        SiteEntity s = site(frozenId, SiteEntity.STATUS_FROZEN);
        when(siteRepo.findById(frozenId)).thenReturn(Optional.of(s));
        when(siteRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.archive(frozenId);

        assertThat(s.getStatus()).isEqualTo(SiteEntity.STATUS_ARCHIVED);
    }

    @Test
    void unarchiveRestoresActive() {
        UUID id = UUID.randomUUID();
        SiteEntity s = site(id, SiteEntity.STATUS_ARCHIVED);
        when(siteRepo.findById(id)).thenReturn(Optional.of(s));
        when(siteRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.unarchive(id);

        assertThat(s.getStatus()).isEqualTo(SiteEntity.STATUS_ACTIVE);
    }

    @Test
    void updateProfileOnlyTouchesProvidedFields() {
        UUID id = UUID.randomUUID();
        SiteEntity s = site(id, SiteEntity.STATUS_ACTIVE);
        when(siteRepo.findById(id)).thenReturn(Optional.of(s));
        when(siteRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(envRepo.findBySiteId(id)).thenReturn(List.of());

        SiteResponse updated = service.updateProfile(id, "新名称", "  新描述  ", null, "UTC");

        assertThat(updated.name()).isEqualTo("新名称");
        assertThat(updated.description()).isEqualTo("新描述");
        assertThat(updated.defaultLocale()).isEqualTo("zh-CN");
        assertThat(updated.timezone()).isEqualTo("UTC");
    }

    @Test
    void updateProfileRejectsBlankRequiredFields() {
        UUID id = UUID.randomUUID();
        when(siteRepo.findById(id)).thenReturn(Optional.of(site(id, SiteEntity.STATUS_ACTIVE)));

        assertThatThrownBy(() -> service.updateProfile(id, "  ", null, null, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("站点名称");
        assertThatThrownBy(() -> service.updateProfile(id, null, null, " ", null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("默认语言");
    }
}
