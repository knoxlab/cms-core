package com.powercms.cms.delivery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.delivery.controller.SiteScopedContentQueryService;
import com.powercms.cms.identity.permission.ObjectPermissionService;
import com.powercms.cms.identity.permission.ObjectType;
import com.powercms.cms.sitepackage.ComponentInstance;
import com.powercms.cms.sitepackage.PageCompositionService;
import com.powercms.cms.sitepackage.TemplateService;
import com.powercms.cms.sitepackage.repo.SharedComponentRepository;
import com.powercms.cms.widget.repo.WidgetDefinitionRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ComponentRenderService {
  private static final int MAX_DEPTH = 12;
  private static final int MAX_OUTPUT = 1024 * 1024;

  private final SharedComponentRepository shared;
  private final PageCompositionService compositions;
  private final ContentItemRepository items;
  private final TemplateService templates;
  private final FreeMarkerSandbox sandbox;
  private final WidgetDefinitionRepository widgets;
  private final ObjectMapper mapper;
  private final SiteScopedContentQueryService content;
  private final ObjectPermissionService objectPermissions;
  private final com.powercms.cms.identity.permission.ViewerAccessService viewerAccess;

  ComponentRenderService(
      SharedComponentRepository s,
      PageCompositionService c,
      ContentItemRepository i,
      TemplateService t,
      FreeMarkerSandbox f,
      WidgetDefinitionRepository w,
      ObjectMapper m,
      SiteScopedContentQueryService q,
      ObjectPermissionService perms,
      com.powercms.cms.identity.permission.ViewerAccessService viewerAccess) {
    shared = s;
    compositions = c;
    items = i;
    templates = t;
    sandbox = f;
    widgets = w;
    mapper = m;
    content = q;
    objectPermissions = perms;
    this.viewerAccess = viewerAccess;
  }

  public String renderPath(UUID siteId, String path, Map<String, Object> additional, boolean preview) {
    var e = shared.findBySiteIdAndPath(siteId, path).orElseThrow(() -> missing("shared component not found"));
    return renderShared(siteId, e.getId(), additional, preview, new HashSet<>(), 0);
  }

  public String renderReference(UUID siteId, UUID pageId, String reference, UUID parent,
                                Map<String, Object> additional, boolean preview, boolean anonymousDelivery) {
    return renderReference(siteId, pageId, reference, parent, additional, preview, anonymousDelivery, null);
  }

  public String renderReference(UUID siteId, UUID pageId, String reference, UUID parent,
                                Map<String, Object> additional, boolean preview, boolean anonymousDelivery, UUID viewerUserId) {
    UUID pid = parent == null ? pageId : parent;
    items.findById(pid).filter(x -> siteId.equals(x.getSiteId())).orElseThrow(() -> missing("parent page not found"));
    try {
      UUID id = UUID.fromString(reference);
      if (shared.findById(id).filter(x -> siteId.equals(x.getSiteId())).isPresent())
        return renderShared(siteId, id, additional, preview, new HashSet<>(), 0);
    } catch (Exception ignored) { }
    var instance = compositions.zones(pid).values().stream()
        .flatMap(java.util.Collection::stream)
        .filter(x -> x.id().equals(reference))
        .findFirst().orElseThrow(() -> missing("component not found"));
    return renderInstance(siteId, pid, instance, additional, preview, new HashSet<>(), 0, anonymousDelivery, viewerUserId);
  }

  public String renderInstance(UUID siteId, UUID pageId, ComponentInstance x, Map<String, Object> additional,
                               boolean preview, Set<String> stack, int depth, boolean anonymousDelivery) {
    return renderInstance(siteId, pageId, x, additional, preview, stack, depth, anonymousDelivery, null);
  }

  public String renderInstance(UUID siteId, UUID pageId, ComponentInstance x, Map<String, Object> additional,
                               boolean preview, Set<String> stack, int depth, boolean anonymousDelivery, UUID viewerUserId) {
    if (depth > MAX_DEPTH) throw bad("component recursion limit");
    String key = pageId + ":" + x.id();
    if (!stack.add(key)) throw bad("component recursion detected");
    try {
      if (x.kind() == ComponentInstance.Kind.CONTENT)
        throw bad("content placement requires a container structure template");
      if (x.kind() == ComponentInstance.Kind.SHARED)
        return renderShared(siteId, x.sharedComponentId(), merge(x.additionalModel(), additional), preview, stack, depth + 1);
      if (x.kind() == ComponentInstance.Kind.WIDGET)
        return renderWidget(siteId, x, additional, preview, depth + 1, anonymousDelivery, viewerUserId);
      String path = x.templatePath();
      if (path == null || path.isBlank())
        return "<div class=\"component\">" + escape(x.title()) + "</div>";
      Map<String, Object> data = new LinkedHashMap<>(x.data() == null ? Map.of() : x.data());
      data.putIfAbsent("title", x.title());
      data.put("id", x.id());
      return limited(sandbox.render("component.ftl", resolve(siteId, path),
          Map.of("content", data, "contentModel", data, "additionalModel", merge(x.additionalModel(), additional)), preview));
    } finally {
      stack.remove(key);
    }
  }

  /**
   * FR-DELIVERY-PERM-001 §3.5：匿名交付下，widget 内容查询（query/byRoute）不得把受限内容输出到公开 HTML。
   * 列表过滤受限行；单条（byRoute）受限时置空。预览/编辑/Release 构建（anonymousDelivery=false）不受影响。
   */
  private String renderWidget(UUID siteId, ComponentInstance x, Map<String, Object> additional,
                              boolean preview, int depth, boolean anonymousDelivery, UUID viewerUserId) {
    if (depth > MAX_DEPTH) throw bad("widget recursion limit");
    var w = widgets.findById(x.widgetId())
        .filter(v -> siteId.equals(v.getSiteId()) && "PUBLISHED".equals(v.getStatus()))
        .orElseThrow(() -> missing("published widget required"));
    try {
      JsonNode d = mapper.readTree(w.getControllerDefinition());
      String view = d.path("view").asText();
      if (d.path("queries").isArray() && d.path("queries").size() > 10) throw bad("widget queries exceed limit");
      if (!view.startsWith("/templates/") || !view.endsWith(".ftl") || view.contains("..")) throw bad("widget view invalid");
      Map<String, Object> model = new LinkedHashMap<>();
      model.put("params", x.widgetParams() == null ? Map.of() : x.widgetParams());
      Map<String, Object> tm = d.path("templateModel").isObject()
          ? mapper.convertValue(d.path("templateModel"), Map.class)
          : new LinkedHashMap<>();
      for (JsonNode q : d.path("queries")) {
        String as = q.path("as").asText();
        String op = q.path("op").asText();
        int limit = Math.max(1, Math.min(q.path("limit").asInt(20), 50));
        Object value = "byRoute".equals(op)
            ? content.byRoute(siteId, q.path("route").asText(), q.path("status").asText("PUBLISHED"))
            : content.query(siteId, q.path("status").asText("PUBLISHED"), limit);
        tm.put(as, anonymousDelivery ? filterAnonymous(value, viewerUserId) : value);
      }
      model.put("templateModel", tm);
      model.put("additionalModel", merge(x.additionalModel(), additional));
      return limited(sandbox.render(view, resolve(siteId, view.substring(1)), model, preview));
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      throw bad("widget definition invalid");
    }
  }

  /** 匿名交付下过滤内容查询结果：列表剔除受限行，单条受限置空。 */
  private Object filterAnonymous(Object value, UUID viewerUserId) {
    if (value instanceof List<?> rows) {
      List<Map<String, Object>> out = new ArrayList<>();
      for (Object row : rows) {
        if (row instanceof Map<?, ?> m && viewable(viewerUserId, m.get("id"))) {
          @SuppressWarnings("unchecked")
          Map<String, Object> copy = (Map<String, Object>) m;
          out.add(copy);
        }
      }
      return out;
    }
    if (value instanceof Map<?, ?> single) {
      return viewable(viewerUserId, single.get("id")) ? single : Map.of();
    }
    return value;
  }

  private boolean viewable(UUID viewerUserId, Object id) {
    if (id == null) return true;
    try {
      UUID cid = UUID.fromString(String.valueOf(id));
      return viewerAccess.visible(viewerUserId, ObjectType.CONTENT, cid);
    } catch (IllegalArgumentException ex) {
      return true;
    }
  }

  private String renderShared(UUID siteId, UUID id, Map<String, Object> additional, boolean preview,
                              Set<String> stack, int depth) {
    if (depth > MAX_DEPTH) throw bad("shared recursion limit");
    String key = "shared:" + id;
    if (!stack.add(key)) throw bad("shared recursion detected");
    try {
      var e = shared.findById(id)
          .filter(x -> siteId.equals(x.getSiteId()) && !"ARCHIVED".equals(x.getStatus()))
          .orElseThrow(() -> missing("shared component unavailable"));
      if (e.getTemplatePath() == null || e.getTemplatePath().isBlank())
        return "<div class=\"component shared\">" + escape(e.getTitle()) + "</div>";
      Map<String, Object> data = parse(e.getDataJson());
      data.putIfAbsent("title", e.getTitle());
      data.put("id", id.toString());
      return limited(sandbox.render("shared.ftl", resolve(siteId, e.getTemplatePath()),
          Map.of("content", data, "contentModel", data, "additionalModel", additional == null ? Map.of() : additional), preview));
    } finally {
      stack.remove(key);
    }
  }

  private String resolve(UUID siteId, String path) {
    for (var p : templates.listProjects(siteId))
      if ("FTL".equalsIgnoreCase(p.projectType()))
        try {
          return templates.readFileContent(p.id(), path);
        } catch (BusinessException ignored) { }
    throw missing("template not found");
  }

  private Map<String, Object> parse(String raw) {
    try {
      return mapper.readValue(raw, new TypeReference<>() { });
    } catch (Exception e) {
      throw bad("component data invalid");
    }
  }

  private Map<String, Object> merge(Map<String, Object> a, Map<String, Object> b) {
    var o = new LinkedHashMap<String, Object>();
    if (a != null) o.putAll(a);
    if (b != null) o.putAll(b);
    return o;
  }

  private String limited(String h) {
    if (h.length() > MAX_OUTPUT) throw bad("component output exceeds limit");
    return h;
  }

  private String escape(String s) {
    if (s == null) return "";
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
  }

  private BusinessException missing(String m) {
    return new BusinessException(ErrorCodes.NOT_FOUND, m);
  }

  private BusinessException bad(String m) {
    return new BusinessException(ErrorCodes.TEMPLATE_RENDER_ERROR, m);
  }
}
