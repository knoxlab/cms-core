package com.powercms.cms.content;

import com.powercms.cms.audit.AuditService;
import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.entity.ContentVersionEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.content.repo.ContentVersionRepository;
import com.powercms.cms.delivery.RenderDatasetCache;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Pattern;

@Service
@Transactional
public class PageResourceService {
    private static final Pattern SAFE_ROUTE = Pattern.compile("/[A-Za-z0-9/_-]*");

    private final ContentItemRepository items;
    private final ContentVersionRepository versions;
    private final ContentTypeRepository types;
    private final RenderDatasetCache cache;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final PageRouteImpactService impacts;
    private final PageMoveRedirectService redirects;

    public PageResourceService(ContentItemRepository items, ContentVersionRepository versions,
                               ContentTypeRepository types, RenderDatasetCache cache, AuditService audit,
                               ApplicationEventPublisher events, PageRouteImpactService impacts,
                               PageMoveRedirectService redirects) {
        this.items = items;
        this.versions = versions;
        this.types = types;
        this.cache = cache;
        this.audit = audit;
        this.events = events;
        this.impacts = impacts;
        this.redirects = redirects;
    }

    public record UpdateResult(UUID id, UUID siteId, String title, String oldRoutePath,
                               String routePath, String status, int currentVersion) {}
    public record ExpectedPage(UUID pageId, int expectedVersion) {}
    public record MoveChange(UUID pageId, String title, String oldRoutePath, String newRoutePath,
                             String status, int currentVersion) {}
    public record MovePlan(String sourcePath, String targetPath, boolean valid,
                           List<String> errors, List<MoveChange> changes, PageRouteImpactService.Report impact,
                           PageMoveRedirectService.Plan redirects) {}

    public UpdateResult update(UUID siteId, UUID pageId, int expectedVersion, String title,
                               String routePath, String templatePath, String fieldValues, String note,
                               boolean createRedirectDraft, boolean withdrawPublished, String actor) {
        ContentItemEntity page = requirePage(items.findByIdAndSiteIdForUpdate(pageId, siteId)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "Page not found in this site")));
        if (ContentItemEntity.STATUS_PUBLISHED.equals(page.getStatus()) && !withdrawPublished) rejectPublished(page);
        rejectStale(page, expectedVersion);

        String normalized = normalizeRoute(routePath);
        if (items.existsBySiteIdAndRoutePathAndIdNot(siteId, normalized, pageId)) {
            throw new BusinessException(ErrorCodes.CONFLICT, "Page route already exists: " + normalized);
        }
        String nextTitle = title == null || title.isBlank() ? page.getTitle() : title.trim();
        String oldRoute = page.getRoutePath();
        String nextTemplate = templatePath == null ? page.getTemplatePath() : templatePath.trim();
        ContentVersionEntity current = versions.findByContentItemIdAndVersion(page.getId(), page.getCurrentVersion())
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "Current page version not found"));
        String nextFields = fieldValues == null ? current.getFieldValues() : fieldValues;
        if (normalized.equals(oldRoute) && nextTitle.equals(page.getTitle())
            && Objects.equals(nextTemplate, page.getTemplatePath()) && Objects.equals(nextFields, current.getFieldValues())
            && !(withdrawPublished && ContentItemEntity.STATUS_PUBLISHED.equals(page.getStatus()))) return result(page, oldRoute);
        PageMoveRedirectService.Plan redirectPlan = redirects.preview(siteId, List.of(
            new PageRouteImpactService.RouteChange(pageId, oldRoute, normalized)));
        if (createRedirectDraft && !redirectPlan.valid()) {
            throw new BusinessException(ErrorCodes.CONFLICT, String.join("; ", redirectPlan.errors()));
        }

        createVersion(page, current, nextFields, note == null || note.isBlank()
            ? "Page resource update: " + oldRoute + " -> " + normalized : note.trim(), actor);
        page.setTitle(nextTitle);
        page.setRoutePath(normalized);
        page.setTemplatePath(nextTemplate);
        if (withdrawPublished && ContentItemEntity.STATUS_PUBLISHED.equals(page.getStatus())) page.setStatus(ContentItemEntity.STATUS_DRAFT);
        try {
            page = items.saveAndFlush(page);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException(ErrorCodes.CONFLICT, "Page route already exists: " + normalized);
        }
        if (createRedirectDraft) redirects.createDrafts(siteId, redirectPlan, actor);
        afterChange(siteId, page, oldRoute, normalized, actor, "PAGE_RESOURCE_UPDATE");
        return result(page, oldRoute);
    }

    @Transactional(readOnly = true)
    public PageRouteImpactService.Report impact(UUID siteId, List<PageRouteImpactService.RouteChange> changes) {
        return impacts.scan(siteId, changes);
    }

    @Transactional(readOnly = true)
    public PageMoveRedirectService.Plan redirectPreview(UUID siteId, List<PageRouteImpactService.RouteChange> changes) {
        return redirects.preview(siteId, changes);
    }

    @Transactional(readOnly = true)
    public MovePlan previewMove(UUID siteId, String sourcePath, String targetPath,
                                List<ExpectedPage> expectedPages) {
        return plan(siteId, normalizeRoute(sourcePath), normalizeRoute(targetPath),
            expectedMap(expectedPages), items.findBySiteId(siteId));
    }

    public MovePlan moveSubtree(UUID siteId, String sourcePath, String targetPath,
                                List<ExpectedPage> expectedPages, boolean createRedirectDrafts, String actor) {
        String source = normalizeRoute(sourcePath);
        String target = normalizeRoute(targetPath);
        List<ContentItemEntity> locked = items.findBySiteIdForUpdate(siteId);
        MovePlan plan = plan(siteId, source, target, expectedMap(expectedPages), locked);
        if (!plan.valid()) throw new BusinessException(ErrorCodes.CONFLICT, String.join("; ", plan.errors()));
        if (createRedirectDrafts && !plan.redirects().valid()) {
            throw new BusinessException(ErrorCodes.CONFLICT, String.join("; ", plan.redirects().errors()));
        }

        Map<UUID, ContentItemEntity> byId = new HashMap<>();
        locked.forEach(item -> byId.put(item.getId(), item));
        for (MoveChange change : plan.changes()) {
            ContentItemEntity page = byId.get(change.pageId());
            copyVersion(page, "Site Browser subtree move: " + change.oldRoutePath() + " -> " + change.newRoutePath(), actor);
            page.setRoutePath(change.newRoutePath());
        }
        try {
            items.saveAllAndFlush(plan.changes().stream().map(change -> byId.get(change.pageId())).toList());
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException(ErrorCodes.CONFLICT, "Subtree target route conflicts with an existing page");
        }
        cache.invalidatePrefix(RenderDatasetCache.Region.CONTENT, "nav:" + siteId + ":");
        for (MoveChange change : plan.changes()) {
            ContentItemEntity page = byId.get(change.pageId());
            cache.invalidatePrefix(RenderDatasetCache.Region.CONTENT, "page:" + page.getId() + ":");
            audit.write(actor, "PAGE_SUBTREE_MOVE", "CONTENT_ITEM", page.getId().toString(), siteId, "SUCCESS",
                "version=" + page.getCurrentVersion() + "; route=" + change.oldRoutePath() + " -> " + change.newRoutePath());
            events.publishEvent(new PageResourceChangedEvent(siteId, page.getId(), change.oldRoutePath(), change.newRoutePath()));
        }
        if (createRedirectDrafts) redirects.createDrafts(siteId, plan.redirects(), actor);
        List<MoveChange> applied = plan.changes().stream().map(change -> {
            ContentItemEntity page = byId.get(change.pageId());
            return new MoveChange(page.getId(), page.getTitle(), change.oldRoutePath(), page.getRoutePath(),
                page.getStatus(), page.getCurrentVersion());
        }).toList();
        return new MovePlan(source, target, true, List.of(), applied, impacts.scan(siteId, routeChanges(applied)),
            createRedirectDrafts ? plan.redirects() : PageMoveRedirectService.Plan.empty());
    }

    private MovePlan plan(UUID siteId, String source, String target, Map<UUID, Integer> expected,
                          List<ContentItemEntity> candidates) {
        List<String> errors = new ArrayList<>();
        if ("/".equals(source)) errors.add("The site root cannot be moved");
        if (target.equals(source) || target.startsWith(source + "/")) errors.add("A subtree cannot be moved into itself");

        Set<UUID> pageTypeIds = new HashSet<>();
        types.findBySiteIdOrderByCodeAsc(siteId).stream()
            .filter(type -> "PAGE".equalsIgnoreCase(type.getObjectType()))
            .forEach(type -> pageTypeIds.add(type.getId()));
        List<ContentItemEntity> moving = candidates.stream()
            .filter(item -> pageTypeIds.contains(item.getContentTypeId()))
            .filter(item -> isInSubtree(item.getRoutePath(), source))
            .sorted(Comparator.comparing(ContentItemEntity::getRoutePath))
            .toList();
        if (moving.isEmpty()) errors.add("No pages exist under the source path");

        Set<UUID> movingIds = new HashSet<>();
        moving.forEach(item -> movingIds.add(item.getId()));
        Set<String> occupied = new HashSet<>();
        candidates.stream().filter(item -> !movingIds.contains(item.getId()) && item.getRoutePath() != null)
            .forEach(item -> occupied.add(item.getRoutePath()));
        Set<String> targets = new HashSet<>();
        List<MoveChange> changes = new ArrayList<>();
        for (ContentItemEntity page : moving) {
            String next = target + page.getRoutePath().substring(source.length());
            next = normalizeRoute(next);
            if (ContentItemEntity.STATUS_PUBLISHED.equals(page.getStatus())) {
                errors.add("Published page must be withdrawn first: " + page.getRoutePath());
            }
            Integer expectedVersion = expected.get(page.getId());
            if (expectedVersion == null || expectedVersion != page.getCurrentVersion()) {
                errors.add("Page version changed or missing: " + page.getRoutePath());
            }
            if (occupied.contains(next) || !targets.add(next)) errors.add("Target route already exists: " + next);
            changes.add(new MoveChange(page.getId(), page.getTitle(), page.getRoutePath(), next,
                page.getStatus(), page.getCurrentVersion()));
        }
        if (!expected.keySet().equals(movingIds)) errors.add("Expected page set does not match the current subtree");
        List<MoveChange> immutableChanges = List.copyOf(changes);
        List<PageRouteImpactService.RouteChange> routeChanges = routeChanges(immutableChanges);
        return new MovePlan(source, target, errors.isEmpty(), List.copyOf(new LinkedHashSet<>(errors)),
            immutableChanges, impacts.scan(siteId, routeChanges), redirects.preview(siteId, routeChanges));
    }

    private ContentItemEntity requirePage(ContentItemEntity page) {
        var type = types.findById(page.getContentTypeId())
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "Page model not found"));
        if (!"PAGE".equalsIgnoreCase(type.getObjectType())) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "Only PAGE resources can be moved or renamed");
        }
        return page;
    }

    private static void rejectPublished(ContentItemEntity page) {
        if (ContentItemEntity.STATUS_PUBLISHED.equals(page.getStatus())) {
            throw new BusinessException(ErrorCodes.CONFLICT, "Published page must be withdrawn and republished after the route change");
        }
    }

    private static void rejectStale(ContentItemEntity page, int expectedVersion) {
        if (page.getCurrentVersion() != expectedVersion) {
            throw new BusinessException(ErrorCodes.CONFLICT,
                "Page version changed; expected v" + expectedVersion + ", current v" + page.getCurrentVersion());
        }
    }

    private void copyVersion(ContentItemEntity page, String note, String actor) {
        ContentVersionEntity current = versions.findByContentItemIdAndVersion(page.getId(), page.getCurrentVersion())
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "Current page version not found"));
        createVersion(page, current, current.getFieldValues(), note, actor);
    }

    private void createVersion(ContentItemEntity page, ContentVersionEntity current, String fieldValues, String note, String actor) {
        int nextVersion = page.getCurrentVersion() + 1;
        versions.save(ContentVersionEntity.create(page.getId(), nextVersion, fieldValues,
            current.getModelVersion(), actor, note));
        page.setCurrentVersion(nextVersion);
    }

    private void afterChange(UUID siteId, ContentItemEntity page, String oldRoute, String newRoute,
                             String actor, String action) {
        cache.invalidatePrefix(RenderDatasetCache.Region.CONTENT, "nav:" + siteId + ":");
        cache.invalidatePrefix(RenderDatasetCache.Region.CONTENT, "page:" + page.getId() + ":");
        audit.write(actor, action, "CONTENT_ITEM", page.getId().toString(), siteId, "SUCCESS",
            "version=" + page.getCurrentVersion() + "; route=" + oldRoute + " -> " + newRoute);
        events.publishEvent(new PageResourceChangedEvent(siteId, page.getId(), oldRoute, newRoute));
    }

    static String normalizeRoute(String input) {
        if (input == null || input.isBlank()) throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "Page route is required");
        String raw = input.trim();
        if (raw.indexOf('\\') >= 0) throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "Page route cannot contain backslashes");
        String route = raw.replaceAll("/{2,}", "/");
        if (!route.startsWith("/")) route = "/" + route;
        if (route.length() > 1 && route.endsWith("/")) route = route.substring(0, route.length() - 1);
        String lower = route.toLowerCase(Locale.ROOT);
        if (route.length() > 1000 || !SAFE_ROUTE.matcher(route).matches() || route.contains("..")
            || lower.startsWith("/api/") || lower.equals("/api")
            || lower.startsWith("/actuator/") || lower.equals("/actuator")
            || lower.startsWith("/admin/") || lower.equals("/admin")) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "Page route contains invalid characters or a reserved path");
        }
        return route;
    }

    private static List<PageRouteImpactService.RouteChange> routeChanges(List<MoveChange> changes) {
        return changes.stream().map(change -> new PageRouteImpactService.RouteChange(
            change.pageId(), change.oldRoutePath(), change.newRoutePath())).toList();
    }

    private static boolean isInSubtree(String route, String source) {
        return route != null && (route.equals(source) || route.startsWith(source + "/"));
    }

    private static Map<UUID, Integer> expectedMap(List<ExpectedPage> pages) {
        Map<UUID, Integer> result = new LinkedHashMap<>();
        if (pages != null) for (ExpectedPage page : pages) {
            if (page != null && page.pageId() != null) result.put(page.pageId(), page.expectedVersion());
        }
        return result;
    }

    private static UpdateResult result(ContentItemEntity page, String oldRoute) {
        return new UpdateResult(page.getId(), page.getSiteId(), page.getTitle(), oldRoute,
            page.getRoutePath(), page.getStatus(), page.getCurrentVersion());
    }
}
