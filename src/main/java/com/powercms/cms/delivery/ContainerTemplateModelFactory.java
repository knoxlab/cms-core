package com.powercms.cms.delivery;

import com.powercms.cms.delivery.controller.SiteScopedContentQueryService;
import com.powercms.cms.sitepackage.entity.SiteContainerEntity;
import freemarker.template.TemplateMethodModelEx;
import freemarker.template.TemplateModelException;
import freemarker.template.utility.DeepUnwrap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Builds the FreeMarker model for container pre/post/zero templates. */
@Component
public class ContainerTemplateModelFactory {
  private final SiteScopedContentQueryService contentQuery;

  ContainerTemplateModelFactory(SiteScopedContentQueryService contentQuery) {
    this.contentQuery = contentQuery;
  }

  public Map<String, Object> create(
      UUID siteId, UUID pageId, SiteContainerEntity container, Map<String, Object> config, boolean preview) {
    Map<String, Object> model = new LinkedHashMap<>();
    model.put("siteId", siteId.toString());
    if (pageId != null) model.put("pageId", pageId.toString());
    model.put(
        "container",
        Map.of(
            "id", container.getId().toString(),
            "name", container.getName() == null ? "" : container.getName(),
            "config", config == null ? Map.of() : config));
    model.put("contentQuery", new ContentQueryMethod(siteId));
    preloadConfiguredQuery(siteId, config, model);
    return model;
  }

  @SuppressWarnings("unchecked")
  private void preloadConfiguredQuery(UUID siteId, Map<String, Object> config, Map<String, Object> model) {
    if (config == null) return;
    Object raw = config.get("query");
    if (!(raw instanceof Map<?, ?> query)) return;
    String typeCode = text(query.get("contentType"));
    if (typeCode == null) return;
    int limit = intValue(query.get("limit"), 10);
    String orderBy = text(query.get("orderBy"));
    if (orderBy == null) orderBy = "viewCount";
    String status = text(query.get("status"));
    if (status == null) status = "PUBLISHED";
    String as = text(query.get("as"));
    if (as == null || as.isBlank()) as = "items";
    model.put(
        as,
        contentQuery.queryByType(siteId, typeCode, status, limit, orderBy, !"false".equalsIgnoreCase(String.valueOf(query.get("publishedOnly")))));
  }

  private final class ContentQueryMethod implements TemplateMethodModelEx {
    private final UUID siteId;

    ContentQueryMethod(UUID siteId) {
      this.siteId = siteId;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public Object exec(List arguments) throws TemplateModelException {
      if (arguments == null || arguments.isEmpty()) {
        throw new TemplateModelException("contentQuery requires contentType code");
      }
      String typeCode = unwrapArg(arguments.get(0));
      if (typeCode.isBlank()) throw new TemplateModelException("contentType code is required");
      int limit = 10;
      if (arguments.size() > 1) {
        limit = Integer.parseInt(unwrapArg(arguments.get(1)));
      }
      String orderBy = "viewCount";
      if (arguments.size() > 2) {
        orderBy = unwrapArg(arguments.get(2));
      }
      String status = "PUBLISHED";
      if (arguments.size() > 3) {
        status = unwrapArg(arguments.get(3));
      }
      return contentQuery.queryByType(siteId, typeCode, status, limit, orderBy, true);
    }

    private static String unwrapArg(Object raw) throws TemplateModelException {
      if (raw instanceof freemarker.template.TemplateModel model) {
        return String.valueOf(DeepUnwrap.unwrap(model)).trim();
      }
      return String.valueOf(raw).trim();
    }
  }

  private static String text(Object value) {
    if (value == null) return null;
    String text = String.valueOf(value).trim();
    return text.isBlank() ? null : text;
  }

  private static int intValue(Object value, int fallback) {
    if (value instanceof Number number) return number.intValue();
    if (value == null) return fallback;
    try {
      return Integer.parseInt(String.valueOf(value).trim());
    } catch (NumberFormatException ex) {
      return fallback;
    }
  }
}
