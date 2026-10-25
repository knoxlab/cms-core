package com.powercms.cms.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.content.ContentService;
import com.powercms.cms.model.dto.CreateContentTypeRequest;
import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.entity.FieldDefinitionEntity;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.cms.model.repo.FieldDefinitionRepository;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.sitepackage.ComponentInstance;
import com.powercms.cms.sitepackage.PageCompositionService;
import com.powercms.cms.sitepackage.TemplateService;
import com.powercms.cms.sitepackage.ThemePackageService;
import com.powercms.cms.sitepackage.VisualLayoutService;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Installs the editable TravelLux demonstration site into an existing site.
 *
 * <p>The preset is deliberately idempotent: existing models, content, layout, and page composition
 * are retained so running it again does not overwrite editor changes.
 */
@Service
@Transactional
public class TravelSitePresetService {
  private static final String ACTOR = "travel-lux-preset";
  private static final String THEME_NAME = "travel-lux";
  private static final String PROJECT_NAME = "TravelLux 模板";
  private static final String CONTAINER_NAME = "TravelLux 页面组件容器";
  private static final String VISUAL_TEMPLATE_NAME = "TravelLux 首页模板";
  private static final String HOME_ROUTE = "/";

  private final ContentTypeRepository typeRepo;
  private final FieldDefinitionRepository fieldRepo;
  private final ContentTypeService typeService;
  private final ContentService contentService;
  private final TemplateService templateService;
  private final ThemePackageService themePackageService;
  private final VisualLayoutService visualLayoutService;
  private final PageCompositionService compositionService;
  private final ContentItemRepository contentItemRepo;
  private final ObjectMapper mapper;

  TravelSitePresetService(
      ContentTypeRepository typeRepo,
      FieldDefinitionRepository fieldRepo,
      ContentTypeService typeService,
      ContentService contentService,
      TemplateService templateService,
      ThemePackageService themePackageService,
      VisualLayoutService visualLayoutService,
      PageCompositionService compositionService,
      ContentItemRepository contentItemRepo,
      ObjectMapper mapper) {
    this.typeRepo = typeRepo;
    this.fieldRepo = fieldRepo;
    this.typeService = typeService;
    this.contentService = contentService;
    this.templateService = templateService;
    this.themePackageService = themePackageService;
    this.visualLayoutService = visualLayoutService;
    this.compositionService = compositionService;
    this.contentItemRepo = contentItemRepo;
    this.mapper = mapper;
  }

  public record PresetResult(
      int createdModels,
      int existingModels,
      int createdContents,
      int existingContents,
      boolean createdTheme,
      boolean createdTemplateProject,
      boolean createdVisualTemplate,
      boolean createdPage,
      UUID themeId,
      UUID templateId,
      UUID pageId,
      String routePath) {}

  public PresetResult install(UUID siteId) {
    if (siteId == null) {
      throw new BusinessException(ErrorCodes.NOT_FOUND, "站点不存在");
    }

    ModelInstall models = installModels(siteId);
    ThemeInstall theme = installTheme(siteId);
    ProjectInstall project = installTemplates(siteId);
    ContentInstall contents = installContents(siteId, models.types());
    LayoutInstall layout = installVisualLayout(siteId, theme.themeId(), models.types());
    PageInstall page = installHomePage(siteId, models.types(), layout.templateId(), contents.seeds());

    return new PresetResult(
        models.created(),
        models.existing(),
        contents.created(),
        contents.existing(),
        theme.created(),
        project.created(),
        layout.created(),
        page.created(),
        theme.themeId(),
        layout.templateId(),
        page.pageId(),
        HOME_ROUTE);
  }

  private ModelInstall installModels(UUID siteId) {
    Map<String, ContentTypeEntity> types = new LinkedHashMap<>();
    int created = 0;
    int existing = 0;

    for (ModelSpec spec : modelSpecs()) {
      ContentTypeEntity type =
          typeRepo.findBySiteIdAndCodeOrderByVersionAsc(siteId, spec.code()).stream()
              .reduce((left, right) -> right)
              .orElse(null);
      if (type == null) {
        CreateContentTypeRequest request =
            new CreateContentTypeRequest(
                spec.code(),
                spec.name(),
                spec.description(),
                "COMPONENT".equals(spec.objectType()) ? "COMPONENT" : "PAGE",
                spec.template(),
                spec.icon(),
                null,
                null,
                spec.fields().stream().map(this::field).toList(),
                spec.objectType(),
                null,
                null,
                "NONE",
                null,
                false,
                null,
                null,
                null,
                true,
                null,
                null,
                null,
                null);
        var response = typeService.create(siteId, request);
        type = typeRepo.findById(response.id()).orElseThrow();
        created++;
      } else {
        syncModelLabels(type, spec);
        existing++;
      }

      if (!ContentTypeEntity.STATUS_PUBLISHED.equals(type.getStatus())) {
        typeService.publish(siteId, type.getId(), ACTOR, null);
        type = typeRepo.findById(type.getId()).orElseThrow();
      }
      configureContentMenu(type, spec);
      types.put(spec.code(), type);
    }
    return new ModelInstall(types, created, existing);
  }

  /**
   * Repair display labels when an earlier install corrupted UTF-8 Chinese into {@code ?}
   * placeholders. Structural fields are left untouched.
   */
  private void syncModelLabels(ContentTypeEntity type, ModelSpec spec) {
    boolean nameBroken = needsLabelRepair(type.getName(), spec.name());
    boolean descBroken = needsLabelRepair(type.getDescription(), spec.description());
    if (nameBroken) type.setName(spec.name());
    if (descBroken) type.setDescription(spec.description());
    if (nameBroken || descBroken) typeRepo.save(type);

    Map<String, FieldSpec> byCode = new LinkedHashMap<>();
    for (FieldSpec field : spec.fields()) byCode.put(field.code(), field);
    for (FieldDefinitionEntity field : fieldRepo.findByContentTypeIdOrderBySortOrder(type.getId())) {
      FieldSpec expected = byCode.get(field.getCode());
      if (expected == null) continue;
      boolean fieldNameBroken = needsLabelRepair(field.getName(), expected.name());
      boolean groupBroken = needsLabelRepair(field.getGroupName(), expected.group());
      if (fieldNameBroken) field.setName(expected.name());
      if (groupBroken) field.setGroupName(expected.group());
      if (fieldNameBroken || groupBroken) fieldRepo.save(field);
    }
  }

  private static boolean needsLabelRepair(String current, String expected) {
    if (expected == null || expected.isBlank()) return false;
    if (current == null || current.isBlank()) return true;
    return current.indexOf('?') >= 0;
  }

  private void configureContentMenu(ContentTypeEntity type, ModelSpec spec) {
    int order =
        switch (type.getCode()) {
          case "travel_hero" -> 290;
          case "travel_booking" -> 300;
          case "travel_destination" -> 310;
          case "travel_event" -> 320;
          case "travel_product" -> 330;
          default -> 0;
        };
    if (order == 0) return;
    type.setContentMenuEnabled(true);
    type.setContentMenuLabel(spec.name());
    type.setContentMenuOrder(order);
    typeRepo.save(type);
  }

  private ThemeInstall installTheme(UUID siteId) {
    VisualLayoutService.ThemeInfo existing =
        visualLayoutService.themes(siteId).stream()
            .filter(theme -> THEME_NAME.equalsIgnoreCase(theme.name()))
            .findFirst()
            .orElse(null);
    if (existing != null && "VALID".equals(existing.validity()) && existing.currentVersion() > 0) {
      ensureThemeStaticAssets(siteId, existing);
      return new ThemeInstall(existing.id(), false);
    }
    ThemePackageService.ImportResult imported =
        themePackageService.importZip(
            siteId, themeArchive(), ACTOR, "初始化 TravelLux 可视化主题");
    return new ThemeInstall(imported.themeId(), existing == null);
  }

  /** Backfill css/js for sites that installed TravelLux before static assets were split out. */
  private void ensureThemeStaticAssets(UUID siteId, VisualLayoutService.ThemeInfo theme) {
    List<ThemePackageService.BrowserFile> files = themePackageService.browserFiles(siteId, theme.id());
    String root = "themes/" + theme.name() + "/";
    boolean hasCss =
        files.stream().anyMatch(file -> (root + "css/site.css").equals(file.path()));
    boolean hasJs =
        files.stream().anyMatch(file -> (root + "js/site.js").equals(file.path()));
    if (hasCss && hasJs) return;
    // One import revision replaces the live package atomically (avoids multi-replace unique conflicts).
    themePackageService.importZip(
        siteId, themeArchive(), ACTOR, "补齐 TravelLux css/js 主题资源");
  }

  private ProjectInstall installTemplates(UUID siteId) {
    TemplateService.ProjInfo project =
        templateService.listProjects(siteId).stream()
            .filter(item -> PROJECT_NAME.equals(item.name()) && "FTL".equalsIgnoreCase(item.projectType()))
            .findFirst()
            .orElse(null);
    boolean created = project == null;
    if (created) {
      project = templateService.createProject(siteId, PROJECT_NAME, "FTL");
    }

    Map<String, String> managedTemplates =
        Map.of(
            "templates/travel-home.ftl", "presets/travel-lux/templates/travel-home.ftl",
            "templates/travel-content.ftl", "presets/travel-lux/templates/travel-content.ftl");
    List<TemplateService.FileInfo> files = templateService.listFiles(project.id());
    for (var entry : managedTemplates.entrySet()) {
      TemplateService.FileInfo existing =
          files.stream()
              .filter(file -> entry.getKey().equals(file.path()))
              .findFirst()
              .orElse(null);
      if (existing == null || existing.content().contains("powercms:travel-lux-preset")) {
        templateService.saveFile(project.id(), entry.getKey(), resourceText(entry.getValue()));
      }
    }
    if (project.currentRevision() == 0) {
      templateService.commit(project.id(), ACTOR, "初始化 TravelLux 页面模板");
    }
    return new ProjectInstall(project.id(), created);
  }

  private ContentInstall installContents(
      UUID siteId, Map<String, ContentTypeEntity> types) {
    List<ContentSeed> seeds = contentSeeds();
    int created = 0;
    int existing = 0;
    for (ContentSeed seed : seeds) {
      ContentTypeEntity type = requireType(types, seed.typeCode());
      ContentService.ContentInfo item =
          contentService.search(siteId, type.getId(), List.of(), null, seed.title()).stream()
              .filter(candidate -> seed.title().equals(candidate.title()))
              .findFirst()
              .orElse(null);
      if (item == null) {
        item =
            contentService.create(
                siteId,
                null,
                null,
                type.getId(),
                seed.title(),
                null,
                null,
                writeJson(seed.fields()),
                ACTOR);
        created++;
      } else {
        existing++;
      }
      if (!"PUBLISHED".equals(item.status())) {
        contentService.updateStatus(item.id(), "PUBLISHED");
      }
    }
    seedDemoViewCounts(siteId, types);
    return new ContentInstall(seeds, created, existing);
  }

  private void seedDemoViewCounts(UUID siteId, Map<String, ContentTypeEntity> types) {
    Map<String, Long> views =
        Map.of(
            "Blue Ice Adventure", 1280L,
            "London in Style", 960L,
            "Amazon Escape", 840L,
            "Alpine Ski Week", 620L,
            "Coastal Kayak", 410L,
            "Hidden Waterfalls", 280L);
    UUID typeId = requireType(types, "travel_destination").getId();
    for (var item : contentItemRepo.findByContentTypeId(typeId)) {
      if (!siteId.equals(item.getSiteId())) continue;
      Long count = views.get(item.getTitle());
      if (count != null && item.getViewCount() != count) {
        item.setViewCount(count);
        contentItemRepo.save(item);
      }
    }
  }

  private LayoutInstall installVisualLayout(
      UUID siteId, UUID themeId, Map<String, ContentTypeEntity> types) {
    record ContainerSpec(String zoneKey, String name, int max, String pre, String post,
                         List<String> typeCodes, List<String> templateFiles) {}
    List<ContainerSpec> specs = List.of(
        new ContainerSpec("hero", "TravelLux Hero Container", 1, "", "",
            List.of("travel_hero"), List.of("hero.ftl")),
        new ContainerSpec("booking", "TravelLux Booking Container", 1, "", "",
            List.of("travel_booking"), List.of("booking.ftl")),
        new ContainerSpec("destinations", "TravelLux Destination Container", 12,
            "<section class=\"tl-section\" id=\"destinations\"><div class=\"tl-section-inner\"><header class=\"tl-section-head\"><p class=\"tl-kicker\">YOUR NEXT STORY STARTS HERE</p><h2>POPULAR DESTINATIONS</h2></header><div class=\"tl-destination-grid\">",
            "</div><div class=\"tl-section-footer\"><a class=\"tl-button tl-button-orange\" href=\"#booking\">VIEW ALL DESTINATIONS</a></div></div></section>",
            List.of("travel_destination"), List.of("destination-card.ftl")),
        new ContainerSpec("events", "TravelLux Event Container", 12,
            "<section class=\"tl-section\" id=\"events\"><div class=\"tl-section-inner\"><header class=\"tl-section-head\"><p class=\"tl-kicker\">MEET US OUT THERE</p><h2>FEATURED EVENTS</h2></header><div class=\"tl-event-grid\">",
            "</div><div class=\"tl-section-footer\"><a class=\"tl-button tl-button-orange\" href=\"#booking\">VIEW ALL EVENTS</a></div></div></section>",
            List.of("travel_event"), List.of("event-card.ftl")),
        new ContainerSpec("products", "TravelLux Product Container", 16,
            "<section class=\"tl-section tl-section-alt\" id=\"products\"><div class=\"tl-section-inner\"><header class=\"tl-section-head\"><p class=\"tl-kicker\">TRIED, TESTED, LOVED</p><h2>RECOMMENDED PRODUCTS</h2></header><div class=\"tl-product-grid\">",
            "</div></div></section>",
            List.of("travel_product"), List.of("product-card.ftl")));

    Map<String, UUID> containerIds = new LinkedHashMap<>();
    for (ContainerSpec spec : specs) {
      var existing = visualLayoutService.containers(siteId).stream()
          .filter(item -> spec.name().equals(item.name())).findFirst().orElse(null);
      List<VisualLayoutService.ContainerStructureInput> structures = new ArrayList<>();
      for (int i = 0; i < spec.typeCodes().size(); i++) {
        structures.add(new VisualLayoutService.ContainerStructureInput(
            requireType(types, spec.typeCodes().get(i)).getId(),
            resourceText("presets/travel-lux/components/" + spec.templateFiles().get(i)), i * 10));
      }
      Map<String,Object> config = new LinkedHashMap<>();
      config.put("type", "CONTENT"); config.put("maxContents", spec.max());
      config.put("preLoopFtl", spec.pre()); config.put("postLoopFtl", spec.post());
      var saved = visualLayoutService.saveContainer(siteId, existing == null ? null : existing.id(), spec.name(),
          writeJson(config), structures, ACTOR, existing == null ? "Initialize dotCMS-style container" : "Synchronize dotCMS-style container");
      containerIds.put(spec.zoneKey(), saved.id());
    }

    VisualLayoutService.TemplateInfo template = visualLayoutService.templates(siteId, null, VISUAL_TEMPLATE_NAME).stream()
        .filter(item -> VISUAL_TEMPLATE_NAME.equals(item.name())).findFirst().orElse(null);
    boolean created = template == null;
    String rows = """
        {"mode":"VISUAL","header":true,"footer":true,"sidebar":"NONE","rows":[
          {"classes":["travel-row"],"boxes":[{"width":12,"zoneKeys":["hero","booking","destinations","events","products"]}]}
        ]}
        """;
    template = visualLayoutService.saveTemplate(siteId, template == null ? null : template.id(), themeId,
        VISUAL_TEMPLATE_NAME, "dotCMS-style independent Contentlets rendered by container structures", rows, "PUBLISHED", ACTOR,
        created ? "Initialize TravelLux visual template" : "Migrate TravelLux multi-container template");
    Map<String,VisualLayoutService.ZoneInfo> existingZones = template.zones().stream()
        .collect(java.util.stream.Collectors.toMap(VisualLayoutService.ZoneInfo::zoneKey, z -> z, (a,b)->a));
    int order = 0;
    for (ContainerSpec spec : specs) {
      var zone = existingZones.get(spec.zoneKey());
      template = visualLayoutService.saveZone(siteId, template.id(), zone == null ? null : zone.id(), spec.zoneKey(),
          containerIds.get(spec.zoneKey()), order, "{\"width\":12,\"classes\":[\"travel-" + spec.zoneKey() + "-zone\"]}", ACTOR);
      order += 10;
    }
    template = installPopularZeroContainer(siteId, template, order);
    return new LayoutInstall(template.id(), created);
  }

  /** ZERO_CONTENT 示例：按 viewCount 动态查询热门目的地，无需在页面 layout 手工投放。 */
  private VisualLayoutService.TemplateInfo installPopularZeroContainer(
      UUID siteId, VisualLayoutService.TemplateInfo template, int nextOrder) {
    String containerName = "TravelLux Popular Destinations (ZERO_CONTENT)";
    var existingContainer =
        visualLayoutService.containers(siteId).stream()
            .filter(item -> containerName.equals(item.name()))
            .findFirst()
            .orElse(null);
    String zeroFtl = resourceText("presets/travel-lux/components/popular-destinations-zero.ftl");
    Map<String, Object> config = new LinkedHashMap<>();
    config.put("type", "ZERO_CONTENT");
    config.put("zeroContentFtl", zeroFtl);
    config.put(
        "query",
        Map.of(
            "contentType", "travel_destination",
            "limit", 3,
            "orderBy", "viewCount",
            "as", "items"));
    var saved =
        visualLayoutService.saveContainer(
            siteId,
            existingContainer == null ? null : existingContainer.id(),
            containerName,
            writeJson(config),
            List.of(),
            ACTOR,
            existingContainer == null ? "Initialize dynamic popular destinations container" : "Sync dynamic popular destinations container");

    Map<String, VisualLayoutService.ZoneInfo> zones =
        template.zones().stream()
            .collect(java.util.stream.Collectors.toMap(VisualLayoutService.ZoneInfo::zoneKey, z -> z, (a, b) -> a));
    VisualLayoutService.ZoneInfo popular = zones.get("popular");
    if (popular == null) {
      template =
          visualLayoutService.saveZone(
              siteId,
              template.id(),
              null,
              "popular",
              saved.id(),
              nextOrder,
              "{\"width\":12,\"classes\":[\"travel-popular-zone\"]}",
              ACTOR);
      String layoutJson = appendZoneKey(template.layoutJson(), "popular");
      template =
          visualLayoutService.saveTemplate(
              siteId,
              template.id(),
              template.themeId(),
              template.name(),
              template.description(),
              layoutJson,
              template.status(),
              ACTOR,
              "Add popular ZERO_CONTENT zone");
    }
    return template;
  }

  private String appendZoneKey(String layoutJson, String zoneKey) {
    try {
      var root = mapper.readValue(layoutJson, com.fasterxml.jackson.databind.node.ObjectNode.class);
      var rows = root.withArray("rows");
      if (rows.isEmpty()) return layoutJson;
      var boxes = rows.get(0).withArray("boxes");
      if (boxes.isEmpty()) return layoutJson;
      var zoneKeys =
          (com.fasterxml.jackson.databind.node.ArrayNode) boxes.get(0).withArray("zoneKeys");
      for (var node : zoneKeys) {
        if (zoneKey.equals(node.asText())) return layoutJson;
      }
      zoneKeys.add(zoneKey);
      return mapper.writeValueAsString(root);
    } catch (Exception ex) {
      return layoutJson;
    }
  }

  private PageInstall installHomePage(
      UUID siteId,
      Map<String, ContentTypeEntity> types,
      UUID templateId,
      List<ContentSeed> seeds) {
    ContentTypeEntity pageType = requireType(types, "travel_home_page");
    ContentService.ContentInfo page =
        contentService.search(siteId, null, List.of(), null, null).stream()
            .filter(item -> HOME_ROUTE.equals(item.routePath()))
            .findFirst()
            .orElse(null);
    boolean created = page == null;
    if (page != null && !pageType.getId().equals(page.contentTypeId())) {
      throw new BusinessException(
          ErrorCodes.CONFLICT, "站点根路径 / 已被其他页面占用，请先调整该页面路径");
    }
    if (page == null) {
      page =
          contentService.create(
              siteId,
              null,
              null,
              pageType.getId(),
              "TravelLux 旅行生活首页",
              HOME_ROUTE,
              "/templates/travel-home.ftl",
              writeJson(
                  Map.of(
                      "seo_title_s", "TravelLux · Explore the world",
                      "seo_description_t", "发现目的地、户外体验、活动与旅行装备。")),
              ACTOR);
    }

    PageCompositionService.CompositionInfo current = compositionService.get(siteId, page.id());
    Map<String,List<ComponentInstance>> document = new LinkedHashMap<>();
    document.put("hero", migrateSingletonContent(siteId, types, current, "hero", "travel_hero", "TravelLux Homepage Hero", Map.of(
        "eyebrow_s", "GO WHERE YOU FEEL MOST ALIVE", "headline_s", "EXPLORE THE WORLD",
        "subtitle_s", "Hand-picked journeys, unforgettable adventures and premium travel gear.",
        "background_url_s", "https://images.unsplash.com/photo-1464822759023-fed622ff2c3b?auto=format&fit=crop&w=1800&q=88",
        "primary_label_s", "EXPLORE TRIPS", "primary_url_s", "#destinations")));
    document.put("booking", migrateSingletonContent(siteId, types, current, "booking", "travel_booking", "TravelLux Holiday Search", Map.of(
        "section_title_s", "BOOK YOUR HOLIDAY", "destination_label_s", "Where do you want to go?",
        "date_label_s", "Choose your dates", "guest_label_s", "2 guests", "button_label_s", "BOOK NOW")));
    document.put("destinations", contentPlacements(siteId, requireType(types, "travel_destination").getId(), ignored -> true));
    document.put("events", contentPlacements(siteId, requireType(types, "travel_event").getId(), ignored -> true));
    document.put("products", contentPlacements(siteId, requireType(types, "travel_product").getId(),
        fields -> "featured".equals(fields.get("collection_s"))));
    boolean legacy = current.zones().containsKey("main") || !current.zones().keySet().containsAll(document.keySet())
        || current.zones().getOrDefault("hero", List.of()).isEmpty()
        || current.zones().getOrDefault("booking", List.of()).isEmpty()
        || current.zones().getOrDefault("hero", List.of()).stream().anyMatch(x -> x.kind() != ComponentInstance.Kind.CONTENT)
        || current.zones().getOrDefault("booking", List.of()).stream().anyMatch(x -> x.kind() != ComponentInstance.Kind.CONTENT);
    if (current.id() == null || legacy) {
      compositionService.save(siteId, page.id(), templateId, document, current.version(), ACTOR);
    }
    if (!"PUBLISHED".equals(page.status())) {
      contentService.updateStatus(page.id(), "PUBLISHED");
    }
    return new PageInstall(page.id(), created);
  }

  private List<ComponentInstance> migrateSingletonContent(UUID siteId, Map<String,ContentTypeEntity> types,
      PageCompositionService.CompositionInfo current, String zoneKey, String typeCode, String seedTitle, Map<String,Object> defaults) {
    ContentTypeEntity type = requireType(types, typeCode);
    var existingPlacement = current.zones().getOrDefault(zoneKey, List.of()).stream()
        .filter(x -> x.kind() == ComponentInstance.Kind.CONTENT && type.getId().equals(x.componentTypeId())).findFirst().orElse(null);
    if (existingPlacement != null) return List.of(existingPlacement);
    Map<String,Object> values = new LinkedHashMap<>(defaults);
    current.zones().getOrDefault(zoneKey, List.of()).stream().filter(x -> x.kind() == ComponentInstance.Kind.EMBEDDED)
        .findFirst().ifPresent(x -> { if (x.data() != null) values.putAll(x.data()); });
    ContentService.ContentInfo item = contentService.search(siteId, type.getId(), List.of(), null, seedTitle).stream()
        .filter(x -> seedTitle.equals(x.title())).findFirst().orElse(null);
    if (item == null) item = contentService.create(siteId, null, null, type.getId(), seedTitle, null, null, writeJson(values), ACTOR);
    else {
      var version = contentService.getVersion(item.id(), item.currentVersion());
      if (!jsonEquals(version.fieldValues(), writeJson(values))) contentService.saveVersion(item.id(), writeJson(values), "Migrate page-embedded fields to Contentlet", ACTOR);
      item = contentService.get(item.id());
    }
    if (!"PUBLISHED".equals(item.status())) contentService.updateStatus(item.id(), "PUBLISHED");
    return List.of(new ComponentInstance(UUID.randomUUID().toString(), ComponentInstance.Kind.CONTENT, null, null,
        type.getId(), item.title(), Map.of(), null, null, Map.of(), 0, null, Map.of(), item.id(), null));
  }

  private boolean jsonEquals(String left, String right) {
    try { return mapper.readTree(left).equals(mapper.readTree(right)); } catch (Exception ignored) { return false; }
  }

  private List<ComponentInstance> contentPlacements(UUID siteId, UUID contentTypeId,
                                                            Predicate<Map<String,Object>> predicate) {
    List<ComponentInstance> result = new ArrayList<>();
    for (var item : contentItemRepo.findByContentTypeId(contentTypeId)) {
      if (!siteId.equals(item.getSiteId()) || "ARCHIVED".equals(item.getStatus())) continue;
      Map<String,Object> fields = new LinkedHashMap<>();
      try {
        var version = contentService.getVersion(item.getId(), item.getCurrentVersion());
        fields.putAll(mapper.readValue(version.fieldValues(), new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {}));
      } catch (Exception ex) { continue; }
      if (!predicate.test(fields)) continue;
      result.add(new ComponentInstance(UUID.randomUUID().toString(), ComponentInstance.Kind.CONTENT,
          null, null, contentTypeId, item.getTitle(), Map.of(), null, null, Map.of(), result.size(),
          null, Map.of(), item.getId(), null));
    }
    result.sort(java.util.Comparator.comparingInt(instance -> {
      try {
        var version = contentService.getVersion(instance.contentItemId(),
            contentItemRepo.findById(instance.contentItemId()).orElseThrow().getCurrentVersion());
        var node = mapper.readTree(version.fieldValues()); return node.path("sort_order_i").asInt(0);
      } catch (Exception ex) { return 0; }
    }));
    return List.copyOf(result);
  }

  private ComponentInstance component(
      Map<String, ContentTypeEntity> types,
      String typeCode,
      String title,
      Map<String, Object> data) {
    return new ComponentInstance(
        UUID.randomUUID().toString(),
        ComponentInstance.Kind.EMBEDDED,
        null,
        null,
        requireType(types, typeCode).getId(),
        title,
        data,
        null,
        null,
        Map.of(),
        0);
  }

  private String cardsJson(
      List<ContentSeed> seeds,
      String typeCode,
      Predicate<Map<String, Object>> predicate) {
    List<Map<String, Object>> cards = new ArrayList<>();
    seeds.stream()
        .filter(seed -> typeCode.equals(seed.typeCode()))
        .filter(seed -> predicate.test(seed.fields()))
        .forEach(
            seed -> {
              Map<String, Object> card = new LinkedHashMap<>();
              card.put("title", seed.title());
              card.putAll(seed.fields());
              cards.add(card);
            });
    return writeJson(cards);
  }

  private String componentTemplateName(String code) {
    return switch (code) {
      case "travel_hero_component" -> "hero.ftl";
      case "travel_booking_component" -> "booking.ftl";
      case "travel_destination_grid_component" -> "destinations.ftl";
      case "travel_shop_component" -> "shop.ftl";
      case "travel_event_grid_component" -> "events.ftl";
      case "travel_product_grid_component" -> "products.ftl";
      default -> throw new IllegalArgumentException("未知 TravelLux 组件: " + code);
    };
  }

  private ContentTypeEntity requireType(Map<String, ContentTypeEntity> types, String code) {
    return Objects.requireNonNull(types.get(code), "缺少内容模型 " + code);
  }

  private CreateContentTypeRequest.FieldDefDto field(FieldSpec spec) {
    return new CreateContentTypeRequest.FieldDefDto(
        null,
        spec.code(),
        spec.name(),
        spec.type(),
        spec.required(),
        spec.order(),
        spec.group(),
        spec.help(),
        null,
        false,
        null,
        null,
        null,
        false,
        true,
        true,
        false,
        false,
        true,
        false,
        false);
  }

  private FieldSpec f(
      String code, String name, String type, boolean required, int order, String group) {
    return new FieldSpec(code, name, type, required, order, group, null);
  }

  private List<ModelSpec> modelSpecs() {
    return List.of(
        new ModelSpec(
            "travel_home_page",
            "TravelLux 首页",
            "旅游生活站首页与 SEO 信息",
            "PAGE",
            "/templates/travel-home.ftl",
            "HomeOutlined",
            List.of(
                f("seo_title_s", "SEO 标题", "STRING", false, 10, "SEO"),
                f("seo_description_t", "SEO 描述", "LONG_TEXT", false, 20, "SEO"))),
        new ModelSpec(
            "travel_destination",
            "旅游目的地",
            "目的地卡片、介绍与图片",
            "SHARED",
            "/templates/travel-content.ftl",
            "EnvironmentOutlined",
            List.of(
                f("summary_t", "简介", "LONG_TEXT", true, 10, "内容"),
                f("location_s", "地点", "STRING", false, 20, "内容"),
                f("image_url_s", "图片 URL", "STRING", true, 30, "视觉"),
                f("badge_s", "角标", "STRING", false, 40, "视觉"),
                f("target_url_s", "跳转地址", "STRING", false, 50, "链接"),
                f("sort_order_i", "排序", "INTEGER", false, 60, "发布"))),
        new ModelSpec(
            "travel_event",
            "旅行活动",
            "旅行营地、课程与特色活动",
            "SHARED",
            "/templates/travel-content.ftl",
            "CalendarOutlined",
            List.of(
                f("summary_t", "简介", "LONG_TEXT", true, 10, "内容"),
                f("location_s", "地点", "STRING", false, 20, "内容"),
                f("date_day_s", "日期", "STRING", true, 30, "时间"),
                f("date_month_s", "月份", "STRING", true, 40, "时间"),
                f("image_url_s", "图片 URL", "STRING", true, 50, "视觉"),
                f("target_url_s", "跳转地址", "STRING", false, 60, "链接"),
                f("sort_order_i", "排序", "INTEGER", false, 70, "发布"))),
        new ModelSpec(
            "travel_product",
            "旅行产品",
            "户外装备与旅行生活产品",
            "SHARED",
            "/templates/travel-content.ftl",
            "ShoppingOutlined",
            List.of(
                f("summary_t", "简介", "LONG_TEXT", false, 10, "内容"),
                f("collection_s", "产品集合", "STRING", true, 20, "内容"),
                f("price_s", "价格", "STRING", false, 30, "销售"),
                f("old_price_s", "原价", "STRING", false, 40, "销售"),
                f("badge_s", "角标", "STRING", false, 50, "销售"),
                f("image_url_s", "图片 URL", "STRING", true, 60, "视觉"),
                f("target_url_s", "跳转地址", "STRING", false, 70, "链接"),
                f("sort_order_i", "排序", "INTEGER", false, 80, "发布"))),
        new ModelSpec(
            "travel_hero",
            "TravelLux 主视觉",
            "首页主视觉 Contentlet",
            "SHARED",
            "/templates/travel-content.ftl",
            "PictureOutlined",
            List.of(
                f("eyebrow_s", "眉题", "STRING", false, 10, "内容"),
                f("headline_s", "主标题", "STRING", true, 20, "内容"),
                f("subtitle_s", "副标题", "LONG_TEXT", false, 30, "内容"),
                f("background_url_s", "背景图 URL", "STRING", true, 40, "视觉"),
                f("primary_label_s", "按钮文字", "STRING", false, 50, "链接"),
                f("primary_url_s", "按钮地址", "STRING", false, 60, "链接"))),
        new ModelSpec(
            "travel_booking",
            "TravelLux 假期搜索",
            "假期搜索表单 Contentlet",
            "SHARED",
            "/templates/travel-content.ftl",
            "SearchOutlined",
            List.of(
                f("section_title_s", "标题", "STRING", true, 10, "内容"),
                f("destination_label_s", "目的地提示", "STRING", false, 20, "内容"),
                f("date_label_s", "日期提示", "STRING", false, 30, "内容"),
                f("guest_label_s", "人数提示", "STRING", false, 40, "内容"),
                f("button_label_s", "按钮文字", "STRING", false, 50, "内容"))),
        new ModelSpec(
            "travel_hero_component",
            "TravelLux 主视觉",
            "全宽图片、标题与行动按钮",
            "COMPONENT",
            null,
            "PictureOutlined",
            List.of(
                f("eyebrow_s", "眉题", "STRING", false, 10, "内容"),
                f("headline_s", "主标题", "STRING", true, 20, "内容"),
                f("subtitle_s", "副标题", "LONG_TEXT", false, 30, "内容"),
                f("background_url_s", "背景图 URL", "STRING", true, 40, "视觉"),
                f("primary_label_s", "按钮文字", "STRING", false, 50, "链接"),
                f("primary_url_s", "按钮地址", "STRING", false, 60, "链接"))),
        new ModelSpec(
            "travel_booking_component",
            "TravelLux 假期搜索",
            "目的地、日期、人数与预订按钮",
            "COMPONENT",
            null,
            "SearchOutlined",
            List.of(
                f("section_title_s", "标题", "STRING", true, 10, "内容"),
                f("destination_label_s", "目的地提示", "STRING", false, 20, "内容"),
                f("date_label_s", "日期提示", "STRING", false, 30, "内容"),
                f("guest_label_s", "人数提示", "STRING", false, 40, "内容"),
                f("button_label_s", "按钮文字", "STRING", false, 50, "内容"))),
        sectionComponent(
            "travel_destination_grid_component",
            "TravelLux 目的地网格",
            "三列目的地内容卡片",
            "AppstoreOutlined",
            true),
        sectionComponent(
            "travel_shop_component",
            "TravelLux 探险商店",
            "杂志式产品拼贴区块",
            "ShoppingOutlined",
            false),
        sectionComponent(
            "travel_event_grid_component",
            "TravelLux 活动网格",
            "三列活动与日期卡片",
            "CalendarOutlined",
            true),
        sectionComponent(
            "travel_product_grid_component",
            "TravelLux 产品网格",
            "四列推荐产品卡片",
            "ShoppingOutlined",
            false));
  }

  private ModelSpec sectionComponent(
      String code, String name, String description, String icon, boolean button) {
    List<FieldSpec> fields = new ArrayList<>();
    fields.add(f("section_kicker_s", "区块眉题", "STRING", false, 10, "内容"));
    fields.add(f("section_title_s", "区块标题", "STRING", true, 20, "内容"));
    fields.add(f("items_json_s", "卡片数据", "JSON", true, 30, "内容"));
    if (button) fields.add(f("button_label_s", "底部按钮", "STRING", false, 40, "内容"));
    return new ModelSpec(code, name, description, "COMPONENT", null, icon, List.copyOf(fields));
  }

  private List<ContentSeed> contentSeeds() {
    return List.of(
        destination(
            "Blue Ice Adventure",
            "Iceland",
            "Walk beneath frozen waterfalls and climb a glacier with expert local guides.",
            "NEW",
            "https://images.unsplash.com/photo-1520769945061-0a448c463865?auto=format&fit=crop&w=900&q=84",
            10),
        destination(
            "London in Style",
            "United Kingdom",
            "Architecture, markets and hidden neighbourhoods through a local lens.",
            "CITY",
            "https://images.unsplash.com/photo-1513635269975-59663e0ac1ad?auto=format&fit=crop&w=900&q=84",
            20),
        destination(
            "Amazon Escape",
            "Brazil",
            "Paddle quiet jungle waterways and wake to the sound of the rainforest.",
            "WILD",
            "https://images.unsplash.com/photo-1516690561799-46d8f74f9abf?auto=format&fit=crop&w=900&q=84",
            30),
        destination(
            "Alpine Ski Week",
            "French Alps",
            "Fresh powder, warm chalets and high-altitude views for every ability.",
            "SNOW",
            "https://images.unsplash.com/photo-1486911278844-a81c5267e227?auto=format&fit=crop&w=900&q=84",
            40),
        destination(
            "Coastal Kayak",
            "Norway",
            "Explore glassy fjords, colourful villages and remote island camps.",
            "WATER",
            "https://images.unsplash.com/photo-1544551763-46a013bb70d5?auto=format&fit=crop&w=900&q=84",
            50),
        destination(
            "Hidden Waterfalls",
            "Costa Rica",
            "A green-season journey through cloud forest trails and secret swimming holes.",
            "NATURE",
            "https://images.unsplash.com/photo-1432405972618-c60b0225b8f9?auto=format&fit=crop&w=900&q=84",
            60),
        event(
            "Christmas Surf Camp",
            "Taghazout, Morocco",
            "Warm waves, morning yoga and long-table dinners beside the Atlantic.",
            "26",
            "NOV",
            "https://images.unsplash.com/photo-1502680390469-be75c86b636f?auto=format&fit=crop&w=1000&q=84",
            10),
        event(
            "Spring Surf Camp",
            "Ericeira, Portugal",
            "A social week of guided surf sessions, coast walks and local food.",
            "08",
            "DEC",
            "https://images.unsplash.com/photo-1455729552865-3658a5d39692?auto=format&fit=crop&w=1000&q=84",
            20),
        event(
            "Speed Ice Tour",
            "Svalbard, Norway",
            "Small-group snowmobile touring across a dramatic Arctic landscape.",
            "03",
            "JAN",
            "https://images.unsplash.com/photo-1529669851596-ba9a5549af95?auto=format&fit=crop&w=1000&q=84",
            30),
        product(
            "Further - Adventure Film",
            "shop",
            "",
            "",
            "FEATURED",
            "A cinematic journey through remote mountains.",
            "https://images.unsplash.com/photo-1464278533981-50106e6176b1?auto=format&fit=crop&w=1200&q=84",
            10),
        product(
            "Nitro Select Boots",
            "shop",
            "614.00",
            "",
            "",
            "Responsive snowboard boots built for long mountain days.",
            "https://images.unsplash.com/photo-1551698618-1dfe5d97d256?auto=format&fit=crop&w=800&q=84",
            20),
        product(
            "Expedition Wetsuit",
            "shop",
            "109.95",
            "",
            "",
            "Warm, flexible protection for cool-water sessions.",
            "https://images.unsplash.com/photo-1530789253388-582c481c54b0?auto=format&fit=crop&w=800&q=84",
            30),
        product(
            "Alpine Freeride Set",
            "shop",
            "549.99",
            "",
            "",
            "A dependable powder-day setup for adventurous riders.",
            "https://images.unsplash.com/photo-1486911278844-a81c5267e227?auto=format&fit=crop&w=1200&q=84",
            40),
        product(
            "Trail Essentials Pack",
            "shop",
            "449.99",
            "",
            "",
            "Technical layers, goggles and a compact day pack.",
            "https://images.unsplash.com/photo-1551698618-1dfe5d97d256?auto=format&fit=crop&w=800&q=84",
            50),
        product(
            "Women's Summit Jacket",
            "shop",
            "299.95",
            "359.95",
            "SALE",
            "Weatherproof warmth with an easy, active fit.",
            "https://images.unsplash.com/photo-1464822759023-fed622ff2c3b?auto=format&fit=crop&w=800&q=84",
            60),
        product(
            "Open Water Wetsuit",
            "featured",
            "189.95",
            "",
            "",
            "Streamlined warmth for open-water swimming and surfing.",
            "https://images.unsplash.com/photo-1560089000-7433a4ebbd64?auto=format&fit=crop&w=800&q=84",
            70),
        product(
            "All-Mountain Board",
            "featured",
            "699.00",
            "",
            "",
            "A confident, versatile board for groomers and fresh snow.",
            "https://images.unsplash.com/photo-1522056615691-da7b8106c665?auto=format&fit=crop&w=800&q=84",
            80),
        product(
            "Touring Paddleboard",
            "featured",
            "699.00",
            "",
            "",
            "Stable, efficient and ready for full-day coastal touring.",
            "https://images.unsplash.com/photo-1544551763-46a013bb70d5?auto=format&fit=crop&w=800&q=84",
            90),
        product(
            "Carbon Snow Bindings",
            "featured",
            "574.00",
            "",
            "",
            "Lightweight support and precise response on technical terrain.",
            "https://images.unsplash.com/photo-1484352491158-830ef5692bb3?auto=format&fit=crop&w=800&q=84",
            100));
  }

  private ContentSeed destination(
      String title,
      String location,
      String summary,
      String badge,
      String image,
      int order) {
    return new ContentSeed(
        "travel_destination",
        title,
        map(
            "summary_t", summary,
            "location_s", location,
            "image_url_s", image,
            "badge_s", badge,
            "target_url_s", "#",
            "sort_order_i", order));
  }

  private ContentSeed event(
      String title,
      String location,
      String summary,
      String day,
      String month,
      String image,
      int order) {
    return new ContentSeed(
        "travel_event",
        title,
        map(
            "summary_t", summary,
            "location_s", location,
            "date_day_s", day,
            "date_month_s", month,
            "image_url_s", image,
            "target_url_s", "#",
            "sort_order_i", order));
  }

  private ContentSeed product(
      String title,
      String collection,
      String price,
      String oldPrice,
      String badge,
      String summary,
      String image,
      int order) {
    return new ContentSeed(
        "travel_product",
        title,
        map(
            "summary_t", summary,
            "collection_s", collection,
            "price_s", price,
            "old_price_s", oldPrice,
            "badge_s", badge,
            "image_url_s", image,
            "target_url_s", "#",
            "sort_order_i", order));
  }

  private Map<String, Object> map(Object... values) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < values.length; i += 2) {
      result.put(String.valueOf(values[i]), values[i + 1]);
    }
    return result;
  }

  private byte[] themeArchive() {
    Map<String, String> files = new LinkedHashMap<>();
    files.put("theme.yaml", resourceText("presets/travel-lux/theme/theme.yaml"));
    files.put("template.ftl", resourceText("presets/travel-lux/theme/template.ftl"));
    files.put("html_head.ftl", resourceText("presets/travel-lux/theme/html_head.ftl"));
    files.put("header.ftl", resourceText("presets/travel-lux/theme/header.ftl"));
    files.put("footer.ftl", resourceText("presets/travel-lux/theme/footer.ftl"));
    files.put("css/site.css", resourceText("presets/travel-lux/theme/css/site.css"));
    files.put("js/site.js", resourceText("presets/travel-lux/theme/js/site.js"));
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
        for (var entry : files.entrySet()) {
          ZipEntry zipEntry = new ZipEntry(entry.getKey());
          zipEntry.setTime(0);
          zip.putNextEntry(zipEntry);
          zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
          zip.closeEntry();
        }
      }
      return bytes.toByteArray();
    } catch (IOException ex) {
      throw new BusinessException(ErrorCodes.INTERNAL_ERROR, "TravelLux 主题包生成失败");
    }
  }

  private String resourceText(String path) {
    try (InputStream input = new ClassPathResource(path).getInputStream()) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new BusinessException(ErrorCodes.INTERNAL_ERROR, "缺少 TravelLux 预置资源: " + path);
    }
  }

  private String writeJson(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception ex) {
      throw new BusinessException(ErrorCodes.INTERNAL_ERROR, "TravelLux 预置数据生成失败");
    }
  }

  private record ModelInstall(
      Map<String, ContentTypeEntity> types, int created, int existing) {}

  private record ThemeInstall(UUID themeId, boolean created) {}

  private record ProjectInstall(UUID projectId, boolean created) {}

  private record ContentInstall(
      List<ContentSeed> seeds, int created, int existing) {}

  private record LayoutInstall(UUID templateId, boolean created) {}

  private record PageInstall(UUID pageId, boolean created) {}

  private record FieldSpec(
      String code,
      String name,
      String type,
      boolean required,
      int order,
      String group,
      String help) {}

  private record ModelSpec(
      String code,
      String name,
      String description,
      String objectType,
      String template,
      String icon,
      List<FieldSpec> fields) {}

  private record ContentSeed(
      String typeCode, String title, Map<String, Object> fields) {}
}
