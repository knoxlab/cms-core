package com.powercms.cms.publish;

import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.delivery.navigation.UrlRewriteRuleEntity;
import com.powercms.cms.delivery.navigation.UrlRewriteRuleRepository;
import com.powercms.cms.media.entity.MediaAssetEntity;
import com.powercms.cms.media.repo.MediaAssetRepository;
import com.powercms.cms.media.repo.MediaVersionRepository;
import com.powercms.cms.publish.entity.ReleaseContentEntity;
import com.powercms.cms.publish.entity.ReleaseEntity;
import com.powercms.cms.publish.entity.ReleaseMediaVersionEntity;
import com.powercms.cms.publish.entity.ReleaseThemeVersionEntity;
import com.powercms.cms.publish.entity.ReleaseUrlRewriteEntity;
import com.powercms.cms.publish.repo.ReleaseContentRepository;
import com.powercms.cms.publish.repo.ReleaseMediaVersionRepository;
import com.powercms.cms.publish.repo.ReleaseRepository;
import com.powercms.cms.publish.repo.ReleaseThemeVersionRepository;
import com.powercms.cms.publish.repo.ReleaseUrlRewriteRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.*;

@Service
@Transactional
public class ReleaseService {
    @Autowired private ApplicationEventPublisher eventPublisher;

    private final ReleaseRepository releaseRepo;
    private final ReleaseContentRepository rcRepo;
    private final ContentItemRepository itemRepo;
    private final com.powercms.cms.publish.repo.ReleasePageRepository pageRepo;
    private final com.powercms.cms.delivery.PageRenderService pageRenderer;
    private final com.powercms.cms.sitepackage.repo.TemplateProjectRepository projectRepo;
    private final com.powercms.cms.sitepackage.repo.SiteThemeRepository themeRepo;
    private final com.powercms.cms.sitepackage.repo.SiteTemplateRepository templateRepo;
    private final com.powercms.cms.sitepackage.repo.SiteContainerRepository containerRepo;
    private final com.powercms.cms.sitepackage.repo.SiteDropZoneRepository zoneRepo;
    private final com.powercms.cms.sitepackage.repo.PageVisualCompositionRepository compositionRepo;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    private final com.powercms.cms.sitepackage.ThemePackageService themePackageService;
    private final com.powercms.cms.sitepackage.repo.SiteThemeRevisionRepository themeRevisionRepo;
    private final ReleaseThemeVersionRepository releaseThemeRepo;
    private final MediaAssetRepository mediaAssets;
    private final MediaVersionRepository mediaVersions;
    private final ReleaseMediaVersionRepository releaseMedia;
    private final UrlRewriteRuleRepository rewriteRules;
    private final ReleaseUrlRewriteRepository releaseRewrites;
    private final com.powercms.cms.identity.permission.ObjectPermissionService objectPermissions;
    private com.powercms.cms.identity.permission.ProgramAccessService programAccess;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setProgramAccess(com.powercms.cms.identity.permission.ProgramAccessService programAccess) {
        this.programAccess = programAccess;
    }

    ReleaseService(ReleaseRepository releaseRepo, ReleaseContentRepository rcRepo, ContentItemRepository itemRepo,
                   com.powercms.cms.publish.repo.ReleasePageRepository pageRepo, com.powercms.cms.delivery.PageRenderService pageRenderer,
                   com.powercms.cms.sitepackage.repo.TemplateProjectRepository projectRepo, com.powercms.cms.sitepackage.repo.SiteThemeRepository themeRepo,
                   com.powercms.cms.sitepackage.repo.SiteTemplateRepository templateRepo, com.powercms.cms.sitepackage.repo.SiteContainerRepository containerRepo,
                   com.powercms.cms.sitepackage.repo.SiteDropZoneRepository zoneRepo, com.powercms.cms.sitepackage.repo.PageVisualCompositionRepository compositionRepo,
                   com.fasterxml.jackson.databind.ObjectMapper mapper, com.powercms.cms.sitepackage.ThemePackageService themePackageService,
                   com.powercms.cms.sitepackage.repo.SiteThemeRevisionRepository themeRevisionRepo, ReleaseThemeVersionRepository releaseThemeRepo,
                   MediaAssetRepository mediaAssets, MediaVersionRepository mediaVersions, ReleaseMediaVersionRepository releaseMedia,
                   UrlRewriteRuleRepository rewriteRules, ReleaseUrlRewriteRepository releaseRewrites,
                   com.powercms.cms.identity.permission.ObjectPermissionService objectPermissions) {
        this.releaseRepo = releaseRepo;
        this.rcRepo = rcRepo;
        this.itemRepo = itemRepo;
        this.pageRepo = pageRepo; this.pageRenderer = pageRenderer; this.projectRepo = projectRepo; this.themeRepo = themeRepo;
        this.templateRepo = templateRepo; this.containerRepo = containerRepo; this.zoneRepo = zoneRepo; this.compositionRepo = compositionRepo; this.mapper = mapper;
        this.themePackageService = themePackageService; this.themeRevisionRepo = themeRevisionRepo; this.releaseThemeRepo = releaseThemeRepo;
        this.mediaAssets = mediaAssets; this.mediaVersions = mediaVersions; this.releaseMedia = releaseMedia;
        this.rewriteRules = rewriteRules; this.releaseRewrites = releaseRewrites;
        this.objectPermissions = objectPermissions;
    }

    public record ReleaseInfo(UUID id, UUID siteId, String name, String description,
                              String status, UUID templateProjectId, Integer templateRevision, String inputDigest, Instant frozenAt, Instant createdAt,
                              List<ReleaseContentInfo> contents) {
        public ReleaseInfo(UUID id, UUID siteId, String name, String description, String status,
                           Instant frozenAt, Instant createdAt, List<ReleaseContentInfo> contents) {
            this(id, siteId, name, description, status, null, null, null, frozenAt, createdAt, contents);
        }
    }

    public record ReleaseContentInfo(UUID contentItemId, int version) {}
    public record ReleaseThemeInfo(UUID revisionId, UUID themeId, int themeVersion, UUID ownerSiteId, String source, String referenceRole, String fileDigest) {}

    public ReleaseInfo createDraft(UUID siteId, String name, String description) {
        var rel = new ReleaseEntity();
        rel.setSiteId(siteId);
        rel.setName(name);
        rel.setDescription(description);
        rel = releaseRepo.save(rel);
        return toInfo(rel);
    }

    /** 冻结 Release（FR-PUBLISH-001）：快照所有已发布内容版本 */
    public ReleaseInfo freeze(UUID releaseId) {
        var rel = require(releaseId);
        if (!ReleaseEntity.STATUS_DRAFT.equals(rel.getStatus())) throw new BusinessException(ErrorCodes.CONFLICT, "\u53ea\u80fd\u51bb\u7ed3\u8349\u7a3f\u72b6\u6001 Release");
        List<ContentItemEntity> published = itemRepo.findBySiteIdAndStatus(rel.getSiteId(), "PUBLISHED").stream()
            .sorted(Comparator.comparing((ContentItemEntity x) -> Objects.toString(x.getRoutePath(), "")).thenComparing(ContentItemEntity::getId)).toList();
        StringBuilder routes = new StringBuilder(), digestInput = new StringBuilder();
        ThemeFreeze frozenThemes = collectUsedThemes(rel, published);
        List<ReleaseThemeVersionEntity> pins = frozenThemes.pinsByRevision().values().stream().sorted(themePinOrder()).toList();
        for (var pin : pins) {
            releaseThemeRepo.save(pin);
            digestInput.append("theme:").append(pin.getThemeRevisionId()).append(':').append(pin.getThemeId()).append('@').append(pin.getThemeVersion())
                .append(':').append(pin.getReferenceRole()).append(':').append(pin.getFileDigest()).append('\n');
        }
        for (var item : published) {
            var rc = new ReleaseContentEntity(); rc.setReleaseId(rel.getId()); rc.setContentItemId(item.getId()); rc.setVersion(item.getPublishedVersion()); rcRepo.save(rc);
            if (item.getRoutePath() != null && !item.getRoutePath().isBlank()) {
                if (!objectPermissions.hasAnonymousRight(
                        com.powercms.cms.identity.permission.ObjectType.CONTENT,
                        item.getId(),
                        com.powercms.cms.identity.permission.ObjectType.VIEW)
                    || (programAccess != null && programAccess.variesByRequest(
                        com.powercms.cms.identity.permission.ObjectType.CONTENT, item.getId()))) {
                    continue;
                }
                ReleaseThemeContext context = frozenThemes.contextByPage().get(item.getId());
                var rendered = context == null ? pageRenderer.renderForRelease(rel.getSiteId(), item.getRoutePath()) : pageRenderer.renderPublishedForRelease(rel.getSiteId(), item.getRoutePath(), context);
                var page = new com.powercms.cms.publish.entity.ReleasePageEntity(); page.setReleaseId(rel.getId()); page.setPageId(item.getId()); page.setContentVersion(rendered.version());
                page.setCompositionVersion(compositionRepo.findByPageId(item.getId()).map(c -> c.getVersion()).orElse(null)); page.setRoutePath(rendered.routePath()); page.setHtmlContent(rendered.html());
                String pageDigest = sha256(rendered.routePath() + "\n" + rendered.html()); page.setInputDigest(pageDigest); pageRepo.save(page);
                routes.append(rendered.routePath()).append('\n'); digestInput.append("page:").append(item.getId()).append(':').append(pageDigest).append('\n');
            }
        }
        var project = projectRepo.findBySiteIdAndProjectType(rel.getSiteId(), "FTL").stream().findFirst().orElse(null);
        if (project != null) { rel.setTemplateProjectId(project.getId()); rel.setTemplateRevision(project.getCurrentRevision()); }
        StringBuilder depsDigest = new StringBuilder();
        for (var pin : pins) {
            depsDigest.append("theme:").append(pin.getThemeRevisionId()).append(':').append(pin.getThemeId()).append('@').append(pin.getThemeVersion())
                .append(':').append(pin.getReferenceRole()).append(':').append(pin.getFileDigest()).append('\n');
        }
        var templates = templateRepo.findBySiteIdOrderByNameAsc(rel.getSiteId());
        var containers = containerRepo.findBySiteIdOrderByNameAsc(rel.getSiteId());
        for (var t : templates) {
            depsDigest.append("template:").append(t.getId()).append(':').append(Objects.toString(t.getThemeId(), ""))
                .append(':').append(Objects.toString(t.getName(), "")).append('\n');
        }
        for (var c : containers) {
            depsDigest.append("container:").append(c.getId()).append(':').append(Objects.toString(c.getName(), "")).append('\n');
        }
        snapshotMedia(rel, depsDigest);
        snapshotRewrites(rel, depsDigest);
        try {
            var snapshot = new LinkedHashMap<String,Object>(); snapshot.put("themes", pins.stream().map(this::themeInfo).toList());
            snapshot.put("templates", templates); snapshot.put("containers", containers);
            Set<UUID> pageIds = published.stream().map(ContentItemEntity::getId).collect(java.util.stream.Collectors.toSet());
            snapshot.put("compositions", compositionRepo.findAll().stream().filter(c -> pageIds.contains(c.getPageId())).sorted(Comparator.comparing(c -> c.getPageId().toString())).toList());
            rel.setSiteSnapshot(mapper.writeValueAsString(snapshot));
        } catch (Exception ex) { throw new BusinessException(ErrorCodes.INTERNAL_ERROR, "\u7ad9\u70b9\u89c6\u89c9\u8d44\u6e90\u5feb\u7167\u5931\u8d25"); }
        rel.setRouteManifest(routes.toString());
        rel.setInputDigest(sha256(digestInput.toString()));
        rel.setGlobalInputDigest(sha256(depsDigest.toString()));
        rel.setStatus(ReleaseEntity.STATUS_FROZEN);
        if(eventPublisher!=null) eventPublisher.publishEvent(new SiteCacheWarmupRequested(rel.getSiteId(), "FREEZE"));
        rel.setFrozenAt(Instant.now()); releaseRepo.save(rel); return toInfo(rel);
    }

    private void snapshotMedia(ReleaseEntity rel, StringBuilder globalDigest) {
        List<MediaAssetEntity> assets = mediaAssets.findBySiteId(rel.getSiteId()).stream()
            .filter(a -> "ACTIVE".equalsIgnoreCase(a.getStatus()))
            .sorted(Comparator.comparing(a -> a.getId().toString()))
            .toList();
        for (MediaAssetEntity asset : assets) {
            var version = mediaVersions.findByMediaAssetIdAndVersion(asset.getId(), asset.getCurrentVersion())
                .orElseThrow(() -> new BusinessException(ErrorCodes.CONFLICT, "媒体版本缺失: " + asset.getId() + "@" + asset.getCurrentVersion()));
            var pin = new ReleaseMediaVersionEntity();
            pin.setReleaseId(rel.getId());
            pin.setMediaAssetId(asset.getId());
            pin.setMediaVersion(version.getVersion());
            pin.setContentHash(version.getContentHash());
            pin.setFilename(version.getFilename());
            pin.setMimeType(version.getMimeType());
            pin.setFileSize(version.getFileSize());
            pin.setStorageKey(version.getStorageKey());
            pin.setOutputPath(ReleaseStaticAssetExporter.outputPath(asset.getId(), version.getFilename()));
            releaseMedia.save(pin);
            globalDigest.append("media:").append(asset.getId()).append('@').append(version.getVersion())
                .append(':').append(version.getContentHash()).append(':').append(pin.getOutputPath()).append('\n');
        }
    }

    private void snapshotRewrites(ReleaseEntity rel, StringBuilder globalDigest) {
        List<UrlRewriteRuleEntity> rules = rewriteRules.findBySiteIdOrderByPriorityDescNameAsc(rel.getSiteId()).stream()
            .filter(r -> "ACTIVE".equalsIgnoreCase(r.getStatus()))
            .toList();
        for (UrlRewriteRuleEntity rule : rules) {
            var pin = new ReleaseUrlRewriteEntity();
            pin.setReleaseId(rel.getId());
            pin.setRuleId(rule.getId());
            pin.setName(rule.getName());
            pin.setPattern(rule.getPattern());
            pin.setReplacement(rule.getReplacement());
            pin.setPriority(rule.getPriority());
            releaseRewrites.save(pin);
            globalDigest.append("rewrite:").append(rule.getId()).append(':').append(rule.getPriority())
                .append(':').append(rule.getPattern()).append("->").append(rule.getReplacement()).append('\n');
        }
    }

    /** 标记为已部署 */
    public ReleaseInfo markDeployed(UUID releaseId) {
        var rel = require(releaseId);
        if (!ReleaseEntity.STATUS_FROZEN.equals(rel.getStatus())) {
            throw new BusinessException(ErrorCodes.CONFLICT, "只能部署已冻结 Release");
        }
        // 取消前一个 DEPLOYED 状态
        releaseRepo.findBySiteIdAndStatus(rel.getSiteId(), ReleaseEntity.STATUS_DEPLOYED)
            .ifPresent(old -> { old.setStatus(ReleaseEntity.STATUS_ROLLED_BACK); releaseRepo.save(old); });

        rel.setStatus(ReleaseEntity.STATUS_DEPLOYED);
        if(eventPublisher!=null)eventPublisher.publishEvent(new SiteCacheWarmupRequested(rel.getSiteId(), "DEPLOY"));
        if(eventPublisher!=null)eventPublisher.publishEvent(new ReleaseDeployRequestedEvent(rel.getId(), rel.getSiteId()));
        releaseRepo.save(rel);
        return toInfo(rel);
    }

    public List<ReleaseInfo> list(UUID siteId) {
        return releaseRepo.findBySiteIdOrderByCreatedAtDesc(siteId).stream()
            .map(this::toInfo).toList();
    }

    /**
     * 索引门禁失败后的回退：DEPLOYED → FROZEN，并恢复前一个 Release 为 DEPLOYED（FR-SEARCH-007）。
     */
    public ReleaseInfo undeploy(UUID releaseId) {
        var rel = require(releaseId);
        if (!ReleaseEntity.STATUS_DEPLOYED.equals(rel.getStatus())) {
            throw new BusinessException(ErrorCodes.CONFLICT, "只能回退已部署 Release");
        }
        rel.setStatus(ReleaseEntity.STATUS_FROZEN);
        releaseRepo.save(rel);
        releaseRepo.findBySiteIdAndStatus(rel.getSiteId(), ReleaseEntity.STATUS_ROLLED_BACK)
            .stream().findFirst()
            .ifPresent(previous -> { previous.setStatus(ReleaseEntity.STATUS_DEPLOYED); releaseRepo.save(previous); });
        return toInfo(rel);
    }

    public ReleaseInfo get(UUID id) { return toInfo(require(id)); }

    public ReleaseInfo getDeployed(UUID siteId) {
        return releaseRepo.findBySiteIdAndStatus(siteId, ReleaseEntity.STATUS_DEPLOYED)
            .map(this::toInfo)
            .orElse(null);
    }

    public List<ReleaseContentInfo> getContents(UUID releaseId) {
        return rcRepo.findByReleaseId(releaseId).stream()
            .map(rc -> new ReleaseContentInfo(rc.getContentItemId(), rc.getVersion())).toList();
    }

    public List<ReleaseThemeInfo> getThemes(UUID releaseId) {
        require(releaseId);
        return releaseThemeRepo.findByReleaseIdOrderByThemeIdAscThemeVersionAsc(releaseId).stream().map(this::themeInfo).toList();
    }

    private ReleaseInfo toInfo(ReleaseEntity rel) {
        var contents = rcRepo.findByReleaseId(rel.getId()).stream()
            .map(rc -> new ReleaseContentInfo(rc.getContentItemId(), rc.getVersion())).toList();
        return new ReleaseInfo(rel.getId(), rel.getSiteId(), rel.getName(), rel.getDescription(),
            rel.getStatus(), rel.getTemplateProjectId(), rel.getTemplateRevision(), rel.getInputDigest(), rel.getFrozenAt(), rel.getCreatedAt(), contents);
    }

    public record ReleasePageInfo(UUID pageId, int contentVersion, Integer compositionVersion,
                                  String routePath, String inputDigest) {}
    public List<ReleasePageInfo> getPages(UUID releaseId) {
        return pageRepo.findByReleaseIdOrderByRoutePathAsc(releaseId).stream()
            .map(p -> new ReleasePageInfo(p.getPageId(), p.getContentVersion(), p.getCompositionVersion(), p.getRoutePath(), p.getInputDigest())).toList();
    }

    private record ThemeFreeze(LinkedHashMap<UUID, ReleaseThemeVersionEntity> pinsByRevision, Map<UUID, ReleaseThemeContext> contextByPage) {}
    private ThemeFreeze collectUsedThemes(ReleaseEntity rel, List<ContentItemEntity> published) {
        LinkedHashMap<UUID,ReleaseThemeVersionEntity> all = new LinkedHashMap<>(); Map<UUID,ReleaseThemeContext> contexts = new HashMap<>();
        for (var item : published) {
            var composition = compositionRepo.findByPageId(item.getId()).orElse(null); if (composition == null || composition.getTemplateId() == null) continue;
            var template = templateRepo.findById(composition.getTemplateId()).orElseThrow(() -> new BusinessException(ErrorCodes.CONFLICT, "\u9875\u9762\u7ec4\u5408\u5f15\u7528\u7684\u6a21\u677f\u4e0d\u5b58\u5728"));
            if (!rel.getSiteId().equals(template.getSiteId())) throw new BusinessException(ErrorCodes.CONFLICT, "\u9875\u9762\u7ec4\u5408\u5f15\u7528\u7684\u6a21\u677f\u4e0d\u5c5e\u4e8e\u5f53\u524d Release \u7ad9\u70b9");
            if (template.getThemeId() == null) continue;
            var available = themePackageService.resolveAvailableTheme(rel.getSiteId(), template.getThemeId());
            int version = "SHARED".equals(available.source()) ? available.pinnedVersion() : available.theme().getCurrentVersion();
            var root = themeRevisionRepo.findByThemeIdAndVersion(template.getThemeId(), version).orElseThrow(() -> new BusinessException(ErrorCodes.CONFLICT, "Release \u65e0\u6cd5\u56fa\u5b9a\u4e3b\u9898\u7248\u672c: " + template.getThemeId() + "@" + version));
            List<ReleaseThemeVersionEntity> pagePins = new ArrayList<>(); collectRevisionChain(rel, root, "ROOT", available.source(), available.ownerSiteId(), pagePins, new HashSet<>(), 0);
            for (var pin : pagePins) { var prior = all.putIfAbsent(pin.getThemeRevisionId(), pin); if (prior != null && "ROOT".equals(pin.getReferenceRole())) prior.setReferenceRole("ROOT"); }
            contexts.put(item.getId(), ReleaseThemeContext.from(pagePins));
        }
        return new ThemeFreeze(all, contexts);
    }
    private void collectRevisionChain(ReleaseEntity rel, com.powercms.cms.sitepackage.entity.SiteThemeRevisionEntity revision, String role, String source, UUID ownerSiteId, List<ReleaseThemeVersionEntity> pins, Set<UUID> visited, int depth) {
        if (depth > 16) throw new BusinessException(ErrorCodes.CONFLICT, "Release \u4e3b\u9898\u7ee7\u627f\u6df1\u5ea6\u8d85\u8fc7 16");
        if (!visited.add(revision.getId())) throw new BusinessException(ErrorCodes.CONFLICT, "Release \u4e3b\u9898 revision \u7ee7\u627f\u5b58\u5728\u5faa\u73af");
        var theme = themeRepo.findById(revision.getThemeId()).orElseThrow(() -> new BusinessException(ErrorCodes.CONFLICT, "Release \u4e3b\u9898\u4e0d\u5b58\u5728: " + revision.getThemeId()));
        if (!ownerSiteId.equals(theme.getSiteId())) throw new BusinessException(ErrorCodes.CONFLICT, "\u4e3b\u9898 revision \u7ee7\u627f\u94fe\u8de8\u8d8a\u4e86\u6240\u5c5e\u7ad9\u70b9");
        var pin = new ReleaseThemeVersionEntity(); pin.setReleaseId(rel.getId()); pin.setThemeRevisionId(revision.getId()); pin.setThemeId(revision.getThemeId()); pin.setThemeVersion(revision.getVersion()); pin.setOwnerSiteId(ownerSiteId); pin.setSource(source); pin.setReferenceRole(role); pin.setFileDigest(revision.getFileDigest()); pins.add(pin);
        if (revision.getParentThemeId() == null && revision.getParentThemeVersion() == null) return;
        if (revision.getParentThemeId() == null || revision.getParentThemeVersion() == null) throw new BusinessException(ErrorCodes.CONFLICT, "\u4e3b\u9898 revision \u7684\u7236\u4e3b\u9898\u5f15\u7528\u4e0d\u5b8c\u6574: " + revision.getId());
        var parent = themeRevisionRepo.findByThemeIdAndVersion(revision.getParentThemeId(), revision.getParentThemeVersion()).orElseThrow(() -> new BusinessException(ErrorCodes.CONFLICT, "Release \u7f3a\u5c11\u7236\u4e3b\u9898 revision: " + revision.getParentThemeId() + "@" + revision.getParentThemeVersion()));
        collectRevisionChain(rel, parent, "PARENT", source, ownerSiteId, pins, visited, depth + 1);
    }
    private static Comparator<ReleaseThemeVersionEntity> themePinOrder() { return Comparator.comparing((ReleaseThemeVersionEntity x) -> x.getThemeId().toString()).thenComparingInt(ReleaseThemeVersionEntity::getThemeVersion).thenComparing(x -> x.getThemeRevisionId().toString()); }
    private ReleaseThemeInfo themeInfo(ReleaseThemeVersionEntity v) { return new ReleaseThemeInfo(v.getThemeRevisionId(), v.getThemeId(), v.getThemeVersion(), v.getOwnerSiteId(), v.getSource(), v.getReferenceRole(), v.getFileDigest()); }
    private static String sha256(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private ReleaseEntity require(UUID id) {
        return releaseRepo.findById(id).orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "Release 不存在"));
    }
}
