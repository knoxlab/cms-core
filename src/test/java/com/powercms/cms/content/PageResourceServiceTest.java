package com.powercms.cms.content;

import com.powercms.cms.audit.AuditService;
import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.entity.ContentVersionEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.content.repo.ContentVersionRepository;
import com.powercms.cms.delivery.RenderDatasetCache;
import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.common.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PageResourceServiceTest {
    private final ContentItemRepository items = mock(ContentItemRepository.class);
    private final ContentVersionRepository versions = mock(ContentVersionRepository.class);
    private final ContentTypeRepository types = mock(ContentTypeRepository.class);
    private final RenderDatasetCache cache = new RenderDatasetCache(20);
    private final AuditService audit = mock(AuditService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final PageRouteImpactService impacts = mock(PageRouteImpactService.class);
    private final PageMoveRedirectService redirects = mock(PageMoveRedirectService.class);
    private final PageResourceService service = new PageResourceService(items, versions, types, cache, audit, events, impacts, redirects);

    PageResourceServiceTest() {
        when(impacts.scan(any(), any())).thenReturn(PageRouteImpactService.Report.empty());
        when(redirects.preview(any(), any())).thenReturn(PageMoveRedirectService.Plan.empty());
    }

    @Test
    void normalizesCanonicalRoutes() {
        assertEquals("/news/item", PageResourceService.normalizeRoute(" news//item/ "));
        assertEquals("/", PageResourceService.normalizeRoute("/"));
        assertThrows(BusinessException.class, () -> PageResourceService.normalizeRoute("/news/../admin"));
        assertThrows(BusinessException.class, () -> PageResourceService.normalizeRoute("/api/internal"));
        assertThrows(BusinessException.class, () -> PageResourceService.normalizeRoute("/news?q=1"));
        assertThrows(BusinessException.class, () -> PageResourceService.normalizeRoute("/news\\item"));
    }

    @Test
    void rejectsPublishedPageBeforeChangingAnything() {
        UUID siteId = UUID.randomUUID(), pageId = UUID.randomUUID();
        ContentItemEntity page = page(siteId, pageId, ContentItemEntity.STATUS_PUBLISHED, 3);
        stubPage(siteId, pageId, page);

        assertThrows(BusinessException.class, () -> service.update(siteId, pageId, 3, "Title", "/new", null, null, null, false, false, "editor"));
        verify(versions, never()).save(any());
        verify(items, never()).saveAndFlush(any());
    }

    @Test
    void rejectsStaleVersion() {
        UUID siteId = UUID.randomUUID(), pageId = UUID.randomUUID();
        ContentItemEntity page = page(siteId, pageId, ContentItemEntity.STATUS_DRAFT, 4);
        stubPage(siteId, pageId, page);

        assertThrows(BusinessException.class, () -> service.update(siteId, pageId, 3, "Title", "/new", null, null, null, false, false, "editor"));
        verify(versions, never()).save(any());
    }

    @Test
    void copiesCurrentFieldsAndPublishesChangeEvent() {
        UUID siteId = UUID.randomUUID(), pageId = UUID.randomUUID();
        ContentItemEntity page = page(siteId, pageId, ContentItemEntity.STATUS_DRAFT, 2);
        stubPage(siteId, pageId, page);
        ContentVersionEntity current = ContentVersionEntity.create(pageId, 2, "{\"body\":\"kept\"}", 7, "author", "old");
        when(versions.findByContentItemIdAndVersion(pageId, 2)).thenReturn(Optional.of(current));
        when(items.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PageResourceService.UpdateResult result = service.update(siteId, pageId, 2, "Renamed", "/archive/item/", null, null, null, false, false, "editor");

        assertEquals("/archive/item", result.routePath());
        assertEquals(3, result.currentVersion());
        verify(versions).save(argThat(version -> "{\"body\":\"kept\"}".equals(version.getFieldValues()) && version.getVersion() == 3));
        verify(events).publishEvent(any(PageResourceChangedEvent.class));
        verify(audit).write(eq("editor"), eq("PAGE_RESOURCE_UPDATE"), eq("CONTENT_ITEM"), eq(pageId.toString()), eq(siteId), eq("SUCCESS"), anyString());
    }

    @Test
    void createsRedirectDraftOnlyWhenExplicitlyRequested() {
        UUID siteId = UUID.randomUUID(), pageId = UUID.randomUUID();
        ContentItemEntity page = page(siteId, pageId, ContentItemEntity.STATUS_DRAFT, 2);
        stubPage(siteId, pageId, page);
        when(versions.findByContentItemIdAndVersion(pageId, 2)).thenReturn(Optional.of(
            ContentVersionEntity.create(pageId, 2, "{}", 1, "author", "old")));
        when(items.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        PageMoveRedirectService.Plan redirectPlan = new PageMoveRedirectService.Plan(true, List.of(), List.of(
            new PageMoveRedirectService.Proposal(pageId, "move", "^/news/item$", "/archive/item")));
        when(redirects.preview(eq(siteId), any())).thenReturn(redirectPlan);

        service.update(siteId, pageId, 2, "Original", "/archive/item", null, null, null, true, false, "editor");

        verify(redirects).createDrafts(siteId, redirectPlan, "editor");
        var order = inOrder(items, redirects);
        order.verify(items).saveAndFlush(page);
        order.verify(redirects).createDrafts(siteId, redirectPlan, "editor");
    }

    @Test
    void atomicallyWithdrawsPublishedPageAndStoresNewFieldsAndTemplate() {
        UUID siteId = UUID.randomUUID(), pageId = UUID.randomUUID();
        ContentItemEntity page = page(siteId, pageId, ContentItemEntity.STATUS_PUBLISHED, 3);
        stubPage(siteId, pageId, page);
        when(versions.findByContentItemIdAndVersion(pageId, 3)).thenReturn(Optional.of(
            ContentVersionEntity.create(pageId, 3, "{\"old\":true}", 5, "author", "old")));
        when(items.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.update(siteId, pageId, 3, "Changed", "/changed", "/templates/changed.ftl",
            "{\"new\":true}", "edit", false, true, "editor");

        assertEquals(ContentItemEntity.STATUS_DRAFT, result.status());
        assertEquals(4, result.currentVersion());
        assertEquals("/templates/changed.ftl", page.getTemplatePath());
        verify(versions).save(argThat(version -> version.getVersion() == 4 && "{\"new\":true}".equals(version.getFieldValues())));
        verify(items).saveAndFlush(page);
    }

    @Test
    void previewRejectsPublishedDescendantAndTargetConflict() {
        UUID siteId = UUID.randomUUID();
        ContentItemEntity root = page(siteId, UUID.randomUUID(), ContentItemEntity.STATUS_DRAFT, 2);
        root.setRoutePath("/news");
        ContentItemEntity child = page(siteId, UUID.randomUUID(), ContentItemEntity.STATUS_PUBLISHED, 4);
        child.setContentTypeId(root.getContentTypeId());
        child.setRoutePath("/news/item");
        ContentItemEntity occupied = page(siteId, UUID.randomUUID(), ContentItemEntity.STATUS_DRAFT, 1);
        occupied.setContentTypeId(root.getContentTypeId());
        occupied.setRoutePath("/archive/item");
        stubPageTypes(siteId, root);
        when(items.findBySiteId(siteId)).thenReturn(List.of(root, child, occupied));

        PageResourceService.MovePlan plan = service.previewMove(siteId, "/news", "/archive", List.of(
            new PageResourceService.ExpectedPage(root.getId(), 2),
            new PageResourceService.ExpectedPage(child.getId(), 4)));

        assertFalse(plan.valid());
        assertTrue(plan.errors().stream().anyMatch(error -> error.contains("Published page")));
        assertTrue(plan.errors().stream().anyMatch(error -> error.contains("already exists")));
        verify(items, never()).saveAllAndFlush(any());
    }

    @Test
    void previewRejectsStaleOrIncompleteExpectedSet() {
        UUID siteId = UUID.randomUUID();
        ContentItemEntity root = page(siteId, UUID.randomUUID(), ContentItemEntity.STATUS_DRAFT, 2);
        root.setRoutePath("/news");
        ContentItemEntity child = page(siteId, UUID.randomUUID(), ContentItemEntity.STATUS_DRAFT, 3);
        child.setContentTypeId(root.getContentTypeId());
        child.setRoutePath("/news/item");
        stubPageTypes(siteId, root);
        when(items.findBySiteId(siteId)).thenReturn(List.of(root, child));

        PageResourceService.MovePlan plan = service.previewMove(siteId, "/news", "/archive",
            List.of(new PageResourceService.ExpectedPage(root.getId(), 1)));

        assertFalse(plan.valid());
        assertTrue(plan.errors().stream().anyMatch(error -> error.contains("version changed")));
        assertTrue(plan.errors().stream().anyMatch(error -> error.contains("Expected page set")));
    }

    @Test
    void atomicallyMovesSubtreeAndCopiesEveryVersion() {
        UUID siteId = UUID.randomUUID();
        ContentItemEntity root = page(siteId, UUID.randomUUID(), ContentItemEntity.STATUS_DRAFT, 2);
        root.setRoutePath("/news");
        ContentItemEntity child = page(siteId, UUID.randomUUID(), ContentItemEntity.STATUS_DRAFT, 3);
        child.setContentTypeId(root.getContentTypeId());
        child.setRoutePath("/news/item");
        stubPageTypes(siteId, root);
        when(items.findBySiteIdForUpdate(siteId)).thenReturn(List.of(root, child));
        when(versions.findByContentItemIdAndVersion(root.getId(), 2)).thenReturn(Optional.of(
            ContentVersionEntity.create(root.getId(), 2, "{\"root\":true}", 1, "author", "old")));
        when(versions.findByContentItemIdAndVersion(child.getId(), 3)).thenReturn(Optional.of(
            ContentVersionEntity.create(child.getId(), 3, "{\"child\":true}", 1, "author", "old")));
        when(items.saveAllAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PageResourceService.MovePlan result = service.moveSubtree(siteId, "/news", "/archive", List.of(
            new PageResourceService.ExpectedPage(root.getId(), 2),
            new PageResourceService.ExpectedPage(child.getId(), 3)), false, "editor");

        assertTrue(result.valid());
        assertEquals("/archive", root.getRoutePath());
        assertEquals("/archive/item", child.getRoutePath());
        assertEquals(3, root.getCurrentVersion());
        assertEquals(4, child.getCurrentVersion());
        verify(versions, times(2)).save(any(ContentVersionEntity.class));
        verify(items).saveAllAndFlush(argThat(saved -> ((List<?>) saved).size() == 2));
        verify(events, times(2)).publishEvent(any(PageResourceChangedEvent.class));
    }

    private void stubPageTypes(UUID siteId, ContentItemEntity page) {
        ContentTypeEntity type = new ContentTypeEntity();
        type.setId(page.getContentTypeId());
        type.setObjectType("PAGE");
        when(types.findBySiteIdOrderByCodeAsc(siteId)).thenReturn(List.of(type));
    }

    private void stubPage(UUID siteId, UUID pageId, ContentItemEntity page) {
        ContentTypeEntity type = new ContentTypeEntity();
        type.setObjectType("PAGE");
        when(items.findByIdAndSiteIdForUpdate(pageId, siteId)).thenReturn(Optional.of(page));
        when(types.findById(page.getContentTypeId())).thenReturn(Optional.of(type));
    }

    private ContentItemEntity page(UUID siteId, UUID pageId, String status, int version) {
        ContentItemEntity page = new ContentItemEntity();
        page.setId(pageId);
        page.setSiteId(siteId);
        page.setContentTypeId(UUID.randomUUID());
        page.setTitle("Original");
        page.setRoutePath("/news/item");
        page.setTemplatePath("templates/page.ftl");
        page.setStatus(status);
        page.setCurrentVersion(version);
        return page;
    }
}
