package com.powercms.cms.content;

import com.powercms.cms.delivery.navigation.UrlRewriteRuleEntity;
import com.powercms.cms.delivery.navigation.UrlRewriteRuleRepository;
import com.powercms.cms.delivery.navigation.UrlRewriteService;
import com.powercms.common.error.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Service
@Transactional
public class PageMoveRedirectService {
    static final int PRIORITY = 1000;
    static final int MAX_DRAFTS = 200;

    private final UrlRewriteRuleRepository rules;
    private final UrlRewriteService rewrites;

    public PageMoveRedirectService(UrlRewriteRuleRepository rules, UrlRewriteService rewrites) {
        this.rules = rules;
        this.rewrites = rewrites;
    }

    public record Proposal(UUID pageId, String name, String pattern, String replacement) {}
    public record Plan(boolean valid, List<String> errors, List<Proposal> proposals) {
        static Plan empty() { return new Plan(true, List.of(), List.of()); }
    }

    @Transactional(readOnly = true)
    public Plan preview(UUID siteId, List<PageRouteImpactService.RouteChange> changes) {
        if (changes == null || changes.isEmpty()) return Plan.empty();
        List<String> errors = new ArrayList<>();
        List<Proposal> proposals = new ArrayList<>();
        List<UrlRewriteRuleEntity> existing = rules.findBySiteIdOrderByPriorityDescNameAsc(siteId);
        Set<String> names = new HashSet<>();
        existing.forEach(rule -> names.add(rule.getName()));

        List<PageRouteImpactService.RouteChange> effective = changes.stream()
            .filter(Objects::nonNull)
            .filter(change -> change.oldRoutePath() != null && change.newRoutePath() != null)
            .filter(change -> !change.oldRoutePath().equals(change.newRoutePath()))
            .distinct().toList();
        if (effective.size() > MAX_DRAFTS) errors.add("At most " + MAX_DRAFTS + " redirect drafts can be created per move");

        for (var change : effective.stream().limit(MAX_DRAFTS).toList()) {
            String oldRoute = PageResourceService.normalizeRoute(change.oldRoutePath());
            String newRoute = PageResourceService.normalizeRoute(change.newRoutePath());
            String name = draftName(change.pageId(), oldRoute);
            if (!names.add(name)) errors.add("Redirect draft name already exists: " + name);
            for (var rule : existing) {
                if (matches(rule.getPattern(), oldRoute)) {
                    errors.add("Existing URL rewrite already matches old route " + oldRoute + ": " + rule.getName());
                }
            }
            try {
                String resolved = rewrites.test(siteId, newRoute, true).output();
                if (oldRoute.equals(resolved)) errors.add("Redirect would create a loop: " + oldRoute + " -> " + newRoute);
            } catch (BusinessException ex) {
                errors.add("Target route has an invalid rewrite chain: " + newRoute);
            }
            proposals.add(new Proposal(change.pageId(), name, exactPattern(oldRoute), newRoute));
        }
        return new Plan(errors.isEmpty(), List.copyOf(new LinkedHashSet<>(errors)), List.copyOf(proposals));
    }

    public List<UrlRewriteService.Info> createDrafts(UUID siteId, Plan plan, String actor) {
        if (plan == null || !plan.valid()) throw new IllegalArgumentException("A valid redirect plan is required");
        List<UrlRewriteService.Info> created = new ArrayList<>();
        for (Proposal proposal : plan.proposals()) {
            created.add(rewrites.save(siteId, null, proposal.name(), proposal.pattern(), proposal.replacement(),
                PRIORITY, "DRAFT", null, actor));
        }
        return List.copyOf(created);
    }

    static String exactPattern(String route) { return "^" + Pattern.quote(route) + "$"; }

    private static boolean matches(String pattern, String route) {
        try { return pattern != null && Pattern.compile(pattern).matcher(route).matches(); }
        catch (PatternSyntaxException ignored) { return false; }
    }

    private static String draftName(UUID pageId, String oldRoute) {
        String suffix = pageId == null ? Integer.toUnsignedString(oldRoute.hashCode(), 36)
            : pageId.toString().substring(0, 8);
        String name = "Page move redirect " + oldRoute + " [" + suffix + "]";
        return name.length() <= 200 ? name : name.substring(0, 200);
    }
}
