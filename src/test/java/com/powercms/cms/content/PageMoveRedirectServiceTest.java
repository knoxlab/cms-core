package com.powercms.cms.content;

import com.powercms.cms.delivery.navigation.UrlRewriteRuleEntity;
import com.powercms.cms.delivery.navigation.UrlRewriteRuleRepository;
import com.powercms.cms.delivery.navigation.UrlRewriteService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PageMoveRedirectServiceTest {
    private final UrlRewriteRuleRepository rules = mock(UrlRewriteRuleRepository.class);
    private final UrlRewriteService rewrites = mock(UrlRewriteService.class);
    private final PageMoveRedirectService service = new PageMoveRedirectService(rules, rewrites);

    @Test
    void proposesExactDraftRedirects() {
        UUID siteId = UUID.randomUUID(), pageId = UUID.randomUUID();
        when(rules.findBySiteIdOrderByPriorityDescNameAsc(siteId)).thenReturn(List.of());
        when(rewrites.test(siteId, "/archive/item", true)).thenReturn(
            new UrlRewriteService.TestResult("/archive/item", "/archive/item", 0, List.of()));

        PageMoveRedirectService.Plan plan = service.preview(siteId, List.of(
            new PageRouteImpactService.RouteChange(pageId, "/news/item", "/archive/item")));

        assertTrue(plan.valid());
        assertEquals(1, plan.proposals().size());
        assertEquals("^\\Q/news/item\\E$", plan.proposals().getFirst().pattern());
        assertEquals("/archive/item", plan.proposals().getFirst().replacement());
    }

    @Test
    void rejectsExistingRuleThatAlreadyMatchesOldRoute() {
        UUID siteId = UUID.randomUUID();
        UrlRewriteRuleEntity existing = mock(UrlRewriteRuleEntity.class);
        when(existing.getId()).thenReturn(UUID.randomUUID()); when(existing.getName()).thenReturn("legacy");
        when(existing.getPattern()).thenReturn("^/news/.*$");
        when(rules.findBySiteIdOrderByPriorityDescNameAsc(siteId)).thenReturn(List.of(existing));
        when(rewrites.test(siteId, "/archive/item", true)).thenReturn(
            new UrlRewriteService.TestResult("/archive/item", "/archive/item", 0, List.of()));

        var plan = service.preview(siteId, List.of(
            new PageRouteImpactService.RouteChange(UUID.randomUUID(), "/news/item", "/archive/item")));

        assertFalse(plan.valid());
        assertTrue(plan.errors().stream().anyMatch(error -> error.contains("already matches")));
    }

    @Test
    void rejectsLoopThroughExistingActiveChain() {
        UUID siteId = UUID.randomUUID();
        when(rules.findBySiteIdOrderByPriorityDescNameAsc(siteId)).thenReturn(List.of());
        when(rewrites.test(siteId, "/archive/item", true)).thenReturn(
            new UrlRewriteService.TestResult("/archive/item", "/news/item", 1, List.of("back")));

        var plan = service.preview(siteId, List.of(
            new PageRouteImpactService.RouteChange(UUID.randomUUID(), "/news/item", "/archive/item")));

        assertFalse(plan.valid());
        assertTrue(plan.errors().stream().anyMatch(error -> error.contains("loop")));
    }

    @Test
    void createsDraftRulesOnly() {
        UUID siteId = UUID.randomUUID();
        PageMoveRedirectService.Proposal proposal = new PageMoveRedirectService.Proposal(
            UUID.randomUUID(), "move", "^/old$", "/new");
        when(rewrites.save(eq(siteId), isNull(), anyString(), anyString(), anyString(), anyInt(), eq("DRAFT"), isNull(), eq("editor")))
            .thenReturn(new UrlRewriteService.Info(UUID.randomUUID(), "move", "^/old$", "/new", 1000, "DRAFT", 0));

        var created = service.createDrafts(siteId, new PageMoveRedirectService.Plan(true, List.of(), List.of(proposal)), "editor");

        assertEquals("DRAFT", created.getFirst().status());
        verify(rewrites).save(eq(siteId), isNull(), eq("move"), eq("^/old$"), eq("/new"), eq(1000), eq("DRAFT"), isNull(), eq("editor"));
    }
}
