package com.powercms.cms.content;

import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.content.repo.ContentVersionRepository;
import com.powercms.cms.delivery.navigation.UrlRewriteRuleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@Transactional(readOnly = true)
public class PageRouteImpactService {
    static final int MAX_CONTENT_ITEMS = 500;
    static final int MAX_REWRITE_RULES = 500;
    static final int MAX_ROUTE_CHANGES = 200;
    static final int MAX_REFERENCES = 200;

    private final ContentItemRepository items;
    private final ContentVersionRepository versions;
    private final UrlRewriteRuleRepository rewrites;

    public PageRouteImpactService(ContentItemRepository items, ContentVersionRepository versions,
                                  UrlRewriteRuleRepository rewrites) {
        this.items = items;
        this.versions = versions;
        this.rewrites = rewrites;
    }

    public record RouteChange(UUID pageId, String oldRoutePath, String newRoutePath) {}
    public record Reference(String kind, String objectId, String title, String matchedRoute, String detail) {}
    public record Report(int scannedContentItems, int contentReferences, int rewriteReferences,
                         boolean truncated, List<Reference> references) {
        public static Report empty() { return new Report(0, 0, 0, false, List.of()); }
    }

    public Report scan(UUID siteId, List<RouteChange> changes) {
        List<RouteChange> effective = changes == null ? List.of() : changes.stream()
            .filter(Objects::nonNull)
            .filter(change -> change.oldRoutePath() != null && !change.oldRoutePath().equals(change.newRoutePath()))
            .distinct()
            .limit(MAX_ROUTE_CHANGES)
            .toList();
        if (effective.isEmpty()) return Report.empty();

        List<Reference> found = new ArrayList<>();
        int contentRefs = 0;
        int rewriteRefs = 0;
        List<ContentItemEntity> siteItems = items.findBySiteId(siteId);
        int scanCount = Math.min(siteItems.size(), MAX_CONTENT_ITEMS);
        boolean truncated = siteItems.size() > MAX_CONTENT_ITEMS;

        for (int index = 0; index < scanCount; index++) {
            ContentItemEntity item = siteItems.get(index);
            String fields = versions.findByContentItemIdAndVersion(item.getId(), item.getCurrentVersion())
                .map(version -> version.getFieldValues()).orElse("");
            for (RouteChange change : effective) {
                if (!containsRoute(fields, change.oldRoutePath())) continue;
                contentRefs++;
                if (found.size() < MAX_REFERENCES) {
                    found.add(new Reference("CONTENT_FIELD", item.getId().toString(), item.getTitle(),
                        change.oldRoutePath(), "currentVersion=" + item.getCurrentVersion()));
                } else truncated = true;
            }
        }

        List<com.powercms.cms.delivery.navigation.UrlRewriteRuleEntity> siteRules =
            rewrites.findBySiteIdOrderByPriorityDescNameAsc(siteId);
        if (siteRules.size() > MAX_REWRITE_RULES) truncated = true;
        for (var rule : siteRules.stream().limit(MAX_REWRITE_RULES).toList()) {
            String haystack = String.valueOf(rule.getPattern()) + "\n" + String.valueOf(rule.getReplacement());
            for (RouteChange change : effective) {
                if (!containsRoute(haystack, change.oldRoutePath())) continue;
                rewriteRefs++;
                if (found.size() < MAX_REFERENCES) {
                    found.add(new Reference("URL_REWRITE", rule.getId().toString(), rule.getName(),
                        change.oldRoutePath(), "status=" + rule.getStatus()));
                } else truncated = true;
            }
        }
        return new Report(scanCount, contentRefs, rewriteRefs, truncated, List.copyOf(found));
    }

    static boolean containsRoute(String text, String route) {
        if (text == null || text.isEmpty() || route == null || route.isEmpty()) return false;
        int from = 0;
        while ((from = text.indexOf(route, from)) >= 0) {
            int end = from + route.length();
            boolean leftBoundary = from == 0 || !isRouteChar(text.charAt(from - 1));
            boolean rightBoundary = end == text.length() || !isRouteChar(text.charAt(end));
            if (leftBoundary && rightBoundary) return true;
            from++;
        }
        return false;
    }

    private static boolean isRouteChar(char value) {
        return Character.isLetterOrDigit(value) || value == '/' || value == '_' || value == '-';
    }
}
