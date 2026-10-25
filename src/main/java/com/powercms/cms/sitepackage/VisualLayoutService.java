package com.powercms.cms.sitepackage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.powercms.cms.audit.AuditService;
import com.powercms.cms.delivery.FreeMarkerModelContract;
import com.powercms.cms.delivery.FreeMarkerSandbox;
import com.powercms.cms.delivery.RenderDatasetCache;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.cms.sitepackage.entity.*;
import com.powercms.cms.sitepackage.repo.*;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

@Service
@Transactional
public class VisualLayoutService {
    private static final Pattern CSS_CLASS = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*");
    private static final Set<String> MODES = Set.of("VISUAL", "CODE");
    private static final Set<String> SIDEBAR_POSITIONS = Set.of("NONE", "LEFT", "RIGHT");
    private static final Set<Integer> SIDEBAR_WIDTHS = Set.of(20, 30, 40);
    private static final Set<String> STATUSES = Set.of("DRAFT", "PUBLISHED", "ARCHIVED");
    private static final Set<String> FRAGMENT_OVERRIDE_KEYS = Set.of("htmlHead", "header", "footer");
    private static final int MAX_FRAGMENT_OVERRIDE_BYTES = 256 * 1024;

    /** 页面专属布局（匿名模板）的名称前缀，与 dotCMS 的 anonymous_layout_ 语义对齐。 */
    public static final String ANONYMOUS_PREFIX = "anonymous_layout_";

    /** 匿名模板不是可复用模板，靠名称前缀识别（无独立数据库标志位）。 */
    public static boolean isAnonymousName(String name) {
        return name != null && name.startsWith(ANONYMOUS_PREFIX);
    }

    private final SiteThemeRepository themeRepo;
    private final ThemePackageService themePackageService;
    private final SiteTemplateRepository templateRepo;
    private final SiteContainerRepository containerRepo;
    private final SiteContainerRevisionRepository containerRevisionRepo;
    private final SiteContainerStructureRepository containerStructureRepo;
    private final ContentTypeRepository contentTypeRepo;
    private final SiteDropZoneRepository zoneRepo;
    private final SiteTemplateRevisionRepository revisionRepo;
    private final SiteTemplateAclRepository aclRepo;
    private final PageVisualCompositionRepository compositionRepo;
    private final AuditService auditService;
    private final RenderDatasetCache datasetCache;
    private final ObjectMapper mapper;
    private final FreeMarkerSandbox sandbox;

    VisualLayoutService(SiteThemeRepository themeRepo, ThemePackageService themePackageService, SiteTemplateRepository templateRepo,
                        SiteContainerRepository containerRepo, SiteContainerRevisionRepository containerRevisionRepo,
                        SiteContainerStructureRepository containerStructureRepo, ContentTypeRepository contentTypeRepo,
                        SiteDropZoneRepository zoneRepo,
                        SiteTemplateRevisionRepository revisionRepo, SiteTemplateAclRepository aclRepo,
                        PageVisualCompositionRepository compositionRepo, AuditService auditService,
                        RenderDatasetCache datasetCache, ObjectMapper mapper, FreeMarkerSandbox sandbox) {
        this.themeRepo = themeRepo;
        this.themePackageService = themePackageService;
        this.templateRepo = templateRepo;
        this.containerRepo = containerRepo;
        this.containerRevisionRepo = containerRevisionRepo;
        this.containerStructureRepo = containerStructureRepo;
        this.contentTypeRepo = contentTypeRepo;
        this.zoneRepo = zoneRepo;
        this.revisionRepo = revisionRepo;
        this.aclRepo = aclRepo;
        this.compositionRepo = compositionRepo;
        this.auditService = auditService;
        this.datasetCache = datasetCache;
        this.mapper = mapper;
        this.sandbox = sandbox;
    }

    public record ThemeInfo(UUID id, UUID siteId, UUID ownerSiteId, String source, boolean shared, boolean readOnly, Integer pinnedVersion,
                            String name, String description, String config, String status, String manifestVersion, String vendor,
                            String cmsVersionRange, String validity, String warnings, int currentVersion, UUID parentThemeId,
                            Integer parentVersion, Instant createdAt, Instant updatedAt) {
        static ThemeInfo from(ThemePackageService.AvailableTheme a) {
            SiteThemeEntity e=a.theme();
            return new ThemeInfo(e.getId(), e.getSiteId(), a.ownerSiteId(), a.source(), a.readOnly(), a.readOnly(), a.pinnedVersion(),
                e.getName(), e.getDescription(), e.getConfig(), e.getStatus(), e.getManifestVersion(), e.getVendor(), e.getCmsVersionRange(),
                e.getValidity(), e.getWarnings(), e.getCurrentVersion(), e.getParentThemeId(), e.getParentVersion(), e.getCreatedAt(), e.getUpdatedAt());
        }
        static ThemeInfo owned(SiteThemeEntity e) { return from(new ThemePackageService.AvailableTheme(e,"OWNED",e.getSiteId(),false,null)); }
    }

    public record TemplateInfo(UUID id, UUID siteId, UUID themeId, String name, String description,
                               String layoutJson, String status, int currentVersion,
                               List<ZoneInfo> zones, boolean anonymous, Instant createdAt, Instant updatedAt) {
        static TemplateInfo from(SiteTemplateEntity e, List<ZoneInfo> zones) {
            return new TemplateInfo(e.getId(), e.getSiteId(), e.getThemeId(), e.getName(),
                e.getDescription(), e.getLayoutJson(), e.getStatus(), e.getCurrentVersion(), zones,
                isAnonymousName(e.getName()), e.getCreatedAt(), e.getUpdatedAt());
        }
    }

    public record ContainerStructureInput(UUID structureId, String code, int sortOrder) {}

    public record ContainerStructureInfo(UUID id, UUID structureId, String contentTypeVar,
                                         String contentTypeName, String code, int sortOrder) {}

    public record ContainerInfo(UUID id, UUID siteId, String name, String allowedComponentTypes,
                                String config, int currentVersion, List<ContainerStructureInfo> containerStructures,
                                Instant createdAt, Instant updatedAt) {}

    public record ZoneInfo(UUID id, UUID templateId, String zoneKey, UUID containerId,
                           int sortOrder, String config) {
        static ZoneInfo from(SiteDropZoneEntity e) {
            return new ZoneInfo(e.getId(), e.getTemplateId(), e.getZoneKey(),
                e.getContainerId(), e.getSortOrder(), e.getConfig());
        }
    }

    public record ContainerRevisionInfo(UUID id, UUID containerId, int version, Integer parentVersion,
                                        String author, String message, Instant createdAt) {
        static ContainerRevisionInfo from(SiteContainerRevisionEntity e) { return new ContainerRevisionInfo(
            e.getId(), e.getContainerId(), e.getVersion(), e.getParentVersion(), e.getAuthor(), e.getMessage(), e.getCreatedAt()); }
    }

    public record RevisionInfo(UUID id, UUID templateId, int version, Integer parentVersion,
                               String author, String message, Instant createdAt) {
        static RevisionInfo from(SiteTemplateRevisionEntity e) {
            return new RevisionInfo(e.getId(), e.getTemplateId(), e.getVersion(), e.getParentVersion(),
                e.getAuthor(), e.getMessage(), e.getCreatedAt());
        }
    }

    public record AclEntry(String roleCode, boolean canView, boolean canUse, boolean canEdit, boolean canPublish) {}

    public record TemplateDocument(UUID id, UUID siteId, UUID themeId, String name, String description,
                                   String status, int currentVersion, String layoutJson,
                                   List<ZoneInfo> zones, List<AclEntry> permissions,
                                   Instant createdAt, Instant updatedAt) {}

    public record DiffEntry(String path, String change) {}

    public List<ThemeInfo> themes(UUID siteId) {
        return themes(siteId, false);
    }

    public List<ThemeInfo> themes(UUID siteId, boolean includeUnrecognized) {
        return themePackageService.themesAvailableToSite(siteId, includeUnrecognized).stream().map(ThemeInfo::from).toList();
    }

    public ThemeInfo getTheme(UUID siteId, UUID id) {
        return ThemeInfo.from(themePackageService.resolveAvailableTheme(siteId, id));
    }

    public ThemeInfo saveTheme(UUID siteId, UUID id, String name, String description,
                               String config, String status) {
        JsonNode parsedConfig = parseObject(config, "主题配置");
        boolean creating = id == null;
        var entity = creating ? new SiteThemeEntity()
            : themeRepo.findById(id).orElseThrow(() -> missing("主题不存在"));
        if (id != null && !siteId.equals(entity.getSiteId())) throw missing("主题不属于当前站点");
        entity.setSiteId(siteId);
        entity.setName(name);
        entity.setDescription(description);
        entity.setConfig(write(parsedConfig));
        entity.setStatus(status == null ? "DRAFT" : status);
        // DEF-M15-002：site_theme.validity 列默认 LEGACY（历史/导入行口径），站内新建主题若不置
        // VALID，紧接着创建的模板绑定该主题即被「主题必须处于 VALID 状态后才能绑定」拒绝——
        // 视频平台预置安装因此 400 中断，管理端手工建主题同样无法绑模板。新建主题即可绑定。
        if (creating) entity.setValidity("VALID");
        return ThemeInfo.owned(themeRepo.save(entity));
    }

    public List<TemplateInfo> templates(UUID siteId, UUID themeId, String q) {
        List<SiteTemplateEntity> list;
        if (themeId != null) list = templateRepo.findBySiteIdAndThemeIdOrderByNameAsc(siteId, themeId);
        else if (q != null && !q.isBlank()) list = templateRepo.findBySiteIdAndNameContainingIgnoreCaseOrderByNameAsc(siteId, q.trim());
        else list = templateRepo.findBySiteIdOrderByNameAsc(siteId);
        return list.stream().filter(t -> !isAnonymousName(t.getName()))
            .map(entity -> TemplateInfo.from(entity, zones(entity.getId()))).toList();
    }

    public TemplateInfo getTemplate(UUID siteId, UUID id) {
        return TemplateInfo.from(requireTemplate(siteId, id), zones(id));
    }

    public TemplateInfo saveTemplate(UUID siteId, UUID id, UUID themeId, String name,
                                     String description, String layoutJson, String status,
                                     String author, String message) {
        boolean creating = id == null;
        var entity = creating ? new SiteTemplateEntity()
            : templateRepo.findById(id).orElseThrow(() -> missing("模板不存在"));
        if (!creating && !siteId.equals(entity.getSiteId())) throw missing("模板不属于当前站点");
        if (themeId != null) {
            var theme = themePackageService.resolveAvailableTheme(siteId, themeId).theme();
            if (!"VALID".equals(theme.getValidity())) throw validation("主题必须处于 VALID 状态后才能绑定");
        }
        String nextStatus = status == null ? (creating ? "DRAFT" : entity.getStatus()) : status.toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(nextStatus)) throw validation("模板状态必须是 DRAFT、PUBLISHED 或 ARCHIVED");
        entity.setSiteId(siteId);
        entity.setThemeId(themeId);
        entity.setName(name);
        entity.setDescription(description);
        entity.setLayoutJson(normalizeLayout(layoutJson));
        entity.setStatus(nextStatus);
        try {
            entity = templateRepo.saveAndFlush(entity);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new BusinessException(ErrorCodes.CONTENT_VERSION_CONFLICT, "模板已被他人修改，请刷新后重试");
        }
        snapshot(entity, author, message == null || message.isBlank()
            ? (creating ? "创建模板" : "更新模板") : message);
        return TemplateInfo.from(entity, zones(entity.getId()));
    }

    /**
     * 页面级布局保存（对齐 dotCMS 的 Custom Page Layout）：
     * - 源模板已是匿名布局：原位更新其 layoutJson，模板标识不变。
     * - 源模板是命名模板：复制一份页面专属匿名布局（含投放区），返回新模板标识；
     *   原命名模板及其它引用页面不受影响。
     */
    public TemplateInfo savePageLayout(UUID siteId, UUID sourceTemplateId, String layoutJson,
                                       String author, String message) {
        var source = requireTemplate(siteId, sourceTemplateId);
        if (isAnonymousName(source.getName())) {
            return saveTemplate(siteId, sourceTemplateId, source.getThemeId(), source.getName(),
                source.getDescription(), layoutJson, source.getStatus(), author,
                message == null || message.isBlank() ? "更新页面布局" : message);
        }
        var clone = new SiteTemplateEntity();
        clone.setSiteId(siteId);
        clone.setThemeId(source.getThemeId());
        clone.setName(ANONYMOUS_PREFIX + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8));
        clone.setDescription(source.getDescription());
        clone.setLayoutJson(normalizeLayout(layoutJson));
        clone.setStatus(source.getStatus());
        clone = templateRepo.saveAndFlush(clone);
        for (var zone : zoneRepo.findByTemplateIdOrderBySortOrderAsc(sourceTemplateId)) {
            var copy = new SiteDropZoneEntity();
            copy.setTemplateId(clone.getId());
            copy.setZoneKey(zone.getZoneKey());
            copy.setContainerId(zone.getContainerId());
            copy.setSortOrder(zone.getSortOrder());
            copy.setConfig(zone.getConfig());
            zoneRepo.save(copy);
        }
        snapshot(clone, author, "从模板 \"" + source.getName() + "\" 创建页面专属布局");
        return TemplateInfo.from(clone, zones(clone.getId()));
    }

    public TemplateInfo saveZone(UUID siteId, UUID templateId, UUID id, String key,
                                 UUID containerId, int order, String config, String author) {
        var template = requireTemplate(siteId, templateId);
        if (containerId != null) {
            containerRepo.findById(containerId).filter(x -> siteId.equals(x.getSiteId()))
                .orElseThrow(() -> missing("容器不属于当前站点"));
        }
        ObjectNode normalizedConfig = normalizeZoneConfig(config);
        var entity = id == null ? new SiteDropZoneEntity()
            : zoneRepo.findById(id).orElseThrow(() -> missing("投放区不存在"));
        if (id != null && !templateId.equals(entity.getTemplateId())) throw missing("投放区不属于当前模板");
        entity.setTemplateId(template.getId());
        entity.setZoneKey(key);
        entity.setContainerId(containerId);
        entity.setSortOrder(order);
        entity.setConfig(write(normalizedConfig));
        zoneRepo.save(entity);
        snapshot(template, author, id == null ? "新增投放区 " + key : "更新投放区 " + key);
        return TemplateInfo.from(template, zones(template.getId()));
    }

    @Transactional
    public TemplateInfo deleteZone(UUID siteId, UUID templateId, UUID zoneId, String author) {
        var template = requireTemplate(siteId, templateId);
        var zone = zoneRepo.findById(zoneId).orElseThrow(() -> missing("\u6295\u653e\u533a\u4e0d\u5b58\u5728"));
        if (!templateId.equals(zone.getTemplateId())) throw missing("\u6295\u653e\u533a\u4e0d\u5c5e\u4e8e\u5f53\u524d\u6a21\u677f");
        String key = zone.getZoneKey();

        var compositions = compositionRepo.findByTemplateId(templateId);
        Map<PageVisualCompositionEntity, ObjectNode> parsedCompositions = new LinkedHashMap<>();
        Set<UUID> referencedPages = new HashSet<>();
        int referencedCompositions = 0;
        for (var composition : compositions) {
            ObjectNode compositionZones;
            try {
                JsonNode parsed = mapper.readTree(composition.getZonesJson());
                if (parsed == null || !parsed.isObject()) throw new IllegalArgumentException();
                compositionZones = (ObjectNode) parsed;
            } catch (Exception ex) {
                throw new BusinessException(ErrorCodes.CONFLICT, "\u9875\u9762\u7ec4\u5408\u6570\u636e\u4e0d\u662f\u5408\u6cd5 JSON\uff0c\u65e0\u6cd5\u5b89\u5168\u5220\u9664\u6295\u653e\u533a");
            }
            JsonNode components = compositionZones.get(key);
            if (components != null && !components.isArray()) {
                throw new BusinessException(ErrorCodes.CONFLICT, "\u9875\u9762\u7ec4\u5408\u4e2d\u7684\u6295\u653e\u533a\u6570\u636e\u683c\u5f0f\u65e0\u6548\uff0c\u65e0\u6cd5\u5b89\u5168\u5220\u9664\u6295\u653e\u533a");
            }
            if (components != null && !components.isEmpty()) {
                referencedCompositions++;
                if (composition.getPageId() != null) referencedPages.add(composition.getPageId());
            }
            parsedCompositions.put(composition, compositionZones);
        }
        if (referencedCompositions > 0) {
            throw new BusinessException(ErrorCodes.CONFLICT,
                "\u6295\u653e\u533a\u4ecd\u88ab " + referencedPages.size() + " \u4e2a\u9875\u9762\u7684 " + referencedCompositions + " \u4e2a\u9875\u9762\u7ec4\u5408\u5f15\u7528\uff0c\u65e0\u6cd5\u5220\u9664");
        }

        String actor = author == null ? "system" : author;
        for (var entry : parsedCompositions.entrySet()) {
            if (entry.getValue().remove(key) == null) continue;
            var composition = entry.getKey();
            composition.setZonesJson(write(entry.getValue()));
            composition.setVersion(composition.getVersion() + 1);
            composition.setUpdatedBy(actor);
            compositionRepo.save(composition);
        }

        zoneRepo.delete(zone);
        zoneRepo.flush();
        snapshot(template, author, "\u5220\u9664\u6295\u653e\u533a " + key);
        auditService.write(actor, "TEMPLATE_ZONE_DELETE", "VISUAL_TEMPLATE",
            templateId.toString(), siteId, "SUCCESS", "\u5220\u9664\u6295\u653e\u533a " + key + " (" + zoneId + ")");
        return TemplateInfo.from(template, zones(templateId));
    }

    public void deleteTemplate(UUID siteId, UUID id, String actor) {
        var template = requireTemplate(siteId, id);
        long refs = compositionRepo.countByTemplateId(id);
        if (refs > 0) {
            throw new BusinessException(ErrorCodes.CONFLICT,
                "模板仍被 " + refs + " 个页面组合引用，无法删除");
        }
        aclRepo.deleteByTemplateId(id);
        revisionRepo.findByTemplateIdOrderByVersionDesc(id).forEach(revisionRepo::delete);
        zoneRepo.findByTemplateIdOrderBySortOrderAsc(id).forEach(zoneRepo::delete);
        templateRepo.delete(template);
        auditService.write(actor, "TEMPLATE_DELETE", "VISUAL_TEMPLATE", id.toString(), siteId,
            "SUCCESS", "删除模板 " + template.getName());
    }

    /**
     * 计划 A：校验并规范化布局表示（不落库、不产生修订），供 MCP dry-run 与调用方预检复用。
     * 返回规范化 JSON（补全 columns/rows/boxes id 与派生 mode），异常与保存路径完全同口径。
     */
    public String validateLayout(String layoutJson) {
        return normalizeLayout(layoutJson);
    }

    public TemplateDocument document(UUID siteId, UUID id) {
        var template = requireTemplate(siteId, id);
        return new TemplateDocument(template.getId(), template.getSiteId(), template.getThemeId(),
            template.getName(), template.getDescription(), template.getStatus(),
            template.getCurrentVersion(), template.getLayoutJson(), zones(id),
            listAcl(id), template.getCreatedAt(), template.getUpdatedAt());
    }

    public List<RevisionInfo> revisions(UUID siteId, UUID templateId) {
        requireTemplate(siteId, templateId);
        return revisionRepo.findByTemplateIdOrderByVersionDesc(templateId).stream()
            .map(RevisionInfo::from).toList();
    }

    public RevisionInfo getRevision(UUID siteId, UUID templateId, int version) {
        requireTemplate(siteId, templateId);
        return RevisionInfo.from(revisionRepo.findByTemplateIdAndVersion(templateId, version)
            .orElseThrow(() -> missing("模板版本不存在")));
    }

    public String revisionSnapshot(UUID siteId, UUID templateId, int version) {
        requireTemplate(siteId, templateId);
        return revisionRepo.findByTemplateIdAndVersion(templateId, version)
            .orElseThrow(() -> missing("模板版本不存在")).getSnapshot();
    }

    public TemplateInfo restore(UUID siteId, UUID templateId, int version, String author) {
        var template = requireTemplate(siteId, templateId);
        var revision = revisionRepo.findByTemplateIdAndVersion(templateId, version)
            .orElseThrow(() -> missing("模板版本不存在"));
        Map<String, Object> snap = parseMap(revision.getSnapshot());
        template.setName(String.valueOf(snap.getOrDefault("name", template.getName())));
        template.setDescription(snap.get("description") == null ? null : String.valueOf(snap.get("description")));
        UUID restoredThemeId = parseUuid(snap.get("themeId"));
        if (restoredThemeId != null) themePackageService.resolveAvailableTheme(siteId, restoredThemeId);
        template.setThemeId(restoredThemeId);
        template.setStatus(String.valueOf(snap.getOrDefault("status", template.getStatus())));
        Object layoutValue = snap.getOrDefault("layoutJson", Map.of());
        template.setLayoutJson(normalizeLayout(layoutValue instanceof String s ? s : write(layoutValue)));
        zoneRepo.findByTemplateIdOrderBySortOrderAsc(templateId).forEach(zoneRepo::delete);
        Object zonesObj = snap.get("zones");
        if (zonesObj instanceof List<?> zoneList) {
            int order = 0;
            for (Object item : zoneList) {
                if (!(item instanceof Map<?, ?> zoneMap)) continue;
                var zone = new SiteDropZoneEntity();
                zone.setTemplateId(templateId);
                zone.setZoneKey(String.valueOf(zoneMap.get("zoneKey")));
                zone.setContainerId(parseUuid(zoneMap.get("containerId")));
                Object sort = zoneMap.get("sortOrder");
                zone.setSortOrder(sort instanceof Number n ? n.intValue() : order * 10);
                Object cfg = zoneMap.get("config");
                if (cfg == null) cfg = Map.of();
                zone.setConfig(cfg instanceof String s ? (s.isBlank() ? "{}" : s) : write(cfg));
                zoneRepo.save(zone);
                order++;
            }
        }
        try {
            template = templateRepo.saveAndFlush(template);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new BusinessException(ErrorCodes.CONTENT_VERSION_CONFLICT, "模板已被他人修改，请刷新后重试");
        }
        snapshot(template, author, "恢复到版本 " + version);
        return TemplateInfo.from(template, zones(templateId));
    }

    public List<DiffEntry> diff(UUID siteId, UUID templateId, int v1, int v2) {
        requireTemplate(siteId, templateId);
        String s1 = revisionRepo.findByTemplateIdAndVersion(templateId, v1)
            .orElseThrow(() -> missing("版本不存在: " + v1)).getSnapshot();
        String s2 = revisionRepo.findByTemplateIdAndVersion(templateId, v2)
            .orElseThrow(() -> missing("版本不存在: " + v2)).getSnapshot();
        Map<String, Object> a = flatten("", parseMap(s1));
        Map<String, Object> b = flatten("", parseMap(s2));
        Set<String> keys = new TreeSet<>();
        keys.addAll(a.keySet());
        keys.addAll(b.keySet());
        List<DiffEntry> diffs = new ArrayList<>();
        for (String key : keys) {
            String left = Objects.toString(a.get(key), "");
            String right = Objects.toString(b.get(key), "");
            if (!Objects.equals(left, right)) {
                if (!a.containsKey(key)) diffs.add(new DiffEntry(key, "ADDED"));
                else if (!b.containsKey(key)) diffs.add(new DiffEntry(key, "DELETED"));
                else diffs.add(new DiffEntry(key, "MODIFIED"));
            }
        }
        return diffs;
    }

    public List<AclEntry> listAcl(UUID templateId) {
        return aclRepo.findByTemplateIdOrderByRoleCodeAsc(templateId).stream()
            .map(e -> new AclEntry(e.getRoleCode(), e.isCanView(), e.isCanUse(), e.isCanEdit(), e.isCanPublish()))
            .toList();
    }

    public List<AclEntry> replaceAcl(UUID siteId, UUID templateId, List<AclEntry> entries, String actor) {
        requireTemplate(siteId, templateId);
        List<AclEntry> before = listAcl(templateId);
        aclRepo.deleteByTemplateId(templateId);
        if (entries != null) {
            for (AclEntry entry : entries) {
                if (entry.roleCode() == null || entry.roleCode().isBlank()) continue;
                var entity = new SiteTemplateAclEntity();
                entity.setTemplateId(templateId);
                entity.setRoleCode(entry.roleCode().trim().toLowerCase(Locale.ROOT));
                entity.setCanView(entry.canView());
                entity.setCanUse(entry.canUse());
                entity.setCanEdit(entry.canEdit());
                entity.setCanPublish(entry.canPublish());
                aclRepo.save(entity);
            }
        }
        List<AclEntry> after = listAcl(templateId);
        auditService.write(actor, "TEMPLATE_ACL_UPDATE", "VISUAL_TEMPLATE", templateId.toString(), siteId,
            "SUCCESS", "before=" + write(before) + "; after=" + write(after));
        return after;
    }

    public List<ContainerInfo> containers(UUID siteId) {
        return containerRepo.findBySiteIdOrderByNameAsc(siteId).stream().map(this::containerInfo).toList();
    }

    public ContainerInfo getContainer(UUID siteId, UUID id) { return containerInfo(requireContainer(siteId, id)); }

    /** Compatibility entry point for presets and older clients; converts legacy UUID/displayTemplates JSON. */
    public ContainerInfo saveContainer(UUID siteId, UUID id, String name, String allowed, String config,
                                       String author, String message) {
        List<UUID> ids = parseUuidArray(allowed, "允许组件类型");
        ObjectNode normalized = normalizeContainerConfig(config);
        JsonNode displays = normalized.path("displayTemplates");
        if (!ids.isEmpty() && !normalized.has("maxContents")) normalized.put("maxContents", 50);
        List<ContainerStructureInput> structures = new ArrayList<>();
        for (int index = 0; index < ids.size(); index++) {
            UUID typeId = ids.get(index);
            structures.add(new ContainerStructureInput(typeId, displays.path(typeId.toString()).asText(""), index * 10));
        }
        return saveContainer(siteId, id, name, normalized.toString(), structures, author, message);
    }

    public ContainerInfo saveContainer(UUID siteId, UUID id, String name, String config,
                                       List<ContainerStructureInput> structures, String author, String message) {
        ObjectNode normalized = normalizeContainerConfig(config);
        int maxContents = integer(normalized, "maxContents", 0);
        if (maxContents < 0) throw validation("Max Contents 不能小于 0");
        normalized.put("maxContents", maxContents);
        List<ContainerStructureInput> requested = structures == null ? List.of() : structures;
        if (maxContents == 0 && !requested.isEmpty()) throw validation("Max Contents 为 0 时不能绑定 Content Type");
        if (maxContents > 0 && requested.isEmpty()) throw validation("Max Contents 大于 0 时至少绑定一个 Content Type");
        Set<UUID> unique = new LinkedHashSet<>();
        for (ContainerStructureInput input : requested) {
            if (input == null || input.structureId() == null || !unique.add(input.structureId()))
                throw validation("ContainerStructure 的 Content Type 不能为空或重复");
            var type = contentTypeRepo.findById(input.structureId()).orElseThrow(() -> validation("绑定的 Content Type 不存在"));
            if (!siteId.equals(type.getSiteId())) throw validation("不能绑定其他站点的 Content Type");
            assertNoFtlPitfalls(input.code(), "Content Type「" + type.getCode() + "」的容器结构 code");
        }
        var entity = id == null ? new SiteContainerEntity()
            : containerRepo.findById(id).orElseThrow(() -> missing("容器不存在"));
        if (id != null && !siteId.equals(entity.getSiteId())) throw missing("容器不属于当前站点");
        entity.setSiteId(siteId); entity.setName(name); entity.setAllowedComponentTypes(write(unique));
        normalized.remove("displayTemplates"); entity.setConfig(write(normalized));
        var saved = containerRepo.save(entity); containerRepo.flush();
        if (saved.getId() == null) saved.setId(UUID.randomUUID());
        replaceContainerStructures(saved.getId(), requested);
        snapshotContainer(saved, author, message == null || message.isBlank() ? (id == null ? "创建容器" : "更新容器") : message);
        datasetCache.invalidate(RenderDatasetCache.Region.CONTAINER, saved.getId().toString());
        return containerInfo(saved);
    }

    private void replaceContainerStructures(UUID containerId, List<ContainerStructureInput> requested) {
        containerStructureRepo.deleteByContainerId(containerId); containerStructureRepo.flush();
        int fallbackOrder = 0;
        for (ContainerStructureInput input : requested) {
            SiteContainerStructureEntity entity = new SiteContainerStructureEntity();
            entity.setContainerId(containerId); entity.setStructureId(input.structureId());
            entity.setCode(input.code() == null ? "" : input.code());
            entity.setSortOrder(input.sortOrder() >= 0 ? input.sortOrder() : fallbackOrder);
            containerStructureRepo.save(entity); fallbackOrder += 10;
        }
    }

    private ContainerInfo containerInfo(SiteContainerEntity entity) {
        List<ContainerStructureInfo> structures = containerStructureRepo.findByContainerIdOrderBySortOrderAsc(entity.getId()).stream()
            .map(link -> structureInfo(link)).toList();
        if (structures.isEmpty()) structures = legacyStructures(entity);
        return new ContainerInfo(entity.getId(), entity.getSiteId(), entity.getName(), entity.getAllowedComponentTypes(),
            entity.getConfig(), entity.getCurrentVersion(), structures, entity.getCreatedAt(), entity.getUpdatedAt());
    }


    private ContainerStructureInfo structureInfo(SiteContainerStructureEntity link) {
        var type = contentTypeRepo.findById(link.getStructureId()).orElse(null);
        return new ContainerStructureInfo(link.getId(), link.getStructureId(),
            type == null ? link.getStructureId().toString() : type.getCode(),
            type == null ? "已删除的 Content Type" : type.getName(), link.getCode(), link.getSortOrder());
    }

    private List<ContainerStructureInfo> legacyStructures(SiteContainerEntity entity) {
        try {
            List<UUID> allowed = parseUuidArray(entity.getAllowedComponentTypes(), "允许组件类型");
            JsonNode displays = parseObject(entity.getConfig(), "容器配置").path("displayTemplates");
            List<ContainerStructureInfo> result = new ArrayList<>();
            for (int index = 0; index < allowed.size(); index++) {
                UUID typeId = allowed.get(index); var type = contentTypeRepo.findById(typeId).orElse(null);
                result.add(new ContainerStructureInfo(null, typeId, type == null ? typeId.toString() : type.getCode(),
                    type == null ? "已删除的 Content Type" : type.getName(), displays.path(typeId.toString()).asText(""), index * 10));
            }
            return List.copyOf(result);
        } catch (RuntimeException ignored) { return List.of(); }
    }

    public List<ContainerRevisionInfo> containerRevisions(UUID siteId, UUID containerId) {
        requireContainer(siteId, containerId);
        return containerRevisionRepo.findByContainerIdOrderByVersionDesc(containerId).stream().map(ContainerRevisionInfo::from).toList();
    }

    public ContainerInfo restoreContainer(UUID siteId, UUID containerId, int version, String author) {
        var container = requireContainer(siteId, containerId);
        var revision = containerRevisionRepo.findByContainerIdAndVersion(containerId, version)
            .orElseThrow(() -> missing("容器版本不存在"));
        JsonNode snapshot = parseObject(revision.getSnapshot(), "容器配置");
        container.setName(snapshot.path("name").asText(container.getName()));
        container.setAllowedComponentTypes(write(parseUuidArray(snapshot.path("allowedComponentTypes").toString(), "允许组件类型")));
        container.setConfig(write(normalizeContainerConfig(snapshot.path("config").toString())));
        List<ContainerStructureInput> restoredStructures = new ArrayList<>();
        for (JsonNode link : snapshot.path("containerStructures")) restoredStructures.add(new ContainerStructureInput(
            UUID.fromString(link.path("structureId").asText()), link.path("code").asText(""), link.path("sortOrder").asInt(0)));
        replaceContainerStructures(containerId, restoredStructures);
        snapshotContainer(container, author, "恢复到 v" + version);
        datasetCache.invalidate(RenderDatasetCache.Region.CONTAINER, containerId.toString());
        return containerInfo(container);
    }

    private void snapshotContainer(SiteContainerEntity container, String author, String message) {
        int next = container.getCurrentVersion() + 1;
        Integer parent = container.getCurrentVersion() > 0 ? container.getCurrentVersion() : null;
        ObjectNode snapshot = mapper.createObjectNode();
        snapshot.put("name", container.getName());
        try { snapshot.set("allowedComponentTypes", mapper.readTree(container.getAllowedComponentTypes())); } catch (Exception ex) { snapshot.put("allowedComponentTypes", container.getAllowedComponentTypes()); }
        try { snapshot.set("config", mapper.readTree(container.getConfig())); } catch (Exception ex) { snapshot.put("config", container.getConfig()); }
        ArrayNode structures = snapshot.putArray("containerStructures");
        for (SiteContainerStructureEntity link : containerStructureRepo.findByContainerIdOrderBySortOrderAsc(container.getId())) {
            ObjectNode item = structures.addObject(); item.put("structureId", link.getStructureId().toString()); item.put("code", link.getCode()); item.put("sortOrder", link.getSortOrder());
        }
        containerRevisionRepo.save(SiteContainerRevisionEntity.create(container.getId(), next, parent, author == null ? "system" : author, message, write(snapshot)));
        container.setCurrentVersion(next); containerRepo.save(container);
    }

    private SiteContainerEntity requireContainer(UUID siteId, UUID id) {
        return containerRepo.findById(id).filter(container -> siteId.equals(container.getSiteId()))
            .orElseThrow(() -> missing("容器不属于当前站点"));
    }

    private void snapshot(SiteTemplateEntity template, String author, String message) {
        int next = template.getCurrentVersion() + 1;
        Integer parent = template.getCurrentVersion() > 0 ? template.getCurrentVersion() : null;
        ObjectNode doc = mapper.createObjectNode();
        doc.put("id", template.getId().toString());
        doc.put("siteId", template.getSiteId().toString());
        if (template.getThemeId() != null) doc.put("themeId", template.getThemeId().toString());
        else doc.putNull("themeId");
        doc.put("name", template.getName());
        doc.put("description", template.getDescription());
        doc.put("status", template.getStatus());
        doc.put("currentVersion", next);
        try {
            doc.set("layoutJson", mapper.readTree(template.getLayoutJson()));
        } catch (Exception ex) {
            doc.put("layoutJson", template.getLayoutJson());
        }
        ArrayNode zoneArr = mapper.createArrayNode();
        for (ZoneInfo zone : zones(template.getId())) {
            ObjectNode z = mapper.createObjectNode();
            z.put("id", zone.id().toString());
            z.put("zoneKey", zone.zoneKey());
            if (zone.containerId() != null) z.put("containerId", zone.containerId().toString());
            else z.putNull("containerId");
            z.put("sortOrder", zone.sortOrder());
            try { z.set("config", mapper.readTree(zone.config())); }
            catch (Exception ex) { z.put("config", zone.config()); }
            zoneArr.add(z);
        }
        doc.set("zones", zoneArr);
        revisionRepo.save(SiteTemplateRevisionEntity.create(
            template.getId(), next, parent, author == null ? "system" : author, message, write(doc)));
        template.setCurrentVersion(next);
        templateRepo.save(template);
    }

    private SiteTemplateEntity requireTemplate(UUID siteId, UUID id) {
        return templateRepo.findById(id).filter(t -> siteId.equals(t.getSiteId()))
            .orElseThrow(() -> missing("模板不属于当前站点"));
    }

    /**
     * 落库前 CODE 试渲染校验（回归缺陷 ②）：引用不存在命名空间（如 {@code @powercms.zone}）或
     * 顶层对象拼写错误的代码模板，绑定到页面后渲染期直接 500，此前保存无任何校验。
     *
     * <p>两步判定：① 静态解析（语法错误 / 未知标签直接拒绝）；②「宽松数据 + 严格顶层」试渲染
     * （{@link FreeMarkerModelContract}），仅当报错指向**未定义的顶层对象**时拒绝——数据字段
     * 缺失属正常数据依赖（落库前没有真实数据模型），一律放行给渲染期。</p>
     */
    private void validateCodeRenderingSource(String code) {
        if (code == null || code.isBlank()) return;
        String name = "visual-template-check.ftl";
        FreeMarkerSandbox.ParseResult parsed = sandbox.parse(name, code);
        if (!parsed.valid()) {
            throw validation("可视化模板代码语法错误" + position(parsed) + ": " + parsed.message());
        }
        FreeMarkerSandbox.ParseResult rendered =
            sandbox.renderCheck(name, code, FreeMarkerModelContract.stubModel());
        if (!rendered.valid()) {
            String root = FreeMarkerModelContract.missingRoot(rendered.message());
            if (root != null && !FreeMarkerModelContract.ROOTS.contains(root)) {
                throw validation("可视化模板代码引用了未定义的对象 \"" + root
                    + "\"（绑定后渲染将失败）" + position(rendered) + ": " + rendered.message());
            }
            // 其余错误按数据依赖放行（如数据字段的裸引用），交给渲染期以真实数据执行
        }
    }

    private static String position(FreeMarkerSandbox.ParseResult result) {
        return result.line() == null ? ""
            : "（行 " + result.line() + (result.column() == null ? "" : " 列 " + result.column()) + "）";
    }

    private String normalizeLayout(String raw) {
        ObjectNode layout = parseObject(raw, "模板布局");
        // 计划 A（单实体两表示）：mode 支持「显式优先 + 派生」——未显式传入时由 rows（布局表示）与
        // code（代码表示）派生，对齐 dotCMS 以 drawedBody 反推 drawed 的语义。两者在渲染期互斥：
        // CODE 只以 code 为渲染源，VISUAL 只以 rows 为渲染源（存储非破坏，显式 mode 仍以调用方为准）。
        String explicitMode = layout.hasNonNull("mode")
            ? text(layout, "mode", "VISUAL").toUpperCase(Locale.ROOT) : null;
        if (explicitMode != null && !MODES.contains(explicitMode))
            throw validation("模板模式必须是 VISUAL 或 CODE");
        layout.put("columns", 12);
        layout.put("header", bool(layout, "header", true));
        layout.put("footer", bool(layout, "footer", true));
        String sidebar = text(layout, "sidebar", "NONE").toUpperCase(Locale.ROOT);
        if (!SIDEBAR_POSITIONS.contains(sidebar)) throw validation("侧边栏必须是 NONE、LEFT 或 RIGHT");
        layout.put("sidebar", sidebar);
        int sidebarWidth = integer(layout, "sidebarWidth", 30);
        if (!SIDEBAR_WIDTHS.contains(sidebarWidth)) throw validation("侧边栏宽度只允许 20、30 或 40");
        layout.put("sidebarWidth", sidebarWidth);
        JsonNode overridesNode = layout.get("fragmentOverrides");
        ObjectNode overrides = mapper.createObjectNode();
        if (overridesNode != null && !overridesNode.isNull()) {
            if (!overridesNode.isObject()) throw validation("fragmentOverrides \u5fc5\u987b\u662f\u5bf9\u8c61");
            overridesNode.fields().forEachRemaining(entry -> {
                if (!FRAGMENT_OVERRIDE_KEYS.contains(entry.getKey())) throw validation("\u4e0d\u652f\u6301\u7684\u7247\u6bb5\u8986\u76d6: " + entry.getKey());
                if (!entry.getValue().isTextual()) throw validation("\u7247\u6bb5\u8986\u76d6\u5fc5\u987b\u662f FreeMarker \u5b57\u7b26\u4e32: " + entry.getKey());
                String source = entry.getValue().asText();
                if (!source.isBlank()) {
                    if (source.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_FRAGMENT_OVERRIDE_BYTES)
                        throw validation("\u7247\u6bb5\u8986\u76d6\u8d85\u8fc7 256 KiB: " + entry.getKey());
                    assertNoFtlPitfalls(source, "\u7247\u6bb5\u8986\u76d6 " + entry.getKey());
                    overrides.put(entry.getKey(), source);
                }
            });
        }
        layout.set("fragmentOverrides", overrides);
        String code = text(layout, "code", "");
        // 回归缺陷 ②：CODE 非空即试渲染校验（无论显式/派生 mode）——坏代码模板一旦绑到页面，
        // 渲染期直接 500；保存时拦截成本最低。
        validateCodeRenderingSource(code);
        JsonNode rowsNode = layout.get("rows");
        ArrayNode rows = mapper.createArrayNode();
        if (rowsNode != null && !rowsNode.isArray()) throw validation("模板 rows 必须是数组");
        if (rowsNode != null) {
            int rowIndex = 0;
            for (JsonNode rowNode : rowsNode) {
                if (!rowNode.isObject()) throw validation("第 " + (rowIndex + 1) + " 行配置无效");
                ObjectNode row = (ObjectNode) rowNode.deepCopy();
                row.put("id", text(row, "id", UUID.randomUUID().toString()));
                validateClasses(row.get("classes"), "行 CSS 类");
                JsonNode boxesNode = row.get("boxes");
                if (boxesNode == null || !boxesNode.isArray()) throw validation("每行必须包含 boxes 数组");
                ArrayNode boxes = mapper.createArrayNode();
                int total = 0;
                int boxIndex = 0;
                for (JsonNode boxNode : boxesNode) {
                    if (!boxNode.isObject()) throw validation("第 " + (rowIndex + 1) + " 行 Box 配置无效");
                    ObjectNode box = (ObjectNode) boxNode.deepCopy();
                    int width = integer(box, "width", 12);
                    if (width < 1 || width > 12) throw validation("Box 宽度必须在 1 到 12 之间");
                    total += width;
                    if (total > 12) throw validation("同一行 Box 宽度总和不能超过 12");
                    box.put("id", text(box, "id", UUID.randomUUID().toString()));
                    box.put("width", width);
                    validateClasses(box.get("classes"), "Box CSS 类");
                    boxes.add(box);
                    boxIndex++;
                }
                if (boxIndex == 0) throw validation("每行至少包含一个 Box");
                row.set("boxes", boxes);
                rows.add(row);
                rowIndex++;
            }
        }
        layout.set("rows", rows);
        String mode = explicitMode != null ? explicitMode
            : (rows.isEmpty() && !code.isBlank() ? "CODE" : "VISUAL");
        if ("CODE".equals(mode)) {
            if (code.isBlank()) throw validation("CODE 模式必须填写 FreeMarker 模板代码");
            assertNoFtlPitfalls(code, "模板代码 (CODE 模式)");
            layout.put("code", code);
        }
        layout.put("mode", mode);
        return write(layout);
    }

    private ObjectNode normalizeContainerConfig(String raw) {
        ObjectNode config = parseObject(raw, "容器配置");
        String type = text(config, "type", "CONTENT").toUpperCase(Locale.ROOT);
        if (!Set.of("CONTENT", "ZERO_CONTENT").contains(type))
            throw validation("容器类型必须是 CONTENT 或 ZERO_CONTENT");
        config.put("type", type);
        config.put("preLoopFtl", text(config, "preLoopFtl", ""));
        config.put("postLoopFtl", text(config, "postLoopFtl", ""));
        config.put("zeroContentFtl", text(config, "zeroContentFtl", ""));
        JsonNode displays = config.get("displayTemplates");
        if (displays == null) config.set("displayTemplates", mapper.createObjectNode());
        else if (!displays.isObject()) throw validation("displayTemplates 必须是组件类型 ID 到 FTL 的对象映射");
        assertNoFtlPitfalls(text(config, "preLoopFtl", ""), "preLoopFtl");
        assertNoFtlPitfalls(text(config, "postLoopFtl", ""), "postLoopFtl");
        assertNoFtlPitfalls(text(config, "zeroContentFtl", ""), "zeroContentFtl");
        JsonNode displaysForCheck = config.get("displayTemplates");
        var displayKeys = displaysForCheck.fieldNames();
        while (displayKeys.hasNext()) {
            String typeId = displayKeys.next();
            assertNoFtlPitfalls(displaysForCheck.path(typeId).asText(""), "displayTemplates[" + typeId + "]");
        }
        if ("ZERO_CONTENT".equals(type) && text(config, "zeroContentFtl", "").isBlank())
            throw validation("零内容容器必须配置 zeroContentFtl");
        return config;
    }

    /**
     * 拦截 FreeMarker 默认值优先级陷阱（{@code (x)!"d" != "e"}）。
     *
     * <p>与 {@code #include} 的治理口径一致：这类写法语法合法、解析绿灯，但渲染期必然失败，
     * 必须在校验期把「预览才炸」提前成「保存即拒」并给出可行动改写建议。
     */
    private void assertNoFtlPitfalls(String ftl, String fieldLabel) {
        if (ftl == null || ftl.isBlank()) return;
        List<String> issues = TemplateSecurityValidator.checkExpressionPitfalls(ftl);
        if (!issues.isEmpty()) throw validation(fieldLabel + "：" + String.join("；", issues));
    }

    private ObjectNode normalizeZoneConfig(String raw) {
        ObjectNode config = parseObject(raw, "投放区配置");
        int width = integer(config, "width", 12);
        if (width < 1 || width > 12) throw validation("投放区宽度必须在 1 到 12 之间");
        config.put("width", width);
        validateClasses(config.get("classes"), "投放区 CSS 类");
        return config;
    }

    private void validateClasses(JsonNode classes, String label) {
        if (classes == null || classes.isNull()) return;
        if (!classes.isArray()) throw validation(label + "必须是数组");
        for (JsonNode value : classes) {
            if (!value.isTextual() || !CSS_CLASS.matcher(value.asText()).matches())
                throw validation(label + "仅允许字母、数字、下划线和连字符");
        }
    }

    private ObjectNode parseObject(String raw, String label) {
        if (raw == null || raw.isBlank()) return mapper.createObjectNode();
        try {
            JsonNode value = mapper.readTree(raw);
            if (value.isTextual()) value = mapper.readTree(value.asText());
            if (!value.isObject()) throw validation(label + "必须是 JSON 对象");
            return (ObjectNode) value;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw validation(label + "不是合法 JSON");
        }
    }

    private Map<String, Object> parseMap(String raw) {
        try { return mapper.readValue(raw, new TypeReference<>() {}); }
        catch (Exception ex) { throw validation("版本快照不是合法 JSON"); }
    }

    private Map<String, Object> flatten(String prefix, Object value) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                String key = prefix.isEmpty() ? String.valueOf(entry.getKey()) : prefix + "." + entry.getKey();
                out.putAll(flatten(key, entry.getValue()));
            }
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                out.putAll(flatten(prefix + "[" + i + "]", list.get(i)));
            }
        } else {
            out.put(prefix.isEmpty() ? "$" : prefix, value);
        }
        return out;
    }

    private List<UUID> parseUuidArray(String raw, String label) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            return mapper.readValue(raw, new TypeReference<>() {});
        } catch (Exception ex) {
            throw validation(label + "必须是 UUID 数组");
        }
    }

    private static UUID parseUuid(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value);
        if (text.isBlank() || "null".equalsIgnoreCase(text)) return null;
        try { return UUID.fromString(text); }
        catch (IllegalArgumentException ex) { return null; }
    }

    private static String text(JsonNode node, String key, String fallback) {
        JsonNode value = node.get(key);
        return value != null && value.isTextual() ? value.asText() : fallback;
    }

    private static boolean bool(JsonNode node, String key, boolean fallback) {
        JsonNode value = node.get(key);
        return value != null && value.isBoolean() ? value.asBoolean() : fallback;
    }

    private static int integer(JsonNode node, String key, int fallback) {
        JsonNode value = node.get(key);
        return value != null && value.canConvertToInt() ? value.asInt() : fallback;
    }

    private String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception ex) { throw validation("布局数据无法序列化"); }
    }

    private List<ZoneInfo> zones(UUID templateId) {
        return zoneRepo.findByTemplateIdOrderBySortOrderAsc(templateId).stream().map(ZoneInfo::from).toList();
    }

    private BusinessException missing(String message) {
        return new BusinessException(ErrorCodes.NOT_FOUND, message);
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCodes.VALIDATION_FAILED, message);
    }
}
