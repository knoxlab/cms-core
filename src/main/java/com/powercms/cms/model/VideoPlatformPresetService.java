package com.powercms.cms.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.content.CategoryService;
import com.powercms.cms.content.ContentService;
import com.powercms.cms.content.entity.CategoryEntity;
import com.powercms.cms.content.repo.CategoryRepository;
import com.powercms.cms.content.relation.ContentRelationEntity;
import com.powercms.cms.content.relation.ContentRelationRepository;
import com.powercms.cms.model.dto.CreateContentTypeRequest;
import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.entity.FieldDefinitionEntity;
import com.powercms.cms.model.entity.FormLayoutEntity;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.cms.model.repo.FieldDefinitionRepository;
import com.powercms.cms.model.repo.FormLayoutRepository;
import com.powercms.cms.sitepackage.PageCompositionService;
import com.powercms.cms.sitepackage.TemplateService;
import com.powercms.cms.sitepackage.VisualLayoutService;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class VideoPlatformPresetService {
  private final ContentTypeRepository typeRepo;
  private final ContentTypeService typeService;
  private final FieldDefinitionRepository fieldRepo;
  private final FormLayoutRepository layoutRepo;
  private final CategoryRepository categoryRepo;
  private final CategoryService categoryService;
  private final ContentRelationRepository relationRepo;
  private final TemplateService templateService;
  private final ContentService contentService;
  private final PageCompositionService compositionService;
  private final VisualLayoutService visualLayoutService;
  private final ObjectMapper mapper;

  VideoPlatformPresetService(
      ContentTypeRepository typeRepo,
      ContentTypeService typeService,
      FieldDefinitionRepository fieldRepo,
      FormLayoutRepository layoutRepo,
      CategoryRepository categoryRepo,
      CategoryService categoryService,
      ContentRelationRepository relationRepo,
      TemplateService templateService,
      ContentService contentService,
      PageCompositionService compositionService,
      VisualLayoutService visualLayoutService,
      ObjectMapper mapper) {
    this.typeRepo = typeRepo;
    this.typeService = typeService;
    this.fieldRepo = fieldRepo;
    this.layoutRepo = layoutRepo;
    this.categoryRepo = categoryRepo;
    this.categoryService = categoryService;
    this.relationRepo = relationRepo;
    this.templateService = templateService;
    this.contentService = contentService;
    this.compositionService = compositionService;
    this.visualLayoutService = visualLayoutService;
    this.mapper = mapper;
  }

  public record PresetResult(
      int createdModels,
      int existingModels,
      int createdCategories,
      int existingCategories,
      int createdRelations,
      List<String> models,
      List<String> categories) {}

  public PresetResult install(UUID siteId) {
    if (siteId == null) throw new BusinessException(ErrorCodes.NOT_FOUND, "站点不存在");
    Map<String, ContentTypeEntity> types = new LinkedHashMap<>();
    int createdModels = 0, existingModels = 0;
    for (ModelSpec spec : modelSpecs()) {
      ContentTypeEntity existing =
          typeRepo.findBySiteIdAndCodeOrderByVersionAsc(siteId, spec.code()).stream()
              .reduce((a, b) -> b)
              .orElse(null);
      if (existing != null) {
        types.put(spec.code(), existing);
        existingModels++;
        continue;
      }
      var request =
          new CreateContentTypeRequest(
              spec.code(),
              spec.name(),
              spec.description(),
              "PAGE",
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
              null,
              null,
              null,
              null,
              null);
      var response = typeService.create(siteId, request);
      types.put(spec.code(), typeRepo.findById(response.id()).orElseThrow());
      createdModels++;
    }

    int[] categoryCounts = {0, 0};
    List<String> categoryNames = new ArrayList<>();
    for (CategorySpec root : categorySpecs()) {
      installTree(siteId, null, root, categoryCounts, categoryNames);
    }

    int createdRelations = 0;
    for (RelationSpec spec : relationSpecs()) {
      if (relationRepo.existsBySiteIdAndRelationName(siteId, spec.name())) continue;
      ContentTypeEntity source = types.get(spec.source()), target = types.get(spec.target());
      if (source == null || target == null) continue;
      ContentRelationEntity relation = new ContentRelationEntity();
      relation.setSiteId(siteId);
      relation.setRelationName(spec.name());
      relation.setCardinality(spec.cardinality());
      relation.setSourceTypeId(source.getId());
      relation.setTargetTypeId(target.getId());
      relation.setDescription(spec.description());
      relation.setCreatedBy("video-platform-preset");
      relationRepo.save(relation);
      createdRelations++;
    }

    ensureOptionalPageReference(types.get("video_detail_page"), "work_ref_s", "视频作品");
    ensureOptionalPageReference(types.get("series_detail_page"), "work_ref_s", "视频作品");
    ensureOptionalPageReference(types.get("episode_detail_page"), "episode_ref_s", "视频集");
    ContentTypeEntity videoWork = types.get("video_work");
    if (videoWork != null) {
      ensureVideoWorkFields(siteId, videoWork);
      ensureRelationshipField(videoWork, "person_refs_s", "人物", "video_work_person", 200);
      ensureRelationshipField(videoWork, "organization_refs_s", "制作机构", "video_work_organization", 210);
      ensureRelationshipField(videoWork, "season_refs_s", "视频季", "video_work_season", 220);
    }
    ContentTypeEntity playlist = types.get("playlist");
    if (playlist != null) {
      ensureRelationshipField(playlist, "video_refs_ss", "视频列表", "playlist_video_work", 30);
    }
    ContentTypeEntity topic = types.get("topic");
    if (topic != null) {
      ensureRelationshipField(topic, "video_refs_ss", "收录作品", "topic_video_work", 30);
    }
    ContentTypeEntity course = types.get("knowledge_course");
    if (course != null) {
      ensureRelationshipField(course, "chapter_refs_ss", "章节", "course_chapter", 50);
    }
    ContentTypeEntity liveChannel = types.get("live_channel");
    if (liveChannel != null) {
      ensureRelationshipField(liveChannel, "event_refs_ss", "直播活动", "live_channel_event", 50);
      ensureRelationshipField(liveChannel, "schedule_refs_ss", "节目单", "live_channel_schedule", 60);
    }

    int order = 10;
    for (String code :
        List.of(
            "video_home",
            "video_list_page",
            "video_detail_page",
            "series_detail_page",
            "episode_detail_page",
            "live_home",
            "video_work",
            "video_season",
            "video_episode",
            "knowledge_course",
            "live_channel",
            "live_event",
            "topic",
            "playlist",
            "person",
            "organization")) {
      ContentTypeEntity type = types.get(code);
      if (type != null) enableMenu(type, type.getName(), order);
      order += 10;
    }

    seedTemplates(siteId);
    Map<String, VisualLayoutService.TemplateInfo> visualTemplates = seedVisualLayouts(siteId, types);
    seedPages(siteId, types, visualTemplates);
    return new PresetResult(
        createdModels,
        existingModels,
        categoryCounts[0],
        categoryCounts[1],
        createdRelations,
        new ArrayList<>(types.keySet()),
        categoryNames);
  }

  private void installTree(
      UUID siteId, UUID parentId, CategorySpec spec, int[] counts, List<String> names) {
    CategoryEntity entity = ensureCategory(siteId, parentId, spec, counts, names);
    for (CategorySpec child : spec.children()) {
      installTree(siteId, entity.getId(), child, counts, names);
    }
  }

  private CategoryEntity ensureCategory(
      UUID siteId, UUID parentId, CategorySpec spec, int[] counts, List<String> names) {
    CategoryEntity existing =
        categoryRepo.findBySiteId(siteId).stream()
            .filter(
                c ->
                    Objects.equals(parentId, c.getParentId())
                        && spec.alias().equals(c.getPathAlias()))
            .findFirst()
            .orElse(null);
    if (existing != null) {
      counts[1]++;
      return existing;
    }
    var created = categoryService.create(siteId, parentId, spec.name(), spec.alias(), spec.order());
    CategoryEntity entity = categoryRepo.findById(created.id()).orElseThrow();
    counts[0]++;
    names.add(spec.name());
    return entity;
  }

  private void enableMenu(ContentTypeEntity type, String label, int order) {
    type.setContentMenuEnabled(true);
    type.setContentMenuLabel(label);
    type.setContentMenuOrder(order);
    typeRepo.save(type);
  }

  private void ensureVideoWorkFields(UUID siteId, ContentTypeEntity type) {
    ensureField(type, "poster_h_m", "横版海报", "MEDIA_REF", 21, "基本信息", null);
    ensureField(type, "poster_v_m", "竖版海报", "MEDIA_REF", 22, "基本信息", null);
    ensureField(type, "trailer_m", "预告片", "MEDIA_REF", 23, "基本信息", null);
    ensureField(type, "year_i", "制作年份", "INTEGER", 41, "基本信息", null);
    ensureField(type, "publish_at_dt", "定时发布时间", "DATE_TIME", 80, "发布与版权", null);
    ensureField(type, "copyright_start_dt", "版权开始", "DATE_TIME", 81, "发布与版权", null);
    ensureField(type, "copyright_end_dt", "版权结束", "DATE_TIME", 82, "发布与版权", null);
    UUID genre = rootCategoryId(siteId, "genre");
    UUID decade = rootCategoryId(siteId, "decade");
    UUID audience = rootCategoryId(siteId, "audience");
    UUID operation = rootCategoryId(siteId, "operation");
    UUID region = rootCategoryId(siteId, "region");
    if (genre != null) ensureField(type, "genre_cat_s", "题材", "CATEGORY", 60, "分类属性", taxonomyConfig(genre));
    if (decade != null) ensureField(type, "decade_cat_s", "年代", "CATEGORY", 61, "分类属性", taxonomyConfig(decade));
    if (audience != null)
      ensureField(type, "audience_cat_s", "人群", "CATEGORY", 62, "分类属性", taxonomyConfig(audience));
    if (operation != null)
      ensureField(type, "operation_cat_s", "运营标签", "CATEGORY", 63, "分类属性", taxonomyConfig(operation));
    if (region != null)
      ensureField(type, "region_cat_s", "地区分类", "CATEGORY", 64, "分类属性", taxonomyConfig(region));
  }

  private UUID rootCategoryId(UUID siteId, String alias) {
    return categoryRepo.findBySiteId(siteId).stream()
        .filter(c -> c.getParentId() == null && alias.equals(c.getPathAlias()))
        .map(CategoryEntity::getId)
        .findFirst()
        .orElse(null);
  }

  private String taxonomyConfig(UUID rootId) {
    return "{\"taxonomyRoot\":\"" + rootId + "\"}";
  }

  private void ensureField(
      ContentTypeEntity type,
      String code,
      String name,
      String fieldType,
      int order,
      String group,
      String config) {
    FieldDefinitionEntity field =
        fieldRepo.findByContentTypeIdOrderBySortOrder(type.getId()).stream()
            .filter(f -> code.equals(f.getCode()))
            .findFirst()
            .orElseGet(FieldDefinitionEntity::new);
    if (field.getId() == null) field.setId(UUID.randomUUID());
    field.setContentTypeId(type.getId());
    field.setCode(code);
    field.setName(name);
    field.setFieldType(fieldType);
    field.setRequired(false);
    field.setSortOrder(order);
    field.setGroupName(group);
    field.setAdminIndex(true);
    field.setPublicIndex(true);
    field.setFilterable(true);
    if (config != null) field.setFieldConfig(config);
    fieldRepo.save(field);
    ensureLayout(type, field, order);
  }

  private void ensureOptionalPageReference(ContentTypeEntity type, String code, String name) {
    if (type != null) ensureField(type, code, name, "CONTENT_REF", 10, "页面数据", null);
  }
  private void ensureRelationshipField(
      ContentTypeEntity type, String code, String name, String relationName, int order) {
    var relation = relationRepo.findBySiteIdAndRelationName(type.getSiteId(), relationName).orElse(null);
    if (relation == null) return;
    ensureField(
        type, code, name, "RELATIONSHIP", order, "关联内容", "{\"relationId\":\"" + relation.getId() + "\"}");
  }

  private void ensureLayout(ContentTypeEntity type, FieldDefinitionEntity field, int order) {
    UUID parentId =
        layoutRepo.findByContentTypeIdOrderBySortOrder(type.getId()).stream()
            .filter(l -> "FORM_SECTION".equals(l.getElementType()))
            .map(FormLayoutEntity::getElementId)
            .findFirst()
            .orElse(null);
    boolean layoutExists =
        layoutRepo.findByContentTypeIdOrderBySortOrder(type.getId()).stream()
            .anyMatch(l -> field.getId().equals(l.getElementId()));
    if (layoutExists) return;
    FormLayoutEntity layout = new FormLayoutEntity();
    layout.setContentTypeId(type.getId());
    layout.setElementType("FIELD");
    layout.setElementId(field.getId());
    layout.setParentId(parentId);
    layout.setSortOrder(order);
    layoutRepo.save(layout);
  }

  private void seedTemplates(UUID siteId) {
    var project =
        templateService.listProjects(siteId).stream()
            .filter(p -> "视频平台模板".equals(p.name()))
            .findFirst()
            .orElseGet(() -> templateService.createProject(siteId, "视频平台模板", "FTL"));
    Set<String> existing =
        templateService.listFiles(project.id()).stream()
            .map(TemplateService.FileInfo::path)
            .collect(java.util.stream.Collectors.toSet());
    for (var entry : templateFiles().entrySet()) {
      String path = entry.getKey().startsWith("/") ? entry.getKey().substring(1) : entry.getKey();
      if (!existing.contains(path) || isManagedVisualTemplate(project.id(), path)) {
        templateService.saveFile(project.id(), path, entry.getValue());
      }
    }
  }

  private Map<String, VisualLayoutService.TemplateInfo> seedVisualLayouts(UUID siteId, Map<String, ContentTypeEntity> types) {
    var theme = visualLayoutService.themes(siteId).stream().filter(t -> "视频平台主题".equals(t.name())).findFirst()
        .orElseGet(() -> visualLayoutService.saveTheme(siteId, null, "视频平台主题", "视频点播、直播、知识和专题统一主题", "{\"header\":true,\"footer\":true,\"colorMode\":\"dark\"}", "PUBLISHED"));
    Map<String, VisualLayoutService.ContainerInfo> containers = new LinkedHashMap<>();
    containers.put("hero", ensureVisualContainer(siteId, "视频轮播容器", types, "hero_banner_component"));
    containers.put("video-grid", ensureVisualContainer(siteId, "视频网格容器", types, "video_grid_component", "topic_grid_component"));
    containers.put("detail", ensureVisualContainer(siteId, "视频详情容器", types, "video_detail_component"));
    containers.put("series", ensureVisualContainer(siteId, "季与分集容器", types, "season_list_component", "episode_list_component"));
    containers.put("live", ensureVisualContainer(siteId, "直播内容容器", types, "live_grid_component"));
    containers.put("topic", ensureVisualContainer(siteId, "专题内容容器", types, "hero_banner_component", "topic_grid_component", "video_grid_component"));
    Map<String, VisualLayoutService.TemplateInfo> result = new LinkedHashMap<>();
    result.put("视频首页模板", ensureVisualTemplate(siteId, theme.id(), containers, "视频首页模板", List.of(zone("hero", "hero", 10), zone("featured", "video-grid", 20), zone("topics", "topic", 30))));
    result.put("视频列表模板", ensureVisualTemplate(siteId, theme.id(), containers, "视频列表模板", List.of(zone("header", "hero", 10), zone("video-list", "video-grid", 20))));
    result.put("视频详情模板", ensureVisualTemplate(siteId, theme.id(), containers, "视频详情模板", List.of(zone("video-detail", "detail", 10), zone("seasons", "series", 20), zone("recommendations", "video-grid", 30))));
    result.put("剧集详情模板", ensureVisualTemplate(siteId, theme.id(), containers, "剧集详情模板", List.of(zone("series-detail", "detail", 10), zone("season-list", "series", 20), zone("episode-list", "series", 30))));
    result.put("分集详情模板", ensureVisualTemplate(siteId, theme.id(), containers, "分集详情模板", List.of(zone("episode-player", "detail", 10), zone("episode-list", "series", 20), zone("recommendations", "video-grid", 30))));
    result.put("直播首页模板", ensureVisualTemplate(siteId, theme.id(), containers, "直播首页模板", List.of(zone("live-hero", "hero", 10), zone("live-channels", "live", 20), zone("live-events", "live", 30))));
    result.put("专题页面模板", ensureVisualTemplate(siteId, theme.id(), containers, "专题页面模板", List.of(zone("topic-hero", "hero", 10), zone("topic-content", "topic", 20), zone("topic-videos", "video-grid", 30))));
    return result;
  }
  private VisualLayoutService.ContainerInfo ensureVisualContainer(UUID siteId, String name,
      Map<String, ContentTypeEntity> types, String... typeCodes) {
    String allowed;
    try { allowed = mapper.writeValueAsString(Arrays.stream(typeCodes).map(types::get).filter(Objects::nonNull).map(ContentTypeEntity::getId).toList()); }
    catch (Exception ex) { throw new BusinessException(ErrorCodes.INTERNAL_ERROR, "视频容器配置生成失败"); }
    var existing = visualLayoutService.containers(siteId).stream().filter(c -> name.equals(c.name())).findFirst().orElse(null);
    return visualLayoutService.saveContainer(siteId, existing == null ? null : existing.id(), name, allowed, "{\"managedBy\":\"video-platform-preset\"}", "system", "视频平台预置容器");
  }

  private record VisualZone(String key, String containerKey, int order) {}
  private VisualZone zone(String key, String containerKey, int order) { return new VisualZone(key, containerKey, order); }

  private VisualLayoutService.TemplateInfo ensureVisualTemplate(UUID siteId, UUID themeId,
      Map<String, VisualLayoutService.ContainerInfo> containers, String name, List<VisualZone> zones) {
    var existing = visualLayoutService.templates(siteId, null, null).stream().filter(t -> name.equals(t.name())).findFirst().orElse(null);
    var template = visualLayoutService.saveTemplate(siteId, existing == null ? null : existing.id(), themeId, name,
        "视频平台预置可视化模板", "{\"columns\":12,\"managedBy\":\"video-platform-preset\"}", "PUBLISHED",
        "video-platform-preset", "初始化可视化模板");
    for (VisualZone spec : zones) {
      var current = template.zones().stream().filter(z -> spec.key().equals(z.zoneKey())).findFirst().orElse(null);
      template = visualLayoutService.saveZone(siteId, template.id(), current == null ? null : current.id(), spec.key(),
          containers.get(spec.containerKey()).id(), spec.order(), "{\"width\":12}", "video-platform-preset");
    }
    return template;
  }
  private record PagePreset(String typeCode, String title, String route, String templateName,
      Map<String, Object> fields, Map<String, List<ComponentPreset>> zones) {}
  private record ComponentPreset(String typeCode, String title, Map<String, Object> fields) {}

  private void seedPages(UUID siteId, Map<String, ContentTypeEntity> types,
      Map<String, VisualLayoutService.TemplateInfo> templates) {
    for (PagePreset preset : pagePresets()) {
      var type = types.get(preset.typeCode());
      var template = templates.get(preset.templateName());
      if (type == null || template == null) continue;
      var existing = contentService.search(siteId, type.getId(), List.of(), null, null).stream()
          .filter(item -> preset.route().equals(item.routePath())).findFirst().orElse(null);
      var page = existing == null
          ? contentService.create(siteId, null, null, type.getId(), preset.title(), preset.route(),
              type.getDisplayTemplate(), writeJson(preset.fields()), "video-platform-preset")
          : existing;
      var current = compositionService.get(siteId, page.id());
      if (current.id() != null || !current.zones().isEmpty()) continue;
      Map<String, List<com.powercms.cms.sitepackage.ComponentInstance>> zones = new LinkedHashMap<>();
      preset.zones().forEach((key, components) -> zones.put(key, components.stream().map(component -> {
        var componentType = types.get(component.typeCode());
        return new com.powercms.cms.sitepackage.ComponentInstance(UUID.randomUUID().toString(), com.powercms.cms.sitepackage.ComponentInstance.Kind.EMBEDDED, null, null,
            componentType.getId(), component.title(), component.fields(), null, null, Map.of(), 0);
      }).toList()));
      compositionService.save(siteId, page.id(), template.id(), zones, 0, "video-platform-preset");
    }
  }

  private List<PagePreset> pagePresets() {
    return List.of(
        pagePreset("video_home", "视频平台首页", "/", "视频首页模板", Map.of("hero_title_s", "精彩视频，尽在此刻"),
            zones("hero", component("hero_banner_component", "首页主视觉", Map.of("headline_s", "精彩视频，尽在此刻", "subtitle_s", "电影、剧集、纪录片、综艺、知识与直播")),
                "featured", component("video_grid_component", "热门推荐", Map.of("section_title_s", "热门推荐", "columns_i", 6)),
                "topics", component("topic_grid_component", "精选专题", Map.of("section_title_s", "精选专题")))),
        pagePreset("video_list_page", "电影列表", "/videos/movies", "视频列表模板", Map.of("list_type_s", "MOVIE"),
            zones("header", component("hero_banner_component", "电影频道", Map.of("headline_s", "电影", "subtitle_s", "发现值得观看的好电影")),
                "video-list", component("video_grid_component", "全部电影", Map.of("section_title_s", "全部电影", "columns_i", 6)))),
        pagePreset("video_list_page", "电视剧列表", "/videos/series", "视频列表模板", Map.of("list_type_s", "SERIES"),
            zones("header", component("hero_banner_component", "电视剧频道", Map.of("headline_s", "电视剧", "subtitle_s", "热播好剧持续更新")),
                "video-list", component("video_grid_component", "全部剧集", Map.of("section_title_s", "全部剧集", "columns_i", 6)))),
        pagePreset("video_list_page", "纪录片列表", "/videos/documentaries", "视频列表模板", Map.of("list_type_s", "DOCUMENTARY"),
            zones("header", component("hero_banner_component", "纪录片频道", Map.of("headline_s", "纪录片", "subtitle_s", "探索真实世界")),
                "video-list", component("video_grid_component", "全部纪录片", Map.of("section_title_s", "全部纪录片", "columns_i", 5)))),
        pagePreset("video_list_page", "综艺列表", "/videos/variety", "视频列表模板", Map.of("list_type_s", "VARIETY"),
            zones("header", component("hero_banner_component", "综艺频道", Map.of("headline_s", "综艺", "subtitle_s", "轻松、欢乐与陪伴")),
                "video-list", component("video_grid_component", "全部综艺", Map.of("section_title_s", "全部综艺", "columns_i", 6)))),
        pagePreset("video_detail_page", "视频详情示例", "/videos/sample", "视频详情模板", Map.of("work_ref_s", ""),
            zones("video-detail", component("video_detail_component", "视频信息", Map.of("work_ref_s", "", "show_trailer_b", true)),
                "seasons", component("season_list_component", "季列表", Map.of("work_ref_s", "")),
                "recommendations", component("video_grid_component", "相关推荐", Map.of("section_title_s", "相关推荐", "columns_i", 6)))),
        pagePreset("series_detail_page", "剧集详情示例", "/series/sample", "剧集详情模板", Map.of("work_ref_s", ""),
            zones("series-detail", component("video_detail_component", "剧集信息", Map.of("work_ref_s", "")),
                "season-list", component("season_list_component", "季列表", Map.of("work_ref_s", "")),
                "episode-list", component("episode_list_component", "分集列表", Map.of("season_ref_s", "", "page_size_i", 30)))),
        pagePreset("episode_detail_page", "分集播放示例", "/episodes/sample", "分集详情模板", Map.of("episode_ref_s", ""),
            zones("episode-player", component("video_detail_component", "播放与详情", Map.of("work_ref_s", "")),
                "episode-list", component("episode_list_component", "选集", Map.of("season_ref_s", "", "page_size_i", 30)),
                "recommendations", component("video_grid_component", "猜你喜欢", Map.of("section_title_s", "猜你喜欢", "columns_i", 6)))),
        pagePreset("live_home", "直播首页", "/live", "直播首页模板", Map.of("headline_s", "正在直播"),
            zones("live-hero", component("hero_banner_component", "直播主视觉", Map.of("headline_s", "正在直播", "subtitle_s", "实时频道与精彩活动")),
                "live-channels", component("live_grid_component", "直播频道", Map.of("section_title_s", "直播频道")),
                "live-events", component("live_grid_component", "直播活动", Map.of("section_title_s", "直播活动")))),
        pagePreset("topic", "专题示例", "/topics/featured", "专题页面模板", Map.of("summary_t", "精选视频专题"),
            zones("topic-hero", component("hero_banner_component", "专题主视觉", Map.of("headline_s", "精选专题", "subtitle_s", "围绕一个主题发现更多好内容")),
                "topic-content", component("topic_grid_component", "专题内容", Map.of("section_title_s", "专题内容")),
                "topic-videos", component("video_grid_component", "专题视频", Map.of("section_title_s", "专题视频", "columns_i", 6)))));
  }

  private PagePreset pagePreset(String type, String title, String route, String template,
      Map<String, Object> fields, Map<String, List<ComponentPreset>> zones) {
    return new PagePreset(type, title, route, template, fields, zones);
  }
  private ComponentPreset component(String type, String title, Map<String, Object> fields) {
    return new ComponentPreset(type, title, fields);
  }
  private Map<String, List<ComponentPreset>> zones(Object... entries) {
    Map<String, List<ComponentPreset>> result = new LinkedHashMap<>();
    for (int i = 0; i < entries.length; i += 2)
      result.put((String) entries[i], List.of((ComponentPreset) entries[i + 1]));
    return result;
  }
  private String writeJson(Object value) {
    try { return mapper.writeValueAsString(value); }
    catch (Exception ex) { throw new BusinessException(ErrorCodes.INTERNAL_ERROR, "视频页面预设生成失败"); }
  }
  private CreateContentTypeRequest.FieldDefDto field(FieldSpec s) {
    return new CreateContentTypeRequest.FieldDefDto(
        null,
        s.code(),
        s.name(),
        s.type(),
        s.required(),
        s.order(),
        s.group(),
        s.help(),
        null,
        false,
        s.validation(),
        s.config() == null ? null : mapper.valueToTree(s.config()),
        null,
        false,
        true,
        s.publicIndex(),
        false,
        s.sortable(),
        s.filterable(),
        false,
        false);
  }

  private record FieldSpec(
      String code,
      String name,
      String type,
      boolean required,
      int order,
      String group,
      String help,
      String validation,
      boolean publicIndex,
      boolean sortable,
      boolean filterable,
      Map<String, Object> config) {}

  private record ModelSpec(
      String code,
      String name,
      String description,
      String objectType,
      String template,
      String icon,
      List<FieldSpec> fields) {}

  private record CategorySpec(String name, String alias, int order, List<CategorySpec> children) {}

  private record RelationSpec(
      String name, String source, String target, String cardinality, String description) {}

  private FieldSpec f(String code, String name, String type, boolean required, int order, String group) {
    return new FieldSpec(code, name, type, required, order, group, null, null, true, false, true, null);
  }

  private FieldSpec e(String code, String name, int order, String... values) {
    Map<String, Object> config =
        Map.of(
            "options",
            Arrays.stream(values).map(v -> Map.of("value", v, "label", v)).toList());
    return new FieldSpec(code, name, "ENUM", false, order, "分类属性", null, null, true, false, true, config);
  }

  private List<ModelSpec> modelSpecs() {
    return List.of(
        new ModelSpec(
            "video_work",
            "视频作品",
            "电影、电视剧、纪录片、综艺、知识视频统一作品模型",
            "SHARED",
            "/templates/video-detail.ftl",
            "VideoCameraOutlined",
            List.of(
                f("summary_t", "简介", "LONG_TEXT", false, 10, "基本信息"),
                f("cover_image_m", "封面", "MEDIA_REF", true, 20, "基本信息"),
                f("poster_h_m", "横版海报", "MEDIA_REF", false, 21, "基本信息"),
                f("poster_v_m", "竖版海报", "MEDIA_REF", false, 22, "基本信息"),
                f("trailer_m", "预告片", "MEDIA_REF", false, 23, "基本信息"),
                e(
                    "video_type_s",
                    "视频类型",
                    30,
                    "MOVIE",
                    "SERIES",
                    "DOCUMENTARY",
                    "VARIETY",
                    "KNOWLEDGE",
                    "SHORT_VIDEO",
                    "ANIMATION",
                    "SPORTS"),
                f("release_date_dt", "上线日期", "DATE_TIME", false, 40, "基本信息"),
                f("year_i", "制作年份", "INTEGER", false, 41, "基本信息"),
                f("duration_i", "时长（秒）", "INTEGER", false, 50, "播放信息"),
                e("language_s", "主要语言", 51, "普通话", "粤语", "英语", "日语", "韩语", "其他"),
                e("region_s", "地区", 52, "中国大陆", "中国香港", "中国台湾", "日本", "韩国", "美国", "英国", "其他"),
                f("is_vip_b", "会员专享", "BOOLEAN", false, 90, "播放信息"),
                f("publish_at_dt", "定时发布时间", "DATE_TIME", false, 80, "发布与版权"),
                f("copyright_start_dt", "版权开始", "DATE_TIME", false, 81, "发布与版权"),
                f("copyright_end_dt", "版权结束", "DATE_TIME", false, 82, "发布与版权"))),
        new ModelSpec(
            "video_season",
            "视频季",
            "电视剧、综艺和系列纪录片的季结构",
            "SHARED",
            "/templates/video-season.ftl",
            "AppstoreOutlined",
            List.of(
                f("season_number_i", "季编号", "INTEGER", true, 10, "季信息"),
                f("summary_t", "简介", "LONG_TEXT", false, 20, "季信息"),
                f("work_ref_s", "所属作品", "CONTENT_REF", true, 30, "关联内容"),
                f("episode_count_i", "总集数", "INTEGER", false, 40, "季信息"))),
        new ModelSpec(
            "video_episode",
            "视频集",
            "电视剧、综艺、纪录片和课程的可独立发布单元",
            "SHARED",
            "/templates/video-episode.ftl",
            "PlaySquareOutlined",
            List.of(
                f("episode_number_i", "集数", "INTEGER", true, 10, "集信息"),
                f("summary_t", "简介", "LONG_TEXT", false, 20, "集信息"),
                f("season_ref_s", "所属季", "CONTENT_REF", true, 30, "关联内容"),
                f("asset_ref_s", "播放资产", "CONTENT_REF", false, 40, "关联内容"),
                f("is_free_b", "免费", "BOOLEAN", false, 50, "播放信息"),
                f("publish_at_dt", "定时发布时间", "DATE_TIME", false, 60, "发布与版权"))),
        new ModelSpec(
            "video_asset",
            "视频播放资产",
            "实际视频文件、转码版本、字幕和播放地址",
            "SHARED",
            "/templates/video-asset.ftl",
            "FileOutlined",
            List.of(
                f("playback_url_s", "播放地址", "STRING", true, 10, "播放资产"),
                e("protocol_s", "播放协议", 20, "MP4", "HLS", "DASH", "LIVE_STREAM"),
                f("resolution_s", "分辨率", "STRING", false, 30, "播放资产"),
                f("duration_i", "时长（秒）", "INTEGER", false, 40, "播放资产"),
                f("subtitle_urls_json", "字幕轨道", "JSON", false, 50, "播放资产"))),
        new ModelSpec(
            "person",
            "人物",
            "演员、导演、主持人、嘉宾、主播和讲师",
            "SHARED",
            "/templates/person.ftl",
            "UserOutlined",
            List.of(
                f("bio_t", "人物简介", "LONG_TEXT", false, 10, "基本信息"),
                f("avatar_m", "头像", "MEDIA_REF", false, 20, "基本信息"),
                e("profession_s", "职业", 30, "演员", "导演", "主持人", "嘉宾", "主播", "讲师", "其他"))),
        new ModelSpec(
            "organization",
            "制作机构",
            "制作公司、版权方、播出平台和教育机构",
            "SHARED",
            "/templates/organization.ftl",
            "BankOutlined",
            List.of(
                f("summary_t", "机构简介", "LONG_TEXT", false, 10, "基本信息"),
                f("logo_m", "机构标志", "MEDIA_REF", false, 20, "基本信息"))),
        new ModelSpec(
            "playlist",
            "播放列表",
            "可运营的视频集合和专题播放列表",
            "SHARED",
            "/templates/playlist.ftl",
            "UnorderedListOutlined",
            List.of(f("summary_t", "简介", "LONG_TEXT", false, 10, "基本信息"))),
        new ModelSpec(
            "topic",
            "专题",
            "运营专题页，聚合多部视频作品",
            "PAGE",
            "/templates/topic.ftl",
            "AppstoreOutlined",
            List.of(
                f("summary_t", "专题简介", "LONG_TEXT", false, 10, "基本信息"),
                f("cover_image_m", "封面", "MEDIA_REF", false, 20, "基本信息"))),
        new ModelSpec(
            "live_channel",
            "直播频道",
            "长期存在的直播频道和播放流配置",
            "PAGE",
            "/templates/live-channel.ftl",
            "VideoCameraOutlined",
            List.of(
                f("summary_t", "频道简介", "LONG_TEXT", false, 10, "频道信息"),
                e("channel_type_s", "频道类型", 20, "新闻", "体育", "娱乐", "音乐", "教育", "活动", "其他"),
                f("stream_url_s", "播放流地址", "STRING", true, 30, "直播配置"))),
        new ModelSpec(
            "live_event",
            "直播活动",
            "一次具体的直播活动、预约和回看",
            "PAGE",
            "/templates/live-event.ftl",
            "CalendarOutlined",
            List.of(
                f("summary_t", "活动简介", "LONG_TEXT", false, 10, "活动信息"),
                f("start_time_dt", "开始时间", "DATE_TIME", true, 20, "活动信息"),
                f("end_time_dt", "结束时间", "DATE_TIME", false, 30, "活动信息"),
                e("status_s", "直播状态", 40, "即将开始", "直播中", "已结束", "回看"),
                f("channel_ref_s", "所属频道", "CONTENT_REF", false, 50, "关联内容"),
                f("replay_ref_s", "回看作品", "CONTENT_REF", false, 60, "关联内容"))),
        new ModelSpec(
            "live_schedule",
            "直播节目单",
            "直播频道的时段排期",
            "SHARED",
            "/templates/live-schedule.ftl",
            "CalendarOutlined",
            List.of(
                f("channel_ref_s", "所属频道", "CONTENT_REF", true, 10, "节目单"),
                f("program_name_s", "节目名称", "STRING", true, 20, "节目单"),
                f("start_time_dt", "开始时间", "DATE_TIME", true, 30, "节目单"),
                f("end_time_dt", "结束时间", "DATE_TIME", true, 40, "节目单"),
                f("event_ref_s", "关联活动", "CONTENT_REF", false, 50, "节目单"))),
        new ModelSpec(
            "knowledge_course",
            "知识课程",
            "由章节和课时组成的知识内容",
            "PAGE",
            "/templates/knowledge-course.ftl",
            "ReadOutlined",
            List.of(
                f("summary_t", "课程简介", "LONG_TEXT", false, 10, "课程信息"),
                f("lecturer_ref_s", "讲师", "CONTENT_REF", false, 20, "关联内容"),
                e("level_s", "难度", 30, "入门", "进阶", "高级"),
                f("target_audience_s", "适用人群", "STRING", false, 40, "课程信息"))),
        new ModelSpec(
            "knowledge_chapter",
            "课程章节",
            "知识课程的章节结构",
            "SHARED",
            "/templates/knowledge-chapter.ftl",
            "AppstoreOutlined",
            List.of(
                f("course_ref_s", "所属课程", "CONTENT_REF", true, 10, "章节"),
                f("chapter_number_i", "章节编号", "INTEGER", true, 20, "章节"),
                f("summary_t", "章节简介", "LONG_TEXT", false, 30, "章节"))),
        new ModelSpec(
            "knowledge_lesson",
            "课程课时",
            "章节下的可播放课时",
            "SHARED",
            "/templates/knowledge-lesson.ftl",
            "PlayCircleOutlined",
            List.of(
                f("chapter_ref_s", "所属章节", "CONTENT_REF", true, 10, "课时"),
                f("lesson_number_i", "课时编号", "INTEGER", true, 20, "课时"),
                f("asset_ref_s", "播放资产", "CONTENT_REF", false, 30, "课时"),
                f("duration_i", "时长（秒）", "INTEGER", false, 40, "课时"))),
        new ModelSpec(
            "video_detail_page", "视频详情页", "视频作品的可路由详情页面", "PAGE",
            "/templates/video-detail-page.ftl", "VideoCameraOutlined",
            List.of(f("work_ref_s", "视频作品", "CONTENT_REF", true, 10, "页面数据"))),
        new ModelSpec(
            "series_detail_page", "剧集详情页", "电视剧、综艺和系列内容详情页面", "PAGE",
            "/templates/series-detail-page.ftl", "AppstoreOutlined",
            List.of(f("work_ref_s", "视频作品", "CONTENT_REF", true, 10, "页面数据"))),
        new ModelSpec(
            "episode_detail_page", "分集详情页", "单集播放与详情页面", "PAGE",
            "/templates/episode-detail-page.ftl", "PlaySquareOutlined",
            List.of(f("episode_ref_s", "视频集", "CONTENT_REF", true, 10, "页面数据"))),
        new ModelSpec(
            "live_home", "直播首页", "直播频道和直播活动聚合首页", "PAGE",
            "/templates/live-home.ftl", "VideoCameraOutlined",
            List.of(f("headline_s", "页面标题", "STRING", false, 10, "页面信息"))),
        new ModelSpec(
            "hero_banner_component", "视频轮播组件", "首页和专题页顶部轮播", "COMPONENT", null, "PictureOutlined",
            List.of(f("headline_s", "主标题", "STRING", true, 10, "组件内容"), f("subtitle_s", "副标题", "STRING", false, 20, "组件内容"), f("background_m", "背景图", "MEDIA_REF", false, 30, "组件内容"), f("target_url_s", "跳转地址", "STRING", false, 40, "组件内容"))),
        new ModelSpec(
            "video_grid_component", "视频网格组件", "按作品、播放列表或分类展示视频网格", "COMPONENT", null, "AppstoreOutlined",
            List.of(f("section_title_s", "区块标题", "STRING", true, 10, "组件内容"), f("video_refs_ss", "视频作品", "CONTENT_REF", false, 20, "组件内容"), f("columns_i", "列数", "INTEGER", false, 30, "显示设置"))),
        new ModelSpec(
            "video_detail_component", "视频详情组件", "视频作品海报、简介、人物和播放入口", "COMPONENT", null, "VideoCameraOutlined",
            List.of(f("work_ref_s", "视频作品", "CONTENT_REF", true, 10, "组件内容"), f("show_trailer_b", "显示预告片", "BOOLEAN", false, 20, "显示设置"))),
        new ModelSpec(
            "season_list_component", "视频季列表组件", "显示作品关联的视频季", "COMPONENT", null, "UnorderedListOutlined",
            List.of(f("work_ref_s", "视频作品", "CONTENT_REF", true, 10, "组件内容"))),
        new ModelSpec(
            "episode_list_component", "分集列表组件", "显示指定视频季的分集列表", "COMPONENT", null, "UnorderedListOutlined",
            List.of(f("season_ref_s", "视频季", "CONTENT_REF", true, 10, "组件内容"), f("page_size_i", "每页集数", "INTEGER", false, 20, "显示设置"))),
        new ModelSpec(
            "live_grid_component", "直播频道组件", "直播频道、节目单和直播活动聚合", "COMPONENT", null, "VideoCameraOutlined",
            List.of(f("section_title_s", "区块标题", "STRING", false, 10, "组件内容"), f("channel_refs_ss", "直播频道", "CONTENT_REF", false, 20, "组件内容"))),
        new ModelSpec(
            "topic_grid_component", "专题网格组件", "运营专题卡片网格", "COMPONENT", null, "AppstoreOutlined",
            List.of(f("section_title_s", "区块标题", "STRING", false, 10, "组件内容"), f("topic_refs_ss", "专题", "CONTENT_REF", false, 20, "组件内容"))),
        new ModelSpec(
            "video_home",
            "视频首页",
            "视频平台首页",
            "PAGE",
            "/templates/video-home.ftl",
            "HomeOutlined",
            List.of(f("hero_title_s", "主标题", "STRING", false, 10, "首页"))),
        new ModelSpec(
            "video_list_page",
            "视频列表页",
            "按分类或模型聚合的视频列表页",
            "PAGE",
            "/templates/video-list.ftl",
            "UnorderedListOutlined",
            List.of(
                e(
                    "list_type_s",
                    "列表类型",
                    10,
                    "MOVIE",
                    "SERIES",
                    "DOCUMENTARY",
                    "VARIETY",
                    "KNOWLEDGE",
                    "LIVE"))));
  }

  private List<CategorySpec> categorySpecs() {
    return List.of(
        new CategorySpec(
            "视频",
            "video",
            10,
            List.of(
                leaf("电影", "movie", 10),
                leaf("电视剧", "series", 20),
                leaf("纪录片", "documentary", 30),
                leaf("综艺", "variety", 40),
                leaf("知识", "knowledge", 50),
                leaf("动漫", "animation", 60),
                leaf("体育", "sports", 70))),
        new CategorySpec(
            "直播",
            "live",
            20,
            List.of(
                leaf("新闻直播", "news", 10),
                leaf("体育直播", "sports-live", 20),
                leaf("娱乐直播", "entertainment-live", 30),
                leaf("知识直播", "knowledge-live", 40),
                leaf("活动直播", "event-live", 50))),
        new CategorySpec(
            "题材",
            "genre",
            30,
            List.of(
                leaf("剧情", "drama", 10),
                leaf("喜剧", "comedy", 20),
                leaf("动作", "action", 30),
                leaf("科幻", "sci-fi", 40),
                leaf("悬疑", "mystery", 50),
                leaf("历史", "history", 60),
                leaf("自然", "nature", 70),
                leaf("教育", "education", 80))),
        new CategorySpec(
            "地区",
            "region",
            40,
            List.of(
                leaf("中国大陆", "china-mainland", 10),
                leaf("中国香港", "hong-kong", 20),
                leaf("中国台湾", "taiwan", 30),
                leaf("日本", "japan", 40),
                leaf("韩国", "korea", 50),
                leaf("美国", "usa", 60),
                leaf("英国", "uk", 70))),
        new CategorySpec(
            "年代",
            "decade",
            45,
            List.of(
                leaf("2020年代", "2020s", 10),
                leaf("2010年代", "2010s", 20),
                leaf("2000年代", "2000s", 30),
                leaf("1990年代", "1990s", 40),
                leaf("1980年代", "1980s", 50),
                leaf("更早", "earlier", 60))),
        new CategorySpec(
            "人群",
            "audience",
            48,
            List.of(
                leaf("少儿", "children", 10),
                leaf("青少年", "teen", 20),
                leaf("成人", "adult", 30),
                leaf("家庭", "family", 40),
                leaf("学生", "student", 50),
                leaf("专业人士", "pro", 60))),
        new CategorySpec(
            "运营标签",
            "operation",
            50,
            List.of(
                leaf("热门", "hot", 10),
                leaf("新上线", "new", 20),
                leaf("独家", "exclusive", 30),
                leaf("会员专享", "vip", 40),
                leaf("编辑推荐", "editor-pick", 50))));
  }

  private CategorySpec leaf(String name, String alias, int order) {
    return new CategorySpec(name, alias, order, List.of());
  }

  private List<RelationSpec> relationSpecs() {
    return List.of(
        new RelationSpec("video_work_person", "video_work", "person", "MANY_TO_MANY", "作品与人物"),
        new RelationSpec("video_work_organization", "video_work", "organization", "MANY_TO_MANY", "作品与制作机构"),
        new RelationSpec("video_work_season", "video_work", "video_season", "ONE_TO_MANY", "作品与季"),
        new RelationSpec("video_season_episode", "video_season", "video_episode", "ONE_TO_MANY", "季与集"),
        new RelationSpec("video_episode_asset", "video_episode", "video_asset", "MANY_TO_ONE", "集与播放资产"),
        new RelationSpec("playlist_video_work", "playlist", "video_work", "MANY_TO_MANY", "播放列表与作品"),
        new RelationSpec("topic_video_work", "topic", "video_work", "MANY_TO_MANY", "专题与作品"),
        new RelationSpec("course_chapter", "knowledge_course", "knowledge_chapter", "ONE_TO_MANY", "课程与章节"),
        new RelationSpec("chapter_lesson", "knowledge_chapter", "knowledge_lesson", "ONE_TO_MANY", "章节与课时"),
        new RelationSpec("live_channel_event", "live_channel", "live_event", "ONE_TO_MANY", "直播频道与活动"),
        new RelationSpec("live_channel_schedule", "live_channel", "live_schedule", "ONE_TO_MANY", "直播频道与节目单"));
  }

  private boolean isManagedVisualTemplate(UUID projectId, String path) {
    if (!Set.of("templates/video-home.ftl", "templates/video-list.ftl", "templates/video-detail-page.ftl",
        "templates/series-detail-page.ftl", "templates/episode-detail-page.ftl", "templates/live-home.ftl",
        "templates/topic.ftl").contains(path)) return false;
    String current = templateService.readFileContent(projectId, path);
    if (current.contains("powercms:video-visual-preset")) return true;
    return switch (path) {
      case "templates/video-home.ftl" -> current.equals(page("视频首页", "<h1>${content.hero_title_s!content.title}</h1>"));
      case "templates/video-list.ftl" -> current.equals(page("视频列表", "<h1>${content.title}</h1><p>类型：${content.list_type_s!}</p>"));
      case "templates/topic.ftl" -> current.equals(page("专题", "<h1>${content.title}</h1><p>${content.summary_t!}</p>"));
      default -> false;
    };
  }
  private Map<String, String> templateFiles() {
    Map<String, String> files = new LinkedHashMap<>();
    files.put("templates/video-home.ftl", visualPage("视频首页", List.of("hero", "featured", "topics")));
    files.put("templates/video-detail-page.ftl", visualPage("视频详情", List.of("video-detail", "seasons", "recommendations")));
    files.put("templates/series-detail-page.ftl", visualPage("剧集详情", List.of("series-detail", "season-list", "episode-list")));
    files.put("templates/episode-detail-page.ftl", visualPage("分集详情", List.of("episode-player", "episode-list", "recommendations")));
    files.put("templates/live-home.ftl", visualPage("直播首页", List.of("live-hero", "live-channels", "live-events")));
    files.put("templates/video-list.ftl", visualPage("视频列表", List.of("header", "video-list")));
    files.put(
        "templates/video-detail.ftl",
        page(
            "视频详情",
            "<h1>${content.title}</h1><p>${content.summary_t!}</p><p>类型：${content.video_type_s!} · ${content.region_s!}</p>"));
    files.put("templates/video-season.ftl", page("视频季", "<h1>${content.title}</h1><p>第 ${content.season_number_i!} 季</p>"));
    files.put(
        "templates/video-episode.ftl",
        page("视频集", "<h1>${content.title}</h1><p>第 ${content.episode_number_i!} 集</p>"));
    files.put(
        "templates/video-asset.ftl",
        page(
            "播放资产",
            "<h1>${content.title}</h1><video controls src=\"${content.playback_url_s!}\" style=\"max-width:100%\"></video>"));
    files.put("templates/person.ftl", page("人物", "<h1>${content.title}</h1><p>${content.bio_t!}</p>"));
    files.put("templates/organization.ftl", page("制作机构", "<h1>${content.title}</h1><p>${content.summary_t!}</p>"));
    files.put("templates/playlist.ftl", page("播放列表", "<h1>${content.title}</h1><p>${content.summary_t!}</p>"));
    files.put("templates/topic.ftl", visualPage("专题", List.of("topic-hero", "topic-content", "topic-videos")));
    files.put(
        "templates/live-channel.ftl",
        page("直播频道", "<h1>${content.title}</h1><p>播放地址：${content.stream_url_s!}</p>"));
    files.put(
        "templates/live-event.ftl",
        page("直播活动", "<h1>${content.title}</h1><p>${content.start_time_dt!} - ${content.end_time_dt!}</p>"));
    files.put("templates/live-schedule.ftl", page("节目单", "<h1>${content.program_name_s!content.title}</h1>"));
    files.put("templates/knowledge-course.ftl", page("知识课程", "<h1>${content.title}</h1><p>${content.summary_t!}</p>"));
    files.put("templates/knowledge-chapter.ftl", page("课程章节", "<h1>${content.title}</h1>"));
    files.put("templates/knowledge-lesson.ftl", page("课程课时", "<h1>${content.title}</h1>"));
    return files;
  }

  private String visualPage(String title, List<String> zoneKeys) {
    StringBuilder body = new StringBuilder("<main class=\"powercms-video-page\"><h1>${content.title!\"")
        .append(title.replace("\"", "&quot;")).append("\"}</h1>");
    for (String key : zoneKeys) {
      body.append("<section class=\"drop-zone drop-zone-").append(key).append("\" data-drop-zone=\"").append(key).append("\">\n")
          .append("<#list zones[\"").append(key).append("\"]![] as component><article class=\"visual-component\" data-component-type=\"${component.componentTypeId!}\"><h2>${component.title!}</h2><#if component.fieldValues?has_content><dl><#list component.fieldValues as fieldKey, fieldValue><dt>${fieldKey}</dt><dd>${fieldValue?string}</dd></#list></dl></#if></article></#list>\n")
          .append("</section>\n");
    }
    return "<#-- powercms:video-visual-preset -->\n" + page(title, body.append("</main>").toString());
  }
  private String page(String title, String body) {
    return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"/><title>"
        + title
        + "</title></head><body>"
        + body
        + "</body></html>\n";
  }
}
