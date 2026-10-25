package com.powercms.cms.delivery.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.identity.permission.ObjectPermissionService;
import com.powercms.cms.identity.permission.ObjectType;
import com.powercms.cms.identity.permission.entity.ObjectAclEntity;
import com.powercms.cms.identity.repo.RoleRepository;
import com.powercms.cms.sitefolder.SiteFolderService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开读取站点栏目及其会员组授权，供资源库前台展示。
 * 只返回带会员等级的角色（会员组）在栏目上的对象权利，不返回个别用户授权明细；
 * <p>当前登录用户的视角权利（含站点/栏目上对个别用户的授权，与后台 {@code hasRight} 同口径）
 * 逐栏目回显在 {@code viewerRights}，供前台把「个别用户授权」与「会员组授权」一致呈现。
 * 响应随会话变化，禁用一切缓存。
 */
@RestController
@RequestMapping("/api/v1/delivery/sites/{siteId}/folders")
class FolderGrantDeliveryController {

  private static final List<String> RIGHT_ORDER =
      List.of("view", "edit", "publish", "manage", "add-children", "download");

  private final SiteFolderService folders;
  private final ObjectPermissionService permissions;
  private final RoleRepository roles;
  private final ObjectMapper mapper;

  FolderGrantDeliveryController(
      SiteFolderService folders,
      ObjectPermissionService permissions,
      RoleRepository roles,
      ObjectMapper mapper) {
    this.folders = folders;
    this.permissions = permissions;
    this.roles = roles;
    this.mapper = mapper;
  }

  public record MemberGrant(String roleId, String code, String name, List<String> rights) {}

  public record FolderGrant(
      String id,
      String title,
      String name,
      String path,
      String parentId,
      boolean inherit,
      List<MemberGrant> grants,
      boolean anonymousView,
      List<String> viewerRights) {}

  @GetMapping
  org.springframework.http.ResponseEntity<List<FolderGrant>> list(@PathVariable UUID siteId) {
    UUID viewerId = viewerId();
    List<FolderGrant> out = new ArrayList<>();
    flatten(folders.tree(siteId), viewerId, out);
    // viewerRights 随登录会话变化：private/no-store，防止任何缓存把甲的视角给到乙
    return org.springframework.http.ResponseEntity.ok()
        .cacheControl(org.springframework.http.CacheControl.noStore())
        .body(List.copyOf(out));
  }

  /** 当前会话的登录用户；匿名访客返回 null（匿名可见性由 anonymousView 表达）。 */
  private static UUID viewerId() {
    var auth =
        org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
    if (auth != null
        && auth.getPrincipal() instanceof com.powercms.cms.identity.AuthenticatedUserPrincipal principal) {
      return principal.getUserId();
    }
    return null;
  }

  private void flatten(List<SiteFolderService.Info> nodes, UUID viewerId, List<FolderGrant> out) {
    for (SiteFolderService.Info node : nodes) {
      ObjectPermissionService.EffectiveView view =
          permissions.view(ObjectType.FOLDER, node.id());
      out.add(
          new FolderGrant(
              node.id().toString(),
              node.title(),
              node.name(),
              node.path(),
              node.parentId() == null ? "" : node.parentId().toString(),
              view.inherit(),
              memberGrants(view.effectiveAcl()),
              permissions.anonymousCanView(ObjectType.FOLDER, node.id()),
              viewerId == null
                  ? List.of()
                  : ordered(permissions.rightsFor(viewerId, ObjectType.FOLDER, node.id()))));
      if (node.children() != null && !node.children().isEmpty()) {
        flatten(node.children(), viewerId, out);
      }
    }
  }

  private List<MemberGrant> memberGrants(List<ObjectAclEntity> rows) {
    List<MemberGrant> grants = new ArrayList<>();
    for (ObjectAclEntity row : rows) {
      if (!ObjectAclEntity.PRINCIPAL_ROLE.equals(row.getPrincipalType())) continue;
      var role = roles.findById(row.getPrincipalId()).orElse(null);
      if (role == null || role.getTierLevel() == null) continue;
      grants.add(
          new MemberGrant(
              role.getId().toString(), role.getCode(), role.getName(), ordered(parse(row.getPermissions()))));
    }
    return List.copyOf(grants);
  }

  private static List<String> ordered(java.util.Collection<String> rights) {
    List<String> out = new ArrayList<>();
    for (String right : RIGHT_ORDER) {
      if (rights.contains(right)) out.add(right);
    }
    return List.copyOf(out);
  }

  private List<String> parse(String json) {
    try {
      List<String> values = mapper.readValue(json, new TypeReference<List<String>>() {});
      return values == null ? List.of() : values;
    } catch (Exception ex) {
      return List.of();
    }
  }
}
