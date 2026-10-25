package com.powercms.cms.delivery;

import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.entity.ContentVersionEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.content.repo.ContentVersionRepository;
import com.powercms.cms.publish.ReleaseService;
import com.powercms.common.error.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

class DeliveryServiceTest {
    private final ReleaseService releases = mock(ReleaseService.class);
    private final ContentItemRepository items = mock(ContentItemRepository.class);
    private final ContentVersionRepository versions = mock(ContentVersionRepository.class);
    private final com.powercms.cms.identity.permission.ObjectPermissionService perms =
        mock(com.powercms.cms.identity.permission.ObjectPermissionService.class);
    private final DeliveryService service = new DeliveryService(releases, items, versions,
        perms,
        new com.powercms.cms.identity.permission.ViewerAccessService(perms,
            mock(com.powercms.cms.identity.repo.UserRepository.class)));

    {
        // 测试默认无打破继承：匿名视图放行
        when(perms.hasAnonymousRight(anyString(), any(), anyString())).thenReturn(true);
    }

    @Test void deployedPublishedVersionIsQueryable() {
        UUID site = UUID.randomUUID(), content = UUID.randomUUID(), category = UUID.randomUUID(), type = UUID.randomUUID(), release = UUID.randomUUID();
        when(releases.getDeployed(site)).thenReturn(new ReleaseService.ReleaseInfo(release, site, "r", null, "DEPLOYED", null, null, List.of()));
        when(releases.getContents(release)).thenReturn(List.of(new ReleaseService.ReleaseContentInfo(content, 2)));
        ContentItemEntity item = new ContentItemEntity(); item.setId(content); item.setSiteId(site); item.setCategoryId(category); item.setContentTypeId(type); item.setTitle("Published"); item.setStatus("PUBLISHED");
        when(items.findById(content)).thenReturn(Optional.of(item));
        ContentVersionEntity version = ContentVersionEntity.create(content, 2, "{\"locale\":\"en\"}", 1, "test", null);
        when(versions.findByContentItemIdAndVersion(content, 2)).thenReturn(Optional.of(version));
        assertEquals(2, service.getById(site, content).version());
    }

    @Test void draftIsInaccessibleEvenIfReferencedByRelease() {
        UUID site = UUID.randomUUID(), content = UUID.randomUUID(), release = UUID.randomUUID();
        when(releases.getDeployed(site)).thenReturn(new ReleaseService.ReleaseInfo(release, site, "r", null, "DEPLOYED", null, null, List.of()));
        when(releases.getContents(release)).thenReturn(List.of(new ReleaseService.ReleaseContentInfo(content, 1)));
        ContentItemEntity item = new ContentItemEntity(); item.setId(content); item.setStatus("DRAFT");
        when(items.findById(content)).thenReturn(Optional.of(item));
        assertThrows(BusinessException.class, () -> service.getById(site, content));
        assertTrue(service.listPublished(site, null, 0, 20).isEmpty());
    }

    // ---- AC-030：匿名对象 view 过滤 ----

    @Test void listFiltersOutContentDeniedToAnonymous() {
        UUID site = UUID.randomUUID(), release = UUID.randomUUID();
        UUID open = UUID.randomUUID(), hidden = UUID.randomUUID();
        when(releases.getDeployed(site)).thenReturn(new ReleaseService.ReleaseInfo(release, site, "r", null, "DEPLOYED", null, null, List.of()));
        when(releases.getContents(release)).thenReturn(List.of(
            new ReleaseService.ReleaseContentInfo(open, 1),
            new ReleaseService.ReleaseContentInfo(hidden, 1)));
        for (UUID id : List.of(open, hidden)) {
            ContentItemEntity item = new ContentItemEntity(); item.setId(id); item.setSiteId(site);
            item.setTitle("t"); item.setStatus("PUBLISHED");
            when(items.findById(id)).thenReturn(Optional.of(item));
            when(versions.findByContentItemIdAndVersion(id, 1)).thenReturn(Optional.of(
                ContentVersionEntity.create(id, 1, "{}", 1, "a", null)));
        }
        // 打破继承后匿名不可见 → 从列表剔除
        when(perms.hasAnonymousRight(com.powercms.cms.identity.permission.ObjectType.CONTENT,
            hidden, com.powercms.cms.identity.permission.ObjectType.VIEW)).thenReturn(false);

        var list = service.listPublished(site, null, 0, 20);
        assertThat(list).extracting(com.powercms.cms.delivery.DeliveryService.DeliveryItem::id)
            .containsExactly(open);
    }

    @Test void getByIdDeniedToAnonymousReturnsNotFound() {
        UUID site = UUID.randomUUID(), content = UUID.randomUUID(), release = UUID.randomUUID();
        when(releases.getDeployed(site)).thenReturn(new ReleaseService.ReleaseInfo(release, site, "r", null, "DEPLOYED", null, null, List.of()));
        when(releases.getContents(release)).thenReturn(List.of(new ReleaseService.ReleaseContentInfo(content, 1)));
        ContentItemEntity item = new ContentItemEntity(); item.setId(content); item.setStatus("PUBLISHED");
        when(items.findById(content)).thenReturn(Optional.of(item));
        when(perms.hasAnonymousRight(anyString(), any(), anyString())).thenReturn(false);
        assertThrows(BusinessException.class, () -> service.getById(site, content));
    }
}
