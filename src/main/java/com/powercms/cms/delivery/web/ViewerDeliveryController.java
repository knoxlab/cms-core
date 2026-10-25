package com.powercms.cms.delivery.web;

import com.powercms.cms.identity.permission.ObjectPermissionService;
import com.powercms.cms.identity.permission.ObjectType;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 前台是否允许匿名进入资源库。只认站点有效授权里明确的匿名「查看」，
 * 不把「整条链都未打破继承」的公开交付默认值当成已开放。
 */
@RestController
@RequestMapping("/api/v1/delivery/sites/{siteId}/viewer")
class ViewerDeliveryController {

  private final ObjectPermissionService permissions;

  ViewerDeliveryController(ObjectPermissionService permissions) {
    this.permissions = permissions;
  }

  @GetMapping
  Map<String, Boolean> viewer(@PathVariable UUID siteId) {
    return Map.of("anonymousView", permissions.anonymousCanView(ObjectType.SITE, siteId));
  }
}
