package com.powercms.cms.delivery.web;

import com.powercms.cms.delivery.ContentViewService;
import com.powercms.cms.delivery.controller.SiteScopedContentQueryService;
import com.powercms.cms.identity.AuthenticatedUserPrincipal;
import com.powercms.cms.identity.permission.ObjectType;
import com.powercms.cms.identity.permission.ViewerAccessService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开投放查询 API（类似 clist）：按内容模型、排序字段与条数拉取已发布内容。
 *
 * <p>orderBy 支持：viewCount（默认）、updatedAt、createdAt、title、sortOrder
 */
@RestController
@RequestMapping("/api/v1/delivery/sites/{siteId}/contents")
class ContentDeliveryQueryController {
  private final SiteScopedContentQueryService contentQuery;
  private final ContentViewService viewService;
  private final ViewerAccessService viewerAccess;

  ContentDeliveryQueryController(
      SiteScopedContentQueryService contentQuery,
      ContentViewService viewService,
      ViewerAccessService viewerAccess) {
    this.contentQuery = contentQuery;
    this.viewService = viewService;
    this.viewerAccess = viewerAccess;
  }

  @GetMapping("/query")
  List<Map<String, Object>> query(
      @PathVariable UUID siteId,
      @RequestParam String contentType,
      @RequestParam(defaultValue = "10") int limit,
      @RequestParam(defaultValue = "viewCount") String orderBy,
      @RequestParam(defaultValue = "PUBLISHED") String status,
      @RequestParam(defaultValue = "false") boolean forViewer) {
    Predicate<UUID> visible = null;
    if (forViewer) {
      UUID viewer = currentViewerId();
      visible = id -> viewerAccess.visible(viewer, ObjectType.CONTENT, id);
    }
    return contentQuery.queryByType(siteId, contentType, status, limit, orderBy, true, visible);
  }

  private static UUID currentViewerId() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof AuthenticatedUserPrincipal principal) {
      return principal.getUserId();
    }
    return null;
  }

  @PostMapping("/{contentId}/views")
  Map<String, Object> recordView(@PathVariable UUID siteId, @PathVariable UUID contentId) {
    long viewCount = viewService.recordView(siteId, contentId);
    return Map.of("id", contentId.toString(), "viewCount", viewCount);
  }
}
