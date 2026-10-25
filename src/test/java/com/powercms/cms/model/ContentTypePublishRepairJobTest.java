package com.powercms.cms.model;

import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.cms.site.repo.SiteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 存量数据修复：同 code 多行 PUBLISHED 时仅保留最高版本，其余置为 DEPRECATED（幂等）。
 */
@ExtendWith(MockitoExtension.class)
class ContentTypePublishRepairJobTest {

    @Mock private ContentTypeRepository typeRepo;
    @Mock private SiteRepository siteRepo;

    @Test
    void repairsDuplicatePublishedRowsKeepingHighestVersion() {
        UUID siteId = UUID.randomUUID();
        ContentTypeEntity v1 = published(siteId, "news", 1);
        ContentTypeEntity v2 = published(siteId, "news", 2);
        ContentTypeEntity v3 = published(siteId, "news", 3);
        when(typeRepo.findBySiteIdOrderByCodeAsc(siteId)).thenReturn(List.of(v1, v2, v3));

        new ContentTypePublishRepairJob(typeRepo, siteRepo).repairSite(siteId);

        assertThat(v1.getStatus()).isEqualTo(ContentTypeEntity.STATUS_DEPRECATED);
        assertThat(v2.getStatus()).isEqualTo(ContentTypeEntity.STATUS_DEPRECATED);
        assertThat(v3.getStatus()).isEqualTo(ContentTypeEntity.STATUS_PUBLISHED);
    }

    @Test
    void singlePublishedRowIsUntouched() {
        UUID siteId = UUID.randomUUID();
        ContentTypeEntity only = published(siteId, "news", 1);
        when(typeRepo.findBySiteIdOrderByCodeAsc(siteId)).thenReturn(List.of(only));

        new ContentTypePublishRepairJob(typeRepo, siteRepo).repairSite(siteId);

        assertThat(only.getStatus()).isEqualTo(ContentTypeEntity.STATUS_PUBLISHED);
    }

    @Test
    void alreadyDeprecatedRowsAreIgnored() {
        UUID siteId = UUID.randomUUID();
        ContentTypeEntity stale = published(siteId, "news", 1);
        stale.setStatus(ContentTypeEntity.STATUS_DEPRECATED);
        ContentTypeEntity active = published(siteId, "news", 2);
        when(typeRepo.findBySiteIdOrderByCodeAsc(siteId)).thenReturn(List.of(stale, active));

        new ContentTypePublishRepairJob(typeRepo, siteRepo).repairSite(siteId);

        assertThat(stale.getStatus()).isEqualTo(ContentTypeEntity.STATUS_DEPRECATED);
        assertThat(active.getStatus()).isEqualTo(ContentTypeEntity.STATUS_PUBLISHED);
    }

    private ContentTypeEntity published(UUID siteId, String code, int version) {
        ContentTypeEntity ct = new ContentTypeEntity();
        ct.setId(UUID.randomUUID());
        ct.setSiteId(siteId);
        ct.setCode(code);
        ct.setName(code);
        ct.setVersion(version);
        ct.setStatus(ContentTypeEntity.STATUS_PUBLISHED);
        return ct;
    }
}
