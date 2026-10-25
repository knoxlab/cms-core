package com.powercms.cms.content;

import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.entity.ContentVersionEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.content.repo.ContentVersionRepository;
import com.powercms.cms.delivery.navigation.UrlRewriteRuleEntity;
import com.powercms.cms.delivery.navigation.UrlRewriteRuleRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PageRouteImpactServiceTest {
    private final ContentItemRepository items = mock(ContentItemRepository.class);
    private final ContentVersionRepository versions = mock(ContentVersionRepository.class);
    private final UrlRewriteRuleRepository rewrites = mock(UrlRewriteRuleRepository.class);
    private final PageRouteImpactService service = new PageRouteImpactService(items, versions, rewrites);

    @Test
    void findsCurrentContentAndRewriteReferencesWithinSite() {
        UUID siteId = UUID.randomUUID();
        ContentItemEntity item = item(siteId, "/landing", 3);
        when(items.findBySiteId(siteId)).thenReturn(List.of(item));
        when(versions.findByContentItemIdAndVersion(item.getId(), 3)).thenReturn(Optional.of(
            ContentVersionEntity.create(item.getId(), 3, "{\"link\":\"/news/item\"}", 1, "a", "n")));
        UrlRewriteRuleEntity rule = mock(UrlRewriteRuleEntity.class);
        when(rule.getId()).thenReturn(UUID.randomUUID());
        when(rule.getName()).thenReturn("legacy");
        when(rule.getPattern()).thenReturn("^/news/item$");
        when(rule.getReplacement()).thenReturn("/news/item");
        when(rule.getStatus()).thenReturn("ACTIVE");
        when(rewrites.findBySiteIdOrderByPriorityDescNameAsc(siteId)).thenReturn(List.of(rule));

        PageRouteImpactService.Report report = service.scan(siteId, List.of(
            new PageRouteImpactService.RouteChange(UUID.randomUUID(), "/news/item", "/archive/item")));

        assertEquals(1, report.scannedContentItems());
        assertEquals(1, report.contentReferences());
        assertEquals(1, report.rewriteReferences());
        assertEquals(2, report.references().size());
        verify(items).findBySiteId(siteId);
    }

    @Test
    void respectsRouteBoundaries() {
        assertTrue(PageRouteImpactService.containsRoute("{\"url\":\"/news/item\"}", "/news/item"));
        assertFalse(PageRouteImpactService.containsRoute("{\"url\":\"/news/items\"}", "/news/item"));
        assertFalse(PageRouteImpactService.containsRoute("{\"url\":\"/old/news/item\"}", "/news/item"));
    }

    @Test
    void capsContentScanningAndMarksTruncation() {
        UUID siteId = UUID.randomUUID();
        List<ContentItemEntity> many = new ArrayList<>();
        for (int index = 0; index < PageRouteImpactService.MAX_CONTENT_ITEMS + 10; index++) {
            many.add(item(siteId, "/p/" + index, 1));
        }
        when(items.findBySiteId(siteId)).thenReturn(many);
        when(rewrites.findBySiteIdOrderByPriorityDescNameAsc(siteId)).thenReturn(List.of());

        PageRouteImpactService.Report report = service.scan(siteId, List.of(
            new PageRouteImpactService.RouteChange(UUID.randomUUID(), "/news", "/archive")));

        assertEquals(PageRouteImpactService.MAX_CONTENT_ITEMS, report.scannedContentItems());
        assertTrue(report.truncated());
        verify(versions, times(PageRouteImpactService.MAX_CONTENT_ITEMS)).findByContentItemIdAndVersion(any(), eq(1));
    }

    private ContentItemEntity item(UUID siteId, String route, int version) {
        ContentItemEntity item = new ContentItemEntity();
        item.setId(UUID.randomUUID()); item.setSiteId(siteId); item.setContentTypeId(UUID.randomUUID());
        item.setTitle(route); item.setRoutePath(route); item.setCurrentVersion(version);
        return item;
    }
}
