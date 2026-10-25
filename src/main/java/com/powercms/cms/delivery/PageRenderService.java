package com.powercms.cms.delivery;
import com.powercms.cms.sitepackage.VisualLayoutHtmlRenderer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.content.repo.ContentVersionRepository;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.cms.sitepackage.PageCompositionService;
import com.powercms.cms.sitepackage.TemplateService;
import com.powercms.cms.sitepackage.ThemePackageService;
import com.powercms.cms.sitepackage.entity.SiteTemplateEntity;
import com.powercms.cms.sitepackage.repo.SiteTemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.powercms.cms.sitepackage.entity.SiteContainerEntity;
import com.powercms.cms.sitepackage.entity.SiteDropZoneEntity;
import com.powercms.cms.sitepackage.repo.SiteContainerRepository;
import com.powercms.cms.sitepackage.repo.SiteDropZoneRepository;
import com.powercms.cms.sitepackage.repo.PageVisualCompositionRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class PageRenderService {
    private static final Logger log = LoggerFactory.getLogger(PageRenderService.class);

    /**
     * 缺陷 ②：渲染契约违例码——**套站点外壳却输出完整文档**（{@code <!DOCTYPE>}/{@code <html>} 被塞进
     * 外壳的正文容器，产出嵌套文档与第二个 {@code <title>}）。平台约定：套外壳的正文必须是片段。
     */
    public static final String VIOLATION_SHELL_WRAPS_DOCUMENT = "SHELL_WRAPS_FULL_DOCUMENT";

    /**
     * 缺陷 ②：渲染契约违例码——**不套外壳却输出片段**（丢掉 {@code <head>}/{@code <title>}/站点页眉页脚
     * 与 {@code site.css}，页面以裸片段交付）。
     */
    public static final String VIOLATION_FRAGMENT_WITHOUT_SHELL = "FRAGMENT_WITHOUT_SHELL";

    /** 完整文档识别：{@code <!DOCTYPE html>} 或 {@code <html …>}（忽略大小写、跨行，先剥离 FTL 注释）。 */
    private static final java.util.regex.Pattern FULL_DOCUMENT_PATTERN =
        java.util.regex.Pattern.compile("(?is)<!doctype\\s+html|<html[\\s>]");
    private static final java.util.regex.Pattern FTL_COMMENT_PATTERN =
        java.util.regex.Pattern.compile("(?s)<#--.*?-->");

    /**
     * 容器结构渲染源的「模板文件引用」形态：{@code [ / ]dir/file.ftl}。
     * 字符集刻意不含 {@code <}、{@code $}、空白与换行，故内联 FTL 源码永不落入此形态。
     */
    private static final java.util.regex.Pattern TEMPLATE_REFERENCE_PATTERN =
        java.util.regex.Pattern.compile("^/?[A-Za-z0-9_][A-Za-z0-9_./-]*\\.ftl$");

    private final ContentItemRepository itemRepo;
    private final ContentVersionRepository versionRepo;
    private final ContentTypeRepository typeRepo;
    private final TemplateService templateService;
    private final ObjectMapper mapper;
    private final PageCompositionService compositionService;
    private final FreeMarkerSandbox freeMarkerSandbox;
    private final PageVisualCompositionRepository compositionRepo;
    private final SiteDropZoneRepository zoneRepo;
    private final SiteContainerRepository containerRepo;
    private final com.powercms.cms.sitepackage.repo.SiteContainerStructureRepository containerStructureRepo;
    private final RenderDatasetCache datasetCache;
    private final SiteTemplateRepository visualTemplateRepo;
    private final ThemePackageService themePackageService;
    private final ComponentRenderService componentRenderer;
    private final RenderContextFactory contextFactory;
    private final ContainerTemplateModelFactory containerModelFactory;
    private final AnalyticsInjector analyticsInjector;
    private final com.powercms.cms.identity.permission.ObjectPermissionService objectPermissions;
    private final com.powercms.cms.identity.permission.ViewerAccessService viewerAccess;
    private final String publicBaseUrl;

    /**
     * 页面管理列表预览图解析（缩略图/列表视图）。单测直构 {@code new PageRenderService(...)} 时为 null，
     * 此时列表不下发预览图，界面回退默认图片。
     */
    private com.powercms.cms.content.ContentPreviewImageService previewImages;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPreviewImages(com.powercms.cms.content.ContentPreviewImageService previewImages) {
        this.previewImages = previewImages;
    }

    PageRenderService(ContentItemRepository itemRepo, ContentVersionRepository versionRepo,
                      ContentTypeRepository typeRepo, TemplateService templateService,
                      ObjectMapper mapper, PageCompositionService compositionService,
                      FreeMarkerSandbox freeMarkerSandbox,
                      PageVisualCompositionRepository compositionRepo,
                      SiteDropZoneRepository zoneRepo,
                      SiteContainerRepository containerRepo,
                      com.powercms.cms.sitepackage.repo.SiteContainerStructureRepository containerStructureRepo,
                      RenderDatasetCache datasetCache,
                      SiteTemplateRepository visualTemplateRepo,
                      ThemePackageService themePackageService, ComponentRenderService componentRenderer,
                      RenderContextFactory contextFactory,
                      ContainerTemplateModelFactory containerModelFactory,
                      AnalyticsInjector analyticsInjector,
                      com.powercms.cms.identity.permission.ObjectPermissionService objectPermissions,
                      com.powercms.cms.identity.permission.ViewerAccessService viewerAccess,
                      @org.springframework.beans.factory.annotation.Value("${powercms.public.base-url:https://example.com}") String publicBaseUrl) {
        this.itemRepo = itemRepo;
        this.versionRepo = versionRepo;
        this.typeRepo = typeRepo;
        this.templateService = templateService;
        this.mapper = mapper;
        this.compositionService = compositionService;
        this.freeMarkerSandbox = freeMarkerSandbox;
        this.compositionRepo = compositionRepo;
        this.zoneRepo = zoneRepo;
        this.containerRepo = containerRepo;
        this.containerStructureRepo = containerStructureRepo;
        this.datasetCache = datasetCache;
        this.visualTemplateRepo = visualTemplateRepo;
        this.themePackageService = themePackageService;
        this.componentRenderer = componentRenderer;
        this.contextFactory = contextFactory;
        this.containerModelFactory = containerModelFactory;
        this.analyticsInjector = analyticsInjector;
        this.objectPermissions = objectPermissions;
        this.viewerAccess = viewerAccess;
        this.publicBaseUrl = publicBaseUrl;
    }

    public record RenderedPage(UUID id, String routePath, String title, String templatePath,
                               String html, String status, int version) {}

    public java.util.List<PageInfo> listPages(UUID siteId) {
        var types = typeRepo.findBySiteIdOrderByCodeAsc(siteId).stream()
            .collect(java.util.stream.Collectors.toMap(t -> t.getId(), t -> t));
        var items = itemRepo.findBySiteId(siteId).stream()
            .filter(item -> {
                var type = types.get(item.getContentTypeId());
                return type != null && "PAGE".equalsIgnoreCase(type.getObjectType());
            })
            .sorted(java.util.Comparator.comparing(ContentItemEntity::getRoutePath,
                java.util.Comparator.nullsLast(String::compareToIgnoreCase)))
            .toList();
        // 页面列表缩略图/列表视图：一次批量解析预览图（内容模型图片字段，空值前端用默认图）
        Map<UUID, String> previews = previewImages == null ? Map.of() : previewImages.resolve(items);
        return items.stream()
            .map(item -> new PageInfo(item.getId(), item.getTitle(), item.getRoutePath(),
                item.getTemplatePath(), item.getStatus(), item.getContentTypeId(), item.getFolderId(),
                item.getCurrentVersion(), item.getUpdatedAt(), previews.get(item.getId())))
            .toList();
    }

    public record PageInfo(UUID id, String title, String routePath, String templatePath,
                           String status, UUID contentTypeId, UUID folderId, int currentVersion,
                           java.time.Instant updatedAt, String previewImage) {}

    public RenderedPage renderPublished(UUID siteId, String routePath) {
        return renderPublished(siteId, routePath, "GET", routePath, Map.of(), Locale.ROOT, null, null);
    }

    public RenderedPage renderPublishedForRelease(UUID siteId, String routePath,
                                                      com.powercms.cms.publish.ReleaseThemeContext themeContext) {
        return render(siteId, normalizeRoute(routePath), ContentItemEntity.STATUS_PUBLISHED, false, false,
            "GET", routePath, Map.of(), Locale.ROOT, null, null, themeContext, false, true, null);
    }

    /**
     * Release 冻结快照渲染（页面无主题上下文时）。
     * 页面级拦截由 {@code ReleaseService.freeze} 决定是否写入产物；组件仍按匿名公开档剔除，
     * 避免受限字段被烤进公开页 HTML。
     */
    public RenderedPage renderForRelease(UUID siteId, String routePath) {
        return render(siteId, normalizeRoute(routePath), ContentItemEntity.STATUS_PUBLISHED, false, false,
            "GET", routePath, Map.of(), Locale.ROOT, null, null, null, false, true, null);
    }

    public RenderedPage renderPublished(UUID siteId, String routePath, String method, String requestPath, Map<String, ?> query, Locale locale, String username, java.time.Instant renderInstant) {
        return render(siteId, normalizeRoute(routePath), ContentItemEntity.STATUS_PUBLISHED, false, false, method, requestPath, query, locale, username, renderInstant, null, true, true, viewerFromContext());
    }

    public RenderedPage renderPreview(UUID siteId, String routePath) {
        return renderPreview(siteId, routePath, false);
    }

    /** editMode 在预览渲染基础上给投放区/组件输出 data-pcms-* 编辑标注，供可视化编辑器桥接。 */
    public RenderedPage renderPreview(UUID siteId, String routePath, boolean editMode) {
        return render(siteId, normalizeRoute(routePath), null, true, editMode, "GET", routePath, Map.of(), Locale.ROOT, null, null, null, false, false, null);
    }

    private static UUID viewerFromContext() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof com.powercms.cms.identity.AuthenticatedUserPrincipal principal) {
            return principal.getUserId();
        }
        return null;
    }

    private RenderedPage render(UUID siteId, String routePath, String requiredStatus, boolean preview, boolean editMode, String method, String requestPath, Map<String, ?> query, Locale locale, String username, java.time.Instant renderInstant,
                                com.powercms.cms.publish.ReleaseThemeContext themeContext, boolean enforceAnonymousView,
                                boolean filterAnonymousComponents, UUID viewerUserId) {
        boolean useCache = !preview;
        // 页面级守卫只在在线 SSR（enforceAnonymousView）生效。冻结是否产出该页由 ReleaseService 决定，
        // 这里抛错会让单页拒绝阻断整站发布。
        // 组件过滤在 SSR 与冻结渲染都生效：公开页内嵌的受限内容不得进入 HTML。预览不过滤。
        boolean anonymousDelivery = filterAnonymousComponents && !preview && requiredStatus != null;
        ContentItemEntity item = requiredStatus == null
            ? itemRepo.findBySiteId(siteId).stream()
                .filter(candidate -> routePath.equals(candidate.getRoutePath()))
                .findFirst().orElseThrow(() -> missing("页面不存在: " + routePath))
            : itemRepo.findBySiteIdAndRoutePathAndStatus(siteId, routePath, requiredStatus)
                .orElseThrow(() -> missing("已发布页面不存在: " + routePath));
        if (enforceAnonymousView && anonymousDelivery) {
            if (!viewerAccess.visible(
                    viewerUserId,
                    com.powercms.cms.identity.permission.ObjectType.CONTENT,
                    item.getId())) {
                throw new BusinessException(ErrorCodes.PERMISSION_DENIED, "页面不存在");
            }
        }
        var type = typeRepo.findById(item.getContentTypeId())
            .orElseThrow(() -> missing("页面模型不存在"));
        if (!"PAGE".equalsIgnoreCase(type.getObjectType()))
            throw bad("只有 PAGE 类型可以作为页面渲染");
        int version = requiredStatus == null ? item.getCurrentVersion() : item.getPublishedVersion();
        if (version <= 0) throw missing("页面没有可渲染版本");
        String fieldValues = datasetCache.getOrLoad(
            RenderDatasetCache.Region.CONTENT,
            item.getId() + ":" + version,
            useCache,
            () -> versionRepo.findByContentItemIdAndVersion(item.getId(), version)
                .orElseThrow(() -> missing("页面版本不存在")).getFieldValues());
        String templatePath = item.getTemplatePath() == null || item.getTemplatePath().isBlank()
            ? type.getDisplayTemplate() : item.getTemplatePath();
        String normalizedTemplate = templatePath == null || templatePath.isBlank()
            ? null : (templatePath.startsWith("/") ? templatePath.substring(1) : templatePath);
        // 计划 A（渲染源自足）：.ftl 不再是硬前置。先按既有口径解析代码模板工程中的视图模板；
        // 未命中时回退到页面绑定的可视化模板（site_template），由其布局或 CODE 代码提供渲染源。
        String source = tryResolveTemplateSource(siteId, normalizedTemplate, useCache);
        String templateRef = normalizedTemplate;
        if (source == null) {
            SiteTemplateEntity visualFallback = visualTemplateFallback(item.getId());
            if (visualFallback == null) {
                if (normalizedTemplate == null) throw bad("页面模板未配置");
                TemplateService.requireFtlViewPath(normalizedTemplate);
                throw missing("页面模板不存在: " + normalizedTemplate);
            }
            // 正文由 wrapWithTheme 中的可视化模板（布局 / CODE）生成，此处不再要求 .ftl 源。
            templateRef = "visual:" + visualFallback.getId();
        }
        Map<String, Object> content = parseObject(fieldValues);
        content.putIfAbsent("title", item.getTitle());
        content.put("id", item.getId().toString());
        content.put("routePath", item.getRoutePath());
        Map<String, List<com.powercms.cms.sitepackage.ComponentInstance>> zoneComponents = compositionService.zones(item.getId());
        Map<String, Object> renderedZones = renderZones(item.getId(), zoneComponents, preview, editMode, anonymousDelivery, viewerUserId);
        Map<String, Object> model = new LinkedHashMap<>(contextFactory.create(new RenderContextFactory.Input(
            siteId, item, content, preview, method, requestPath, query, locale, username, renderInstant, Map.of())));
        model.put("zones", zoneComponents);
        model.put("renderedZones", renderedZones);
        model.put("renderComponent", (freemarker.template.TemplateMethodModelEx) arguments -> {
            if (arguments == null || arguments.isEmpty()) throw new freemarker.template.TemplateModelException("renderComponent 需要 path 或 reference 参数");
            String selector = String.valueOf(freemarker.template.utility.DeepUnwrap.unwrap((freemarker.template.TemplateModel) arguments.get(0)));
            UUID parent = null; Map<String,Object> additional = Map.of();
            if (arguments.size() > 1) { Object raw = freemarker.template.utility.DeepUnwrap.unwrap((freemarker.template.TemplateModel) arguments.get(1)); if (raw != null && !String.valueOf(raw).isBlank()) try { parent=UUID.fromString(String.valueOf(raw)); } catch (IllegalArgumentException ex) { throw new freemarker.template.TemplateModelException("parent 必须是 UUID"); } }
            if (arguments.size() > 2) { Object raw=freemarker.template.utility.DeepUnwrap.unwrap((freemarker.template.TemplateModel) arguments.get(2)); if (raw instanceof Map<?,?> map) { var copy=new LinkedHashMap<String,Object>(); map.forEach((k,v)->copy.put(String.valueOf(k),v)); additional=copy; } else throw new freemarker.template.TemplateModelException("additionalModel 必须是 JSON 对象"); }
            try { return selector.startsWith("/site/components/") ? componentRenderer.renderPath(siteId,selector,additional,preview) : componentRenderer.renderReference(siteId,item.getId(),selector,parent,additional,preview,anonymousDelivery, viewerUserId); }
            catch (BusinessException ex) { throw new freemarker.template.TemplateModelException(ex.getMessage(),ex); }
        });
        try {
            // 计划 A：source 为 null 时表示回退到可视化模板，正文（布局 / CODE）由 wrapWithTheme 生成。
            String body = source == null ? "" : freeMarkerSandbox.render(normalizedTemplate, source, model, preview);
            String html = wrapWithTheme(siteId, item.getId(), body, model, preview, themeContext, item);
            if (!preview && !editMode) {
                // 统计采集组件（FR-STAT-001 注入开关）：交付页在 </body> 前注入一次；
                // 预览/编辑态不注入，避免编辑预览流量污染统计。
                final String[] holder = {html};
                analyticsInjector.findTracker(siteId, zoneComponents).ifPresent(config ->
                    holder[0] = AnalyticsInjector.injectBeforeBodyEnd(holder[0],
                        analyticsInjector.renderTag(siteId, config)));
                html = holder[0];
            }
            return new RenderedPage(item.getId(), item.getRoutePath(), item.getTitle(), templateRef,
                html, item.getStatus(), version);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCodes.TEMPLATE_RENDER_ERROR, ex.getMessage());
        }
    }

    private String wrapWithTheme(UUID siteId, UUID pageId, String body,
                                 Map<String, Object> baseModel, boolean preview,
                                 com.powercms.cms.publish.ReleaseThemeContext themeContext,
                                 ContentItemEntity seoItem) {
        var composition = compositionRepo.findByPageId(pageId).orElse(null);
        if (composition == null || composition.getTemplateId() == null) return body;
        SiteTemplateEntity visual = visualTemplateRepo.findById(composition.getTemplateId()).orElse(null);
        if (visual == null) return body;
        UUID themeId = visual.getThemeId();
        Map<String, Object> layout = parseObject(visual.getLayoutJson());
        String mode = String.valueOf(layout.getOrDefault("mode", "VISUAL")).toUpperCase(Locale.ROOT);
        Map<String, Object> model = new LinkedHashMap<>(baseModel);
        model.put("pageBody", body == null ? "" : body);
        // 计划 A（渲染源自足）：可视化模板是页面正文的渲染源（布局 / CODE 代码），主题只是可选外壳。
        // 主题缺失或不可渲染时，直接返回布局/代码产物——不再回退为「未套布局的原始正文」，避免空白页。
        boolean themeRenderable = themeId != null
            && (themeContext != null || themePackageService.isRenderableAvailable(siteId, themeId));
        if (!themeRenderable) {
            return renderVisualBody(visual, layout, mode, body, model, preview,
                "visual-template-" + visual.getId() + "-body.ftl");
        }
        boolean useCache = !preview;
        com.powercms.cms.publish.ReleaseThemeContext.Revision pinned = themeContext == null ? null : themeContext.require(themeId);
        if (pinned != null) themePackageService.validateReleaseRevision(themeId, pinned.revisionId(), themeContext);
        // 主题外壳（template.ftl）先读：既用于末尾包裹。
        String wrapper = pinned == null
            ? themePackageService.readAvailableText(siteId, themeId, "template.ftl", useCache)
            : themePackageService.readRevisionText(themeId, pinned.revisionId(), "template.ftl", themeContext, useCache);
        if (wrapper == null) throw bad("主题缺少必需文件 template.ftl");
        @SuppressWarnings("unchecked")
        Map<String, Object> overrides = layout.get("fragmentOverrides") instanceof Map<?, ?> values ? (Map<String, Object>) values : Map.of();
        boolean headerEnabled = booleanValue(layout.get("header"), true);
        boolean footerEnabled = booleanValue(layout.get("footer"), true);
        // 头部片段渲染
        String headTemplate = renderFragment(siteId, themeId, "htmlHead", "html_head.ftl", overrides, baseModel, preview, useCache, themeContext, pinned);
        String htmlHead = headTemplate
            + renderThemeAssets(siteId, themeId, "css", "style", useCache, themeContext, pinned);
        String header = headerEnabled
            ? renderFragment(siteId, themeId, "header", "header.ftl", overrides, baseModel, preview, useCache, themeContext, pinned)
            : "";
        String footer = (footerEnabled
            ? renderFragment(siteId, themeId, "footer", "footer.ftl", overrides, baseModel, preview, useCache, themeContext, pinned)
            : "")
            + renderThemeAssets(siteId, themeId, "js", "script", useCache, themeContext, pinned);

        String themedBody = renderVisualBody(visual, layout, mode, body, model, preview,
            "theme-" + themeId + "-layout.ftl");
        model.put("body", themedBody);
        model.put("htmlHead", htmlHead);
        model.put("header", header);
        model.put("footer", footer);
        return freeMarkerSandbox.render("theme-" + themeId + "-template.ftl", wrapper, model, preview);
    }

    /**
     * 计划 A：可视化模板的渲染源展开——CODE 以 {@code layout_json.code} 为正文（FreeMarker 沙箱渲染），
     * VISUAL 以 {@code layout_json.rows} 包裹正文并按 box 展开投放区（{@code VisualLayoutHtmlRenderer}）。
     * {@code pageBody} 为代码模板工程命中的 .ftl 正文；回退场景下为空串。
     */
    private String renderVisualBody(SiteTemplateEntity visual, Map<String, Object> layout, String mode,
                                    String pageBody, Map<String, Object> model, boolean preview,
                                    String codeTemplateName) {
        if ("CODE".equals(mode)) {
            String code = String.valueOf(layout.getOrDefault("code", ""));
            if (code.isBlank()) return pageBody == null ? "" : pageBody;
            return freeMarkerSandbox.render(codeTemplateName, code, model, preview);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> renderedZones = model.get("renderedZones") instanceof Map<?, ?> values
            ? (Map<String, Object>) values : Map.of();
        return VisualLayoutHtmlRenderer.render(visual.getLayoutJson(), renderedZones, pageBody == null ? "" : pageBody);
    }

    /**
     * 计划 A：.ftl 未命中时，页面绑定的可视化模板是否可自足提供渲染源。
     * CODE 模式要求 {@code code} 非空；VISUAL 模式要求 {@code rows} 非空（否则会渲染成空白页）。
     */
    private SiteTemplateEntity visualTemplateFallback(UUID pageId) {
        var composition = compositionRepo.findByPageId(pageId).orElse(null);
        if (composition == null || composition.getTemplateId() == null) return null;
        SiteTemplateEntity visual = visualTemplateRepo.findById(composition.getTemplateId()).orElse(null);
        if (visual == null) return null;
        try {
            JsonNode root = mapper.readTree(visual.getLayoutJson());
            String mode = root.path("mode").asText("VISUAL").toUpperCase(Locale.ROOT);
            if ("CODE".equals(mode)) {
                return root.path("code").asText("").isBlank() ? null : visual;
            }
            JsonNode rows = root.path("rows");
            return rows.isArray() && rows.size() > 0 ? visual : null;
        } catch (Exception ex) {
            log.warn("可视化模板布局 JSON 解析失败，回退不可用: template={} err={}",
                visual.getId(), ex.getMessage());
            return null;
        }
    }

    private String renderFragment(UUID siteId, UUID themeId, String key, String path, Map<String, Object> overrides,
                                  Map<String, Object> model, boolean preview, boolean useCache,
                                  com.powercms.cms.publish.ReleaseThemeContext themeContext,
                                  com.powercms.cms.publish.ReleaseThemeContext.Revision pinned) {
        Object override = overrides.get(key);
        String source = override instanceof String text && !text.isBlank() ? text
            : pinned == null ? themePackageService.readAvailableText(siteId, themeId, path, useCache)
            : themePackageService.readRevisionText(themeId, pinned.revisionId(), path, themeContext, useCache);
        if (source == null) { log.warn("主题 {} 缺少可选片段 {}，使用空内容", themeId, path); return ""; }
        return freeMarkerSandbox.render("theme-" + themeId + "-" + (override instanceof String text && !text.isBlank() ? "override-" + key : path), source, model, preview);
    }

    /**
     * Inline theme {@code css/} and {@code js/} text assets so Site Browser files apply without a separate static host.
     */
    private String renderThemeAssets(UUID siteId, UUID themeId, String directory, String tag,
                                     boolean useCache,
                                     com.powercms.cms.publish.ReleaseThemeContext themeContext,
                                     com.powercms.cms.publish.ReleaseThemeContext.Revision pinned) {
        List<String> assets = pinned == null
            ? themePackageService.listRelativeTextAssets(siteId, themeId, directory)
            : themePackageService.listRevisionRelativeTextAssets(themeId, pinned.revisionId(), directory);
        if (assets.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (String relative : assets) {
            String source = pinned == null
                ? themePackageService.readAvailableText(siteId, themeId, relative, useCache)
                : themePackageService.readRevisionText(themeId, pinned.revisionId(), relative, themeContext, useCache);
            if (source == null || source.isBlank()) continue;
            out.append('\n').append('<').append(tag)
                .append(" data-theme-asset=\"").append(escapeAttr(relative)).append("\">\n")
                .append(source)
                .append("\n</").append(tag).append('>');
        }
        return out.toString();
    }

    private static String escapeAttr(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }

    /**
     * 计划 A：解析 .ftl 视图模板源，**不再抛错**——命中任一 FTL 工程中的文件即返回内容，
     * 未命中（无 FTL 工程 / 无该路径）返回 {@code null}，由调用方决定是否回退到可视化模板。
     */
    private String tryResolveTemplateSource(UUID siteId, String normalizedTemplate, boolean useCache) {
        if (normalizedTemplate == null) return null;
        var projects = templateService.listProjects(siteId).stream()
            .filter(p -> "FTL".equalsIgnoreCase(p.projectType())).toList();
        for (var project : projects) {
            String cacheKey = project.id() + ":" + normalizedTemplate;
            try {
                return datasetCache.getOrLoad(RenderDatasetCache.Region.TEMPLATE, cacheKey, useCache,
                    () -> templateService.readFileContent(project.id(), normalizedTemplate));
            } catch (BusinessException ignored) {
                datasetCache.invalidate(RenderDatasetCache.Region.TEMPLATE, cacheKey);
            }
        }
        return null;
    }

    /**
     * 发布门禁（回归缺陷 ①）：页面是否具备**可渲染源**，判定与 {@link #render} 同源——
     * 命中代码模板工程中的 .ftl，或页面绑定的可视化模板可自足（CODE 非空 / VISUAL rows 非空）。
     * 两者皆无时交付必然 404「页面模板不存在」，发布不应给出「成功」的假象。
     *
     * @param templatePath 页面 templatePath（空则由调用方回退 displayTemplate 后传入）
     */
    public boolean hasRenderableSource(UUID siteId, UUID pageId, String templatePath) {
        String normalizedTemplate = templatePath == null || templatePath.isBlank()
            ? null : (templatePath.startsWith("/") ? templatePath.substring(1) : templatePath);
        if (normalizedTemplate != null
            && tryResolveTemplateSource(siteId, normalizedTemplate, true) != null) {
            return true;
        }
        return visualTemplateFallback(pageId) != null;
    }

    /**
     * 缺陷 ②（渲染契约预检）：判定「页面正文形态（完整文档 / 片段）」与「是否套站点外壳」是否自洽。
     *
     * <p>判定与实际渲染**同源**：同一 {@code templatePath}→{@code displayTemplate} 解析、同一
     * {@link #tryResolveTemplateSource} 查找、同一 {@code isRenderableAvailable} 主题可渲染判定，
     * 因此可在**改绑之前**回答「这次绑定会让页面变成什么形态」。{@code violation} 为空表示自洽。
     */
    public record RenderContract(UUID pageId, String ftlPath, boolean ftlResolved, boolean ftlFullDocument,
                                 UUID templateId, UUID themeId, boolean shellRenderable, String violation) {}

    /**
     * 预检给定目标可视化模板下页面的渲染契约。{@code targetTemplateId} 为 {@code null} 表示「无绑定」
     * （解绑后或从未绑定），此时必然不套外壳。
     */
    public RenderContract inspectRenderContract(UUID siteId, UUID pageId, UUID targetTemplateId) {
        ContentItemEntity item = itemRepo.findById(pageId)
            .filter(x -> siteId.equals(x.getSiteId()))
            .orElseThrow(() -> missing("页面不存在"));
        var type = typeRepo.findById(item.getContentTypeId()).orElseThrow(() -> missing("页面模型不存在"));
        String templatePath = item.getTemplatePath() == null || item.getTemplatePath().isBlank()
            ? type.getDisplayTemplate() : item.getTemplatePath();
        String normalizedTemplate = templatePath == null || templatePath.isBlank()
            ? null : (templatePath.startsWith("/") ? templatePath.substring(1) : templatePath);
        String source = tryResolveTemplateSource(siteId, normalizedTemplate, false);
        boolean fullDocument = looksLikeFullDocument(source);
        UUID themeId = targetTemplateId == null ? null
            : visualTemplateRepo.findById(targetTemplateId).map(SiteTemplateEntity::getThemeId).orElse(null);
        boolean shellRenderable = themeId != null && themePackageService.isRenderableAvailable(siteId, themeId);
        String violation = "";
        if (source != null) {
            if (shellRenderable && fullDocument) {
                violation = VIOLATION_SHELL_WRAPS_DOCUMENT;
            } else if (!shellRenderable && !fullDocument) {
                violation = VIOLATION_FRAGMENT_WITHOUT_SHELL;
            }
        }
        return new RenderContract(pageId, normalizedTemplate, source != null, fullDocument,
            targetTemplateId, themeId, shellRenderable, violation);
    }

    /** 判定 .ftl 正文是否为完整 HTML 文档（先剥离 FTL 注释，避免注释中的示例标签造成误判）。 */
    public static boolean looksLikeFullDocument(String source) {
        if (source == null || source.isBlank()) return false;
        return FULL_DOCUMENT_PATTERN.matcher(FTL_COMMENT_PATTERN.matcher(source).replaceAll("")).find();
    }

    private Map<String, Object> renderZones(UUID pageId,
                                            Map<String, List<com.powercms.cms.sitepackage.ComponentInstance>> zones,
                                            boolean preview, boolean editMode, boolean anonymousDelivery, UUID viewerUserId) {
        Map<String, Object> rendered = new LinkedHashMap<>();
        var composition = compositionRepo.findByPageId(pageId).orElse(null);
        if (composition == null || composition.getTemplateId() == null) {
            zones.forEach((key, components) -> rendered.put(key, Map.of(
                "html", "",
                "components", components == null ? List.of() : components
            )));
            return rendered;
        }
        Map<String, SiteDropZoneEntity> byKey = new LinkedHashMap<>();
        for (SiteDropZoneEntity zone : zoneRepo.findByTemplateIdOrderBySortOrderAsc(composition.getTemplateId())) {
            byKey.put(zone.getZoneKey(), zone);
        }
        for (var entry : zones.entrySet()) {
            String zoneKey = entry.getKey();
            List<com.powercms.cms.sitepackage.ComponentInstance> components =
                entry.getValue() == null ? List.of() : entry.getValue();
            SiteDropZoneEntity zone = byKey.get(zoneKey);
            String html = "";
            if (zone != null && zone.getContainerId() != null) {
                html = renderContainer(pageId, zoneKey, zone.getContainerId(), components, preview, editMode, anonymousDelivery, viewerUserId);
            }
            rendered.put(zoneKey, Map.of("html", wrapZoneForEdit(zoneKey, html, editMode), "components", components));
        }
        for (String key : byKey.keySet()) {
            rendered.putIfAbsent(key, Map.of("html", wrapZoneForEdit(key, "", editMode), "components", List.of()));
        }
        return rendered;
    }

    /** editMode 下为投放区包裹标注节点；空投放区输出占位节点，便于编辑器命中。 */
    private static String wrapZoneForEdit(String zoneKey, String html, boolean editMode) {
        if (!editMode) return html;
        String inner = html == null || html.isBlank()
            ? "<div data-pcms-zone-empty=\"" + escape(zoneKey) + "\">" + escape(zoneKey) + "</div>"
            : html;
        return "<div data-pcms-zone-root=\"" + escape(zoneKey) + "\">" + inner + "</div>";
    }

    private String renderContainer(UUID pageId, String zoneKey, UUID containerId,
                                   List<com.powercms.cms.sitepackage.ComponentInstance> components,
                                   boolean preview, boolean editMode, boolean anonymousDelivery, UUID viewerUserId) {
        boolean useCache = !preview;
        SiteContainerEntity container = datasetCache.getOrLoad(
            RenderDatasetCache.Region.CONTAINER,
            containerId.toString(),
            useCache,
            () -> containerRepo.findById(containerId).orElseThrow(() -> missing("容器不存在")));
        Map<String, Object> config = parseObject(container.getConfig());
        String type = String.valueOf(config.getOrDefault("type", "CONTENT")).toUpperCase(Locale.ROOT);
        if ("ZERO_CONTENT".equals(type)) {
            String zero = String.valueOf(config.getOrDefault("zeroContentFtl", ""));
            if (zero.isBlank()) return "";
            Map<String, Object> model =
                containerModelFactory.create(container.getSiteId(), pageId, container, config, preview);
            return freeMarkerSandbox.render("container-zero-" + containerId + ".ftl", zero, model, preview);
        }
        String pre = String.valueOf(config.getOrDefault("preLoopFtl", ""));
        String post = String.valueOf(config.getOrDefault("postLoopFtl", ""));
        Map<UUID, String> displayTemplates = containerTemplates(container, preview);
        Map<String, Object> shellModel =
            containerModelFactory.create(container.getSiteId(), pageId, container, config, preview);
        StringBuilder html = new StringBuilder();
        if (!pre.isBlank()) {
            html.append(freeMarkerSandbox.render("container-pre-" + containerId + ".ftl", pre, shellModel, preview));
        }
        int index = 0;
        for (com.powercms.cms.sitepackage.ComponentInstance component : components) {
            String piece = renderComponentPiece(pageId, container, displayTemplates, component, preview, editMode, anonymousDelivery, viewerUserId);
            html.append(editMode ? wrapComponentForEdit(zoneKey, component, index, piece) : piece);
            index++;
        }
        if (!post.isBlank()) {
            html.append(freeMarkerSandbox.render("container-post-" + containerId + ".ftl", post, shellModel, preview));
        }
        return html.toString();
    }

    private static String wrapComponentForEdit(String zoneKey, com.powercms.cms.sitepackage.ComponentInstance component,
                                               int index, String inner) {
        return "<div data-pcms-component=\"" + escape(component.id())
            + "\" data-pcms-zone=\"" + escape(zoneKey)
            + "\" data-pcms-index=\"" + index
            + "\" data-pcms-kind=\"" + component.kind() + "\">" + inner + "</div>";
    }

    /** 供可视化编辑器局部刷新：按投放区容器规则渲染单个组件（可携带未保存数据），返回带编辑标注的 HTML。 */
    public String renderComponentEditPreview(UUID siteId, UUID pageId, String zoneKey, int index,
                                             com.powercms.cms.sitepackage.ComponentInstance component) {
        ContentItemEntity item = itemRepo.findById(pageId)
            .filter(x -> siteId.equals(x.getSiteId()))
            .orElseThrow(() -> missing("页面不存在"));
        var composition = compositionRepo.findByPageId(item.getId())
            .orElseThrow(() -> missing("页面没有可视化布局"));
        if (composition.getTemplateId() == null) throw missing("页面未绑定可视化模板");
        SiteDropZoneEntity zone = zoneRepo.findByTemplateIdOrderBySortOrderAsc(composition.getTemplateId()).stream()
            .filter(z -> zoneKey.equals(z.getZoneKey())).findFirst()
            .orElseThrow(() -> missing("投放区不存在: " + zoneKey));
        if (zone.getContainerId() == null) throw missing("投放区未绑定容器");
        SiteContainerEntity container = containerRepo.findById(zone.getContainerId())
            .orElseThrow(() -> missing("容器不存在"));
        Map<String, Object> config = parseObject(container.getConfig());
        Map<UUID, String> displayTemplates = containerTemplates(container, true);
        String piece = renderComponentPiece(pageId, container, displayTemplates, component, true, true, false, null);
        return wrapComponentForEdit(zoneKey, component, index, piece);
    }


    /**
     * 容器结构渲染源：{@code site_container_structure.code} 支持**两种形态**——
     * ① **内联 FTL 源码**（Containers 编辑器默认值与 TravelLux 预置的既有契约，如
     * {@code <div class="content-item">${content.title!}</div>}）；
     * ② **{@code .ftl} 模板文件引用**（与 {@code ComponentInstance.templatePath} 同义，如
     * {@code templates/comp-hero.ftl}）。
     *
     * <p>形态 ② 若被当作 FTL 源码交给 {@link FreeMarkerSandbox} 渲染，会因不含任何 FTL 语法而**逐字输出
     * 路径串**（投放区里出现裸路径文本，如 {@code templates/comp-hero.ftl}）；故此处统一消歧：呈路径形态的
     * 先经 {@link #tryResolveTemplateSource} 取文件内容，其余一律按内联 FTL 源码原样保留。
     */
    private Map<UUID, String> containerTemplates(SiteContainerEntity container, boolean preview) {
        Map<UUID, String> raw = containerStructureRepo.findByContainerIdOrderBySortOrderAsc(container.getId()).stream()
            .collect(java.util.stream.Collectors.toMap(link -> link.getStructureId(), link -> link.getCode(), (left, right) -> left, LinkedHashMap::new));
        if (raw.isEmpty()) {
            // Read-only compatibility for containers saved before site_container_structure existed.
            Map<String, Object> config = parseObject(container.getConfig());
            if (config.get("displayTemplates") instanceof Map<?, ?> legacy) {
                for (var entry : legacy.entrySet()) {
                    try { raw.put(UUID.fromString(String.valueOf(entry.getKey())), String.valueOf(entry.getValue())); }
                    catch (IllegalArgumentException ignored) { }
                }
            }
        }
        Map<UUID, String> templates = new LinkedHashMap<>();
        for (var entry : raw.entrySet()) {
            templates.put(entry.getKey(), resolveStructureRendererSource(container, entry.getKey(), entry.getValue(), preview));
        }
        return templates;
    }

    /**
     * 消歧单个容器结构的渲染源：路径形态 → 解析为模板文件内容（**解析不到返回 {@code null}**，由渲染侧回退，
     * 绝不把路径串当正文输出）；其余（含空白）→ 原样按内联 FTL 源码返回。
     */
    private String resolveStructureRendererSource(SiteContainerEntity container, UUID structureId, String value, boolean preview) {
        if (value == null || value.isBlank()) return value;
        String reference = value.trim();
        if (!TEMPLATE_REFERENCE_PATTERN.matcher(reference).matches()) return value;
        String normalized = reference.startsWith("/") ? reference.substring(1) : reference;
        String source = tryResolveTemplateSource(container.getSiteId(), normalized, !preview);
        if (source == null) {
            log.warn("容器结构引用的模板未找到，该渲染源已跳过 container={} structureId={} template={}",
                container.getId(), structureId, reference);
        }
        return source;
    }

    private String renderComponentPiece(UUID pageId, SiteContainerEntity container,
                                        Map<UUID, String> displayTemplates,
                                        com.powercms.cms.sitepackage.ComponentInstance component, boolean preview, boolean editMode, boolean anonymousDelivery, UUID viewerUserId) {
        if (component.kind() == com.powercms.cms.sitepackage.ComponentInstance.Kind.SHARED || component.templatePath() != null) {
            return componentRenderer.renderInstance(container.getSiteId(), pageId, component,
                component.additionalModel(), preview, new java.util.LinkedHashSet<>(), 0, anonymousDelivery, viewerUserId);
        }
        Object templateObj = component.componentTypeId() == null ? null : displayTemplates.get(component.componentTypeId());
        String template = templateObj == null ? null : String.valueOf(templateObj);
        if (template == null || template.isBlank()) {
            return "<div class=\"component\">" + escape(component.title() == null ? "" : component.title()) + "</div>";
        }
        Map<String,Object> content = new LinkedHashMap<>();
        if (component.kind() == com.powercms.cms.sitepackage.ComponentInstance.Kind.CONTENT) {
            ContentItemEntity item = itemRepo.findById(component.contentItemId())
                .filter(x -> container.getSiteId().equals(x.getSiteId()) && !"ARCHIVED".equals(x.getStatus()))
                .orElseThrow(() -> missing("内容不存在"));
            // FR-DELIVERY-PERM-001 §3.5：匿名交付下，内容型组件引用的受限内容不得随公开页 HTML 输出
            if (anonymousDelivery && !viewerAccess.visible(viewerUserId,
                    com.powercms.cms.identity.permission.ObjectType.CONTENT,
                    item.getId())) {
                return "";
            }
            int version = component.contentVersion() != null ? component.contentVersion()
                : preview ? item.getCurrentVersion() : item.getPublishedVersion() == null ? 0 : item.getPublishedVersion();
            if (version <= 0) return "";
            String values = versionRepo.findByContentItemIdAndVersion(item.getId(), version)
                .orElseThrow(() -> missing("版本不存在")).getFieldValues();
            content.putAll(parseObject(values));
            content.put("id", item.getId().toString());
            content.put("identifier", item.getId().toString());
            content.put("title", item.getTitle());
            content.put("contentTypeId", item.getContentTypeId().toString());
            content.put("contentVersion", version);
        } else {
            if (component.data()!=null) content.putAll(component.data());
            content.putIfAbsent("title",component.title()); content.put("id",component.id());
        }
        Map<String,Object> model=new LinkedHashMap<>();model.put("content",content);model.put("contentModel",content);model.put("additionalModel",component.additionalModel()==null?Map.of():component.additionalModel());
        model.put("editableText", (freemarker.template.TemplateMethodModelEx) arguments -> editableText(arguments, component, content, editMode));
        return freeMarkerSandbox.render("container-item-"+container.getId()+"-"+component.id()+".ftl",template,model,preview);
    }

    /**
     * FreeMarker helper used by component templates:
     * ${editableText("headline_s", "minimal", "text")}.
     * In normal preview/delivery it returns the field value only; edit preview adds metadata that
     * the UVE bridge recognizes and upgrades to TinyMCE inline editing.
     */
    private Object editableText(java.util.List<?> arguments,
                                com.powercms.cms.sitepackage.ComponentInstance component,
                                Map<String, Object> content, boolean editMode)
        throws freemarker.template.TemplateModelException {
        if (arguments == null || arguments.isEmpty())
            throw new freemarker.template.TemplateModelException("editableText requires a field name");
        String field = unwrapTemplateArg(arguments.get(0));
        if (!field.matches("[A-Za-z][A-Za-z0-9_]*"))
            throw new freemarker.template.TemplateModelException("editableText field name is invalid");
        String mode = arguments.size() > 1 ? unwrapTemplateArg(arguments.get(1)).toLowerCase(Locale.ROOT) : "minimal";
        if (!Set.of("plain", "minimal", "full").contains(mode)) mode = "minimal";
        String format = arguments.size() > 2 ? unwrapTemplateArg(arguments.get(2)).toLowerCase(Locale.ROOT) : "text";
        if (!Set.of("text", "html").contains(format)) format = "text";
        String value = String.valueOf(content.getOrDefault(field, ""));
        if ("html".equals(format)) value = sanitizeInlineHtml(value, "full".equals(mode));
        if (!editMode) return "html".equals(format) ? value : escape(value);
        String target = component.kind() == com.powercms.cms.sitepackage.ComponentInstance.Kind.CONTENT
            ? " data-pcms-content-item=\"" + component.contentItemId() + "\" data-pcms-content-version=\""
                + String.valueOf(content.getOrDefault("contentVersion", component.contentVersion() == null ? 0 : component.contentVersion())) + "\""
            : "";
        return "<span data-pcms-editable-field=\"" + escape(field)
            + "\" data-pcms-editable-mode=\"" + mode
            + "\" data-pcms-editable-format=\"" + format
            + "\" data-pcms-editable-component=\"" + escape(component.id()) + "\"" + target + ">"
            + ("html".equals(format) ? value : escape(value)) + "</span>";
    }

    private static String sanitizeInlineHtml(String value, boolean full) {
        // full：富文本编辑器整段 HTML（含富媒体：图片/视频/表格/字体颜色），走富媒体白名单；
        // minimal：行内标题类字段，维持最小行内格式面。
        if (full) return RichTextSanitizer.sanitize(value);
        org.jsoup.safety.Safelist safelist = new org.jsoup.safety.Safelist().addTags("b", "strong", "i", "em", "u", "a", "br")
            .addAttributes("a", "href", "title", "target", "rel")
            .addProtocols("a", "href", "http", "https", "mailto");
        return org.jsoup.Jsoup.clean(value == null ? "" : value, "", safelist,
            new org.jsoup.nodes.Document.OutputSettings().prettyPrint(false));
    }

    private static String unwrapTemplateArg(Object argument) throws freemarker.template.TemplateModelException {
        if (argument instanceof freemarker.template.TemplateModel model) {
            Object value = freemarker.template.utility.DeepUnwrap.unwrap(model);
            return value == null ? "" : String.valueOf(value);
        }
        return argument == null ? "" : String.valueOf(argument);
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private Map<String, Object> parseObject(String raw) {
        if (raw == null || raw.isBlank()) return new LinkedHashMap<>();
        try { return mapper.readValue(raw, new TypeReference<>() {}); }
        catch (Exception ex) { throw bad("页面字段值不是合法 JSON"); }
    }

    private String normalizeRoute(String routePath) {
        if (routePath == null || routePath.isBlank()) throw bad("页面路径不能为空");
        String route = routePath.trim();
        if (!route.startsWith("/")) route = "/" + route;
        route = route.replaceAll("/{2,}", "/");
        if (route.length() > 1 && route.endsWith("/")) route = route.substring(0, route.length() - 1);
        return route;
    }

    private static boolean booleanValue(Object value, boolean defaultValue) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof String text) return Boolean.parseBoolean(text);
        return defaultValue;
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCodes.VALIDATION_FAILED, message);
    }

    /**
     * 资源缺失（页面 / 模型 / 版本 / 模板 / 容器 / 内容）：交付层必须映射为 404，不能落到 500。
     * 与 {@link #bad(String)}（请求形态非法 → 400/500）区分，是 FR-DELIVERY-PERM-001 的映射前提。
     */
    private static BusinessException missing(String message) {
        return new BusinessException(ErrorCodes.NOT_FOUND, message);
    }
}
