package com.powercms.cms.delivery.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.identity.AuthenticatedUserPrincipal;
import com.powercms.cms.identity.entity.RoleEntity;
import com.powercms.cms.identity.permission.ObjectPermissionService;
import com.powercms.cms.identity.permission.ObjectType;
import com.powercms.cms.identity.permission.PermissionableResolver;
import com.powercms.cms.identity.permission.entity.ObjectAclEntity;
import com.powercms.cms.identity.repo.RoleRepository;
import com.powercms.cms.sitefolder.SiteFolderService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 交付栏目授权接口：登录用户的视角权利（viewerRights）随会话回显，
 * 让前台把站点/栏目上对个别用户的授权与会员组授权一致呈现；匿名为空。
 */
class FolderGrantDeliveryControllerTest {

  private final UUID siteId = UUID.randomUUID();
  private final UUID folderId = UUID.randomUUID();
  private final UUID viewerId = UUID.randomUUID();

  private SiteFolderService folders;
  private ObjectPermissionService permissions;
  private FolderGrantDeliveryController controller;

  @BeforeEach
  void setUp() {
    folders = mock(SiteFolderService.class);
    permissions = mock(ObjectPermissionService.class);
    RoleRepository roles = mock(RoleRepository.class);
    controller = new FolderGrantDeliveryController(folders, permissions, roles, new ObjectMapper());

    // 栏目树：单个栏目
    when(folders.tree(siteId)).thenReturn(List.of(new SiteFolderService.Info(
        folderId, siteId, null, "品牌资产", "brand", "/brand", 1, true, null, null,
        Instant.now(), Instant.now(), List.of())));

    // 栏目继承站点 ACL；站点上有一条会员组行 + 一条个别用户行（仅后台可见的授权明细）
    RoleEntity member = RoleEntity.create(UUID.randomUUID(), "vip", "VIP 会员组", null, false);
    member.setTierLevel(2);
    UUID memberRole = member.getId();
    when(roles.findById(memberRole)).thenReturn(Optional.of(member));
    when(permissions.view(ObjectType.FOLDER, folderId)).thenReturn(
        new ObjectPermissionService.EffectiveView(true,
            new PermissionableResolver.Ref(ObjectType.SITE, siteId), List.of(), List.of(
                ObjectAclEntity.of(siteId, ObjectType.SITE, siteId,
                    ObjectAclEntity.PRINCIPAL_ROLE, memberRole, "[\"view\"]", false, viewerId),
                ObjectAclEntity.of(siteId, ObjectType.SITE, siteId,
                    ObjectAclEntity.PRINCIPAL_USER, viewerId,
                    "[\"view\",\"edit\",\"add-children\",\"download\"]", false, viewerId))));
    when(permissions.anonymousCanView(ObjectType.FOLDER, folderId)).thenReturn(false);
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  private void authenticate(UUID userId) {
    var principal = new AuthenticatedUserPrincipal(userId,
        User.withUsername("admin").password("x").roles("USER").build());
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
  }

  @Test
  void loggedInViewerSeesItsOwnRightsPerFolder() {
    authenticate(viewerId);
    when(permissions.rightsFor(viewerId, ObjectType.FOLDER, folderId))
        .thenReturn(Set.of("view", "edit", "add-children", "download"));

    var response = controller.list(siteId);

    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    // 响应随会话变化：必须禁止缓存
    assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    var grant = response.getBody().get(0);
    assertThat(grant.viewerRights())
        .containsExactly("view", "edit", "add-children", "download");
    // 会员组授权明细照旧返回（前台仍按会员组 codes 过滤），个别用户明细不外泄
    assertThat(grant.grants()).hasSize(1);
    assertThat(grant.grants().get(0).code()).isEqualTo("vip");
  }

  @Test
  void anonymousViewerGetsEmptyRights() {
    when(permissions.rightsFor(null, ObjectType.FOLDER, folderId)).thenReturn(Set.of());

    var grant = controller.list(siteId).getBody().get(0);

    assertThat(grant.viewerRights()).isEmpty();
    assertThat(grant.anonymousView()).isFalse();
  }

  @Test
  void unknownSessionNeverQueriesViewerRights() {
    // SecurityContext 无登录用户 → 不调用 rightsFor（避免匿名请求做无谓评估）
    var grant = controller.list(siteId).getBody().get(0);
    assertThat(grant.viewerRights()).isEmpty();
    org.mockito.Mockito.verify(permissions, org.mockito.Mockito.never())
        .rightsFor(any(), any(), any());
  }
}
