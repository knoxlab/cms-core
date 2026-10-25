package com.powercms.cms.delivery;

import com.powercms.cms.identity.permission.AccessControlChangedEvent;
import com.powercms.cms.identity.permission.ObjectPermissionService;
import com.powercms.cms.identity.permission.ObjectType;
import com.powercms.cms.publish.AffectedPageResolver;
import com.powercms.cms.publish.SsgPublishCoordinator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccessInvalidationServiceTest {

    private final RenderDatasetCache cache = mock(RenderDatasetCache.class);
    private final CdnPurge cdn = mock(CdnPurge.class);
    private final AffectedPageResolver pages = mock(AffectedPageResolver.class);
    private final ObjectPermissionService permissions = mock(ObjectPermissionService.class);
    private final SsgPublishCoordinator republish = mock(SsgPublishCoordinator.class);
    private final AccessInvalidationService service =
        new AccessInvalidationService(cache, cdn, pages, permissions, republish);

    @Test
    void applyClearsCachePurgesAffectedRouteAndRebuildsWhenAnonymousViewIsLost() {
        UUID siteId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        when(pages.resolve(siteId, contentId))
            .thenReturn(List.of(new AffectedPageResolver.AffectedPage(contentId, "/news/a")));
        when(permissions.hasAnonymousRight(ObjectType.CONTENT, contentId, ObjectType.VIEW)).thenReturn(false);

        service.apply(new AccessControlChangedEvent(siteId, ObjectType.CONTENT, contentId));

        verify(cache).clear(RenderDatasetCache.Region.CONTENT);
        verify(cdn).purge(siteId, List.of("/news/a"));
        verify(republish).requestRebuild(siteId);
    }

    @Test
    void applyKeepsTheReleaseWhenAnonymousViewRemains() {
        UUID siteId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        when(pages.resolve(siteId, contentId)).thenReturn(List.of());
        when(permissions.hasAnonymousRight(ObjectType.CONTENT, contentId, ObjectType.VIEW)).thenReturn(true);

        service.apply(new AccessControlChangedEvent(siteId, ObjectType.CONTENT, contentId));

        verify(cache).clear(RenderDatasetCache.Region.CONTENT);
        verify(cdn).purge(siteId, List.of("/"));
        verify(republish, never()).requestRebuild(siteId);
    }
}
