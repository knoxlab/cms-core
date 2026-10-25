package com.powercms.cms.identity.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.audit.AuditService;
import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.identity.BuiltInRoleCatalog;
import com.powercms.cms.identity.SiteCapabilities;
import com.powercms.cms.identity.SiteCapabilityEvaluator;
import com.powercms.cms.identity.entity.RoleEntity;
import com.powercms.cms.identity.entity.UserEntity;
import com.powercms.cms.identity.entity.UserRoleEntity;
import com.powercms.cms.identity.permission.entity.ObjectAclEntity;
import com.powercms.cms.identity.permission.repo.ObjectAclRepository;
import com.powercms.cms.identity.repo.RoleRepository;
import com.powercms.cms.identity.repo.UserRepository;
import com.powercms.cms.identity.repo.UserRoleRepository;
import com.powercms.cms.site.entity.SiteEntity;
import com.powercms.cms.site.repo.SiteRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentCaptor.forClass;
import org.mockito.ArgumentCaptor;

/** P1-A：对象 ACL 继承、打破、恢复、owner、匿名（AC-IAM-022~024/025/030）。 */
class ObjectPermissionServiceTest {

    private ObjectAclRepository aclRepo;
    private PermissionableResolver resolver;
    private SiteCapabilityEvaluator capabilities;
    private UserRoleRepository userRoleRepo;
    private UserRepository userRepo;
    private SiteRepository siteRepo;
    private ContentItemRepository contentRepo;
    private RoleRepository roleRepo;
    private ObjectPermissionService service;

    private final UUID site = UUID.randomUUID();
    private final UUID org = UUID.randomUUID();
    private final UUID content = UUID.randomUUID();
    private final UUID folder = UUID.randomUUID();
    private final UUID roleR1 = UUID.randomUUID();
    private final UUID userU = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        aclRepo = mock(ObjectAclRepository.class);
        resolver = mock(PermissionableResolver.class);
        capabilities = mock(SiteCapabilityEvaluator.class);
        userRoleRepo = mock(UserRoleRepository.class);
        userRepo = mock(UserRepository.class);
        siteRepo = mock(SiteRepository.class);
        contentRepo = mock(ContentItemRepository.class);
        roleRepo = mock(RoleRepository.class);
        AuditService audit = mock(AuditService.class);
        service = new ObjectPermissionService(aclRepo, resolver, capabilities, userRoleRepo,
            userRepo, siteRepo, contentRepo, roleRepo, audit, new ObjectMapper());

        // 内容链：CONTENT → FOLDER → SITE → ORG
        when(resolver.parent(ObjectType.CONTENT, content)).thenReturn(
            new PermissionableResolver.Ref(ObjectType.FOLDER, folder));
        when(resolver.parent(ObjectType.FOLDER, folder)).thenReturn(
            new PermissionableResolver.Ref(ObjectType.SITE, site));
        when(resolver.parent(ObjectType.SITE, site)).thenReturn(
            new PermissionableResolver.Ref(ObjectType.ORG, org));
        when(resolver.parent(ObjectType.ORG, org)).thenReturn(null);

        when(capabilities.isSystemAdmin(any())).thenReturn(false);
        // 默认站点能力全无 → 打破后必须依赖 ACL
        when(capabilities.capabilities(any(), any())).thenReturn(Set.of());
    }

    // ---- AC-IAM-022/023：打破继承后按 ACL 判定 ----

    @Test
    void brokenContentGrantsOnlyAclRole() {
        when(aclRepo.inheritFlags(ObjectType.CONTENT, content)).thenReturn(List.of(false));
        when(aclRepo.findByObjectTypeAndObjectId(ObjectType.CONTENT, content)).thenReturn(
            List.of(ObjectAclEntity.of(site, ObjectType.CONTENT, content,
                ObjectAclEntity.PRINCIPAL_ROLE, roleR1, "[\"view\",\"edit\"]", false, userU)));
        when(userRoleRepo.findByUserId(userU)).thenReturn(List.of(UserRoleEntity.of(userU, roleR1, site)));

        assertThat(service.hasRight(userU, ObjectType.CONTENT, content, "view")).isTrue();
        assertThat(service.hasRight(userU, ObjectType.CONTENT, content, "edit")).isTrue();
        assertThat(service.hasRight(userU, ObjectType.CONTENT, content, "publish")).isFalse();
    }

    @Test
    void otherEditorWithoutAclIsDenied() {
        UUID otherEditor = UUID.randomUUID();
        when(aclRepo.inheritFlags(ObjectType.CONTENT, content)).thenReturn(List.of(false));
        when(aclRepo.findByObjectTypeAndObjectId(ObjectType.CONTENT, content)).thenReturn(
            List.of(ObjectAclEntity.of(site, ObjectType.CONTENT, content,
                ObjectAclEntity.PRINCIPAL_ROLE, roleR1, "[\"view\"]", false, userU)));
        when(userRoleRepo.findByUserId(otherEditor)).thenReturn(List.of());

        assertThat(service.hasRight(otherEditor, ObjectType.CONTENT, content, "view")).isFalse();
    }

    // ---- 继承：内容继承栏目打破的 ACL（AC-IAM-022） ----

    @Test
    void inheritsFromBrokenFolder() {
        when(aclRepo.inheritFlags(ObjectType.CONTENT, content)).thenReturn(List.of());
        when(aclRepo.inheritFlags(ObjectType.FOLDER, folder)).thenReturn(List.of(false));
        when(aclRepo.findByObjectTypeAndObjectId(ObjectType.FOLDER, folder)).thenReturn(
            List.of(ObjectAclEntity.of(site, ObjectType.FOLDER, folder,
                ObjectAclEntity.PRINCIPAL_ROLE, roleR1, "[\"edit\"]", false, userU)));
        when(userRoleRepo.findByUserId(userU)).thenReturn(List.of(UserRoleEntity.of(userU, roleR1, site)));

        assertThat(service.hasRight(userU, ObjectType.CONTENT, content, "edit")).isTrue();
        assertThat(service.hasRight(userU, ObjectType.CONTENT, content, "view")).isFalse();
        assertThat(service.chainBroken(ObjectType.CONTENT, content)).isTrue();
    }

    // ---- 未打破继承：回退站点能力（P0 语义） ----

    @Test
    void fallsBackToSiteCapabilityWhenNothingBroken() {
        when(aclRepo.inheritFlags(ObjectType.CONTENT, content)).thenReturn(List.of());
        when(capabilities.capabilities(userU, site)).thenReturn(Set.of(SiteCapabilities.READ));
        assertThat(service.hasRight(userU, ObjectType.CONTENT, content, "view")).isTrue();
        assertThat(service.hasRight(userU, ObjectType.CONTENT, content, "edit")).isFalse();
    }

    // ---- AC-IAM-024：个别用户权限与角色权限并集 ----

    @Test
    void userAndRolePermissionsUnion() {
        when(aclRepo.inheritFlags(ObjectType.CONTENT, content)).thenReturn(List.of(false));
        when(aclRepo.findByObjectTypeAndObjectId(ObjectType.CONTENT, content)).thenReturn(List.of(
            ObjectAclEntity.of(site, ObjectType.CONTENT, content,
                ObjectAclEntity.PRINCIPAL_ROLE, roleR1, "[\"view\"]", false, userU),
            ObjectAclEntity.of(site, ObjectType.CONTENT, content,
                ObjectAclEntity.PRINCIPAL_USER, userU, "[\"edit\"]", false, userU)));
        when(userRoleRepo.findByUserId(userU)).thenReturn(List.of(UserRoleEntity.of(userU, roleR1, site)));

        assertThat(service.hasRight(userU, ObjectType.CONTENT, content, "view")).isTrue();
        assertThat(service.hasRight(userU, ObjectType.CONTENT, content, "edit")).isTrue();
    }

    // ---- 4.6.4 owner：内容创建者默认 view+edit ----

    @Test
    void contentOwnerGetsViewAndEditWhenBroken() {
        when(aclRepo.inheritFlags(ObjectType.CONTENT, content)).thenReturn(List.of(false));
        when(aclRepo.findByObjectTypeAndObjectId(ObjectType.CONTENT, content)).thenReturn(List.of());
        UserEntity owner = new UserEntity();
        owner.setId(userU);
        owner.setUsername("alice");
        when(userRepo.findById(userU)).thenReturn(Optional.of(owner));
        ContentItemEntity item = new ContentItemEntity();
        item.setId(content);
        item.setCreatedBy("alice");
        when(contentRepo.findById(content)).thenReturn(Optional.of(item));

        assertThat(service.hasRight(userU, ObjectType.CONTENT, content, "view")).isTrue();
        assertThat(service.hasRight(userU, ObjectType.CONTENT, content, "edit")).isTrue();
        assertThat(service.hasRight(userU, ObjectType.CONTENT, content, "publish")).isFalse();
    }

    // ---- 保存/打破/恢复 ----

    @Test
    void saveWithoutBreakOnInheritedObjectIsRejected() {
        when(aclRepo.inheritFlags(ObjectType.CONTENT, content)).thenReturn(List.of());
        assertThatThrownBy(() -> service.savePermissions(ObjectType.CONTENT, content,
            List.of(new ObjectPermissionService.SavePermissionEntry(
                ObjectAclEntity.PRINCIPAL_ROLE, roleR1, List.of("view"))), false, userU))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ErrorCodes.PERMISSION_INHERIT_CONFLICT);
    }

    @Test
    void saveWithBreakWritesAclWithInheritFalse() {
        when(aclRepo.inheritFlags(ObjectType.CONTENT, content)).thenReturn(List.of());
        service.savePermissions(ObjectType.CONTENT, content,
            List.of(new ObjectPermissionService.SavePermissionEntry(
                ObjectAclEntity.PRINCIPAL_ROLE, roleR1, List.of("view"))), true, userU);
        verify(aclRepo).deleteByObjectTypeAndObjectId(ObjectType.CONTENT, content);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ObjectAclEntity>> captor = forClass(List.class);
        verify(aclRepo).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).isInherit()).isFalse();
        assertThat(captor.getValue().get(0).getObjectType()).isEqualTo(ObjectType.CONTENT);
        assertThat(captor.getValue().get(0).getPermissions()).contains("view");
    }

    /** 打破继承：快照已含该主体时按主体合并，条目覆盖快照权利，不产生唯一约束冲突。 */
    @Test
    void breakWithDuplicatePrincipalMergesSnapshotAndEntry() {
        when(aclRepo.inheritFlags(ObjectType.CONTENT, content)).thenReturn(List.of());
        when(aclRepo.inheritFlags(ObjectType.FOLDER, folder)).thenReturn(List.of(false));
        when(aclRepo.findByObjectTypeAndObjectId(ObjectType.FOLDER, folder)).thenReturn(
            List.of(ObjectAclEntity.of(site, ObjectType.FOLDER, folder,
                ObjectAclEntity.PRINCIPAL_ROLE, roleR1, "[\"view\"]", false, userU)));

        service.savePermissions(ObjectType.CONTENT, content,
            List.of(new ObjectPermissionService.SavePermissionEntry(
                ObjectAclEntity.PRINCIPAL_ROLE, roleR1, List.of("view", "edit"))), true, userU);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ObjectAclEntity>> captor = forClass(List.class);
        verify(aclRepo).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getPrincipalType()).isEqualTo(ObjectAclEntity.PRINCIPAL_ROLE);
        assertThat(captor.getValue().get(0).getPrincipalId()).isEqualTo(roleR1);
        assertThat(captor.getValue().get(0).getPermissions()).contains("view", "edit");
    }

    /** 打破继承：空权利条目将对应主体从快照中移除（显式取消继承权利）。 */
    @Test
    void breakWithEmptyEntryRemovesSnapshotPrincipal() {
        when(aclRepo.inheritFlags(ObjectType.CONTENT, content)).thenReturn(List.of());
        when(aclRepo.inheritFlags(ObjectType.FOLDER, folder)).thenReturn(List.of(false));
        when(aclRepo.findByObjectTypeAndObjectId(ObjectType.FOLDER, folder)).thenReturn(List.of(
            ObjectAclEntity.of(site, ObjectType.FOLDER, folder,
                ObjectAclEntity.PRINCIPAL_ROLE, roleR1, "[\"view\"]", false, userU),
            ObjectAclEntity.of(site, ObjectType.FOLDER, folder,
                ObjectAclEntity.PRINCIPAL_USER, userU, "[\"edit\"]", false, userU)));

        service.savePermissions(ObjectType.CONTENT, content,
            List.of(new ObjectPermissionService.SavePermissionEntry(
                ObjectAclEntity.PRINCIPAL_ROLE, roleR1, List.of())), true, userU);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ObjectAclEntity>> captor = forClass(List.class);
        verify(aclRepo).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getPrincipalType()).isEqualTo(ObjectAclEntity.PRINCIPAL_USER);
        assertThat(captor.getValue().get(0).getPrincipalId()).isEqualTo(userU);
    }

    /** 已打破对象整表重写：空权利条目忽略，仅写入有效条目。 */
    @Test
    void saveOnBrokenObjectSkipsEmptyEntry() {
        when(aclRepo.inheritFlags(ObjectType.CONTENT, content)).thenReturn(List.of(false));
        service.savePermissions(ObjectType.CONTENT, content,
            List.of(
                new ObjectPermissionService.SavePermissionEntry(
                    ObjectAclEntity.PRINCIPAL_ROLE, roleR1, List.of()),
                new ObjectPermissionService.SavePermissionEntry(
                    ObjectAclEntity.PRINCIPAL_USER, userU, List.of("edit"))), false, userU);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ObjectAclEntity>> captor = forClass(List.class);
        verify(aclRepo).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getPrincipalType()).isEqualTo(ObjectAclEntity.PRINCIPAL_USER);
        assertThat(captor.getValue().get(0).getPrincipalId()).isEqualTo(userU);
        var order = inOrder(aclRepo);
        order.verify(aclRepo).deleteByObjectTypeAndObjectId(ObjectType.CONTENT, content);
        order.verify(aclRepo).flush();
        order.verify(aclRepo).saveAll(any());
    }

    @Test
    void restoreInheritanceDeletesDirectAcl() {
        when(aclRepo.inheritFlags(ObjectType.CONTENT, content)).thenReturn(List.of(false));
        service.restoreInheritance(ObjectType.CONTENT, content, userU);
        verify(aclRepo).deleteByObjectTypeAndObjectId(ObjectType.CONTENT, content);
    }

    @Test
    void restoreInheritanceOnUnbrokenObjectIsRejected() {
        when(aclRepo.inheritFlags(ObjectType.CONTENT, content)).thenReturn(List.of());
        assertThatThrownBy(() -> service.restoreInheritance(ObjectType.CONTENT, content, userU))
            .isInstanceOf(BusinessException.class);
        verify(aclRepo, never()).deleteByObjectTypeAndObjectId(anyString(), any());
    }

    /** 栏目打破继承后只看栏目自己的授权，不再并入站点上的「添加子项」。 */
    @Test
    void brokenFolderIgnoresSiteAddChildren() {
        when(aclRepo.inheritFlags(ObjectType.FOLDER, folder)).thenReturn(List.of(false));
        when(aclRepo.inheritFlags(ObjectType.SITE, site)).thenReturn(List.of(false));
        when(aclRepo.findByObjectTypeAndObjectId(ObjectType.FOLDER, folder)).thenReturn(
            List.of(ObjectAclEntity.of(site, ObjectType.FOLDER, folder,
                ObjectAclEntity.PRINCIPAL_ROLE, roleR1, "[\"view\",\"download\"]", false, userU)));
        when(aclRepo.findByObjectTypeAndObjectId(ObjectType.SITE, site)).thenReturn(
            List.of(ObjectAclEntity.of(site, ObjectType.SITE, site,
                ObjectAclEntity.PRINCIPAL_ROLE, roleR1, "[\"view\",\"add-children\"]", false, userU)));
        when(userRoleRepo.findByUserId(userU)).thenReturn(List.of(UserRoleEntity.of(userU, roleR1, site)));

        assertThat(service.hasRight(userU, ObjectType.FOLDER, folder, "add-children")).isFalse();
        assertThat(service.hasRight(userU, ObjectType.FOLDER, folder, "view")).isTrue();

        ObjectPermissionService.EffectiveView view = service.view(ObjectType.FOLDER, folder);
        assertThat(view.effectiveAcl()).hasSize(1);
        assertThat(view.effectiveAcl().get(0).getPermissions()).doesNotContain("add-children");
    }

    // ---- AC-030：匿名 ----

    @Test
    void anonymousDeniedWhenBrokenAclOnlyGrantsRole() {
        when(aclRepo.inheritFlags(ObjectType.CONTENT, content)).thenReturn(List.of(false));
        when(aclRepo.findByObjectTypeAndObjectId(ObjectType.CONTENT, content)).thenReturn(
            List.of(ObjectAclEntity.of(site, ObjectType.CONTENT, content,
                ObjectAclEntity.PRINCIPAL_ROLE, roleR1, "[\"view\"]", false, userU)));
        assertThat(service.hasAnonymousRight(ObjectType.CONTENT, content, "view")).isFalse();
    }

    @Test
    void anonymousAllowedWhenNothingBroken() {
        when(aclRepo.inheritFlags(ObjectType.CONTENT, content)).thenReturn(List.of());
        assertThat(service.hasAnonymousRight(ObjectType.CONTENT, content, "view")).isTrue();
    }

    @Test
    void anonymousCanViewRequiresExplicitGrant() {
        when(aclRepo.inheritFlags(anyString(), any())).thenReturn(List.of());
        when(aclRepo.findByObjectTypeAndObjectId(anyString(), any())).thenReturn(List.of());
        assertThat(service.anonymousCanView(ObjectType.SITE, site)).isFalse();
    }

    @Test
    void anonymousCanViewFollowsBrokenAncestor() {
        RoleEntity anonymous = RoleEntity.create(org, BuiltInRoleCatalog.ANONYMOUS, "匿名",
            null, true, "ORG", null);
        when(roleRepo.findByOrganizationIdAndCode(org, BuiltInRoleCatalog.ANONYMOUS))
            .thenReturn(Optional.of(anonymous));
        when(siteRepo.findAll()).thenReturn(List.of(siteOf()));
        when(aclRepo.inheritFlags(ObjectType.FOLDER, folder)).thenReturn(List.of());
        when(aclRepo.inheritFlags(ObjectType.SITE, site)).thenReturn(List.of(false));
        when(aclRepo.inheritFlags(ObjectType.ORG, org)).thenReturn(List.of());
        when(aclRepo.findByObjectTypeAndObjectId(ObjectType.FOLDER, folder)).thenReturn(List.of());
        when(aclRepo.findByObjectTypeAndObjectId(ObjectType.SITE, site)).thenReturn(
            List.of(ObjectAclEntity.of(site, ObjectType.SITE, site,
                ObjectAclEntity.PRINCIPAL_ROLE, anonymous.getId(), "[\"view\"]", false, userU)));

        assertThat(service.anonymousCanView(ObjectType.SITE, site)).isTrue();
        assertThat(service.anonymousCanView(ObjectType.FOLDER, folder)).isTrue();
    }

    @Test
    void brokenFolderDoesNotInheritSiteAnonymousView() {
        RoleEntity anonymous = RoleEntity.create(org, BuiltInRoleCatalog.ANONYMOUS, "匿名",
            null, true, "ORG", null);
        when(roleRepo.findByOrganizationIdAndCode(org, BuiltInRoleCatalog.ANONYMOUS))
            .thenReturn(Optional.of(anonymous));
        when(siteRepo.findAll()).thenReturn(List.of(siteOf()));
        when(aclRepo.inheritFlags(ObjectType.FOLDER, folder)).thenReturn(List.of(false));
        when(aclRepo.inheritFlags(ObjectType.SITE, site)).thenReturn(List.of(false));
        when(aclRepo.findByObjectTypeAndObjectId(ObjectType.FOLDER, folder)).thenReturn(
            List.of(ObjectAclEntity.of(site, ObjectType.FOLDER, folder,
                ObjectAclEntity.PRINCIPAL_ROLE, roleR1, "[\"view\"]", false, userU)));
        when(aclRepo.findByObjectTypeAndObjectId(ObjectType.SITE, site)).thenReturn(
            List.of(ObjectAclEntity.of(site, ObjectType.SITE, site,
                ObjectAclEntity.PRINCIPAL_ROLE, anonymous.getId(), "[\"view\"]", false, userU)));

        assertThat(service.anonymousCanView(ObjectType.FOLDER, folder)).isFalse();
        assertThat(service.anonymousCanView(ObjectType.SITE, site)).isTrue();
    }

    @Test
    void anonymousAllowedWhenAclGrantsAnonymousRole() {
        RoleEntity anonymous = RoleEntity.create(org, BuiltInRoleCatalog.ANONYMOUS, "匿名",
            null, true, "ORG", null);
        when(aclRepo.inheritFlags(ObjectType.CONTENT, content)).thenReturn(List.of(false));
        when(aclRepo.findByObjectTypeAndObjectId(ObjectType.CONTENT, content)).thenReturn(
            List.of(ObjectAclEntity.of(site, ObjectType.CONTENT, content,
                ObjectAclEntity.PRINCIPAL_ROLE, anonymous.getId(), "[\"view\"]", false, userU)));
        when(roleRepo.findByOrganizationIdAndCode(org, BuiltInRoleCatalog.ANONYMOUS))
            .thenReturn(Optional.of(anonymous));
        when(siteRepo.findAll()).thenReturn(List.of(siteOf()));

        assertThat(service.hasAnonymousRight(ObjectType.CONTENT, content, "view")).isTrue();
    }

    // ---- rightsFor：一次性取回用户视角权利（交付侧个别用户授权回显） ----

    /** 打破继承站点上只给个别用户授权时，栏目视角权利继承该站点 ACL（与 hasRight 同口径）。 */
    @Test
    void rightsForInheritsBrokenSiteUserGrant() {
        when(aclRepo.inheritFlags(ObjectType.FOLDER, folder)).thenReturn(List.of());
        when(aclRepo.inheritFlags(ObjectType.SITE, site)).thenReturn(List.of(false));
        when(aclRepo.findByObjectTypeAndObjectId(ObjectType.FOLDER, folder)).thenReturn(List.of());
        when(aclRepo.findByObjectTypeAndObjectId(ObjectType.SITE, site)).thenReturn(
            List.of(ObjectAclEntity.of(site, ObjectType.SITE, site,
                ObjectAclEntity.PRINCIPAL_USER, userU, "[\"view\",\"edit\",\"add-children\",\"download\"]",
                false, userU)));
        when(userRoleRepo.findByUserId(userU)).thenReturn(List.of());

        var rights = service.rightsFor(userU, ObjectType.FOLDER, folder);
        assertThat(rights).containsExactlyInAnyOrder("view", "edit", "add-children", "download");
        // 与逐权利判定完全一致（一致性口径）
        for (String right : rights) {
            assertThat(service.hasRight(userU, ObjectType.FOLDER, folder, right)).isTrue();
        }
        assertThat(service.hasRight(userU, ObjectType.FOLDER, folder, "manage")).isFalse();
    }

    /** 用户行与角色行取并集。 */
    @Test
    void rightsForUnionsUserAndRoleRows() {
        when(aclRepo.inheritFlags(ObjectType.FOLDER, folder)).thenReturn(List.of(false));
        when(aclRepo.findByObjectTypeAndObjectId(ObjectType.FOLDER, folder)).thenReturn(List.of(
            ObjectAclEntity.of(site, ObjectType.FOLDER, folder,
                ObjectAclEntity.PRINCIPAL_USER, userU, "[\"edit\"]", false, userU),
            ObjectAclEntity.of(site, ObjectType.FOLDER, folder,
                ObjectAclEntity.PRINCIPAL_ROLE, roleR1, "[\"view\"]", false, userU)));
        when(userRoleRepo.findByUserId(userU)).thenReturn(List.of(UserRoleEntity.of(userU, roleR1, site)));

        assertThat(service.rightsFor(userU, ObjectType.FOLDER, folder))
            .containsExactlyInAnyOrder("view", "edit");
    }

    /** 整链未打破继承：回退站点能力映射（无 download 映射）。 */
    @Test
    void rightsForFallsBackToSiteCapabilities() {
        when(aclRepo.inheritFlags(ObjectType.FOLDER, folder)).thenReturn(List.of());
        when(capabilities.capabilities(userU, site))
            .thenReturn(Set.of(SiteCapabilities.READ, SiteCapabilities.WRITE, SiteCapabilities.DEPLOY));

        assertThat(service.rightsFor(userU, ObjectType.FOLDER, folder))
            .containsExactlyInAnyOrder("view", "edit", "add-children", "publish");
    }

    /** system-admin 直通全部权利；匿名不走本方法（返回空集）。 */
    @Test
    void rightsForSystemAdminAllAndAnonymousNone() {
        when(capabilities.isSystemAdmin(userU)).thenReturn(true);
        assertThat(service.rightsFor(userU, ObjectType.FOLDER, folder))
            .containsExactlyInAnyOrderElementsOf(ObjectType.RIGHTS);
        assertThat(service.rightsFor(null, ObjectType.FOLDER, folder)).isEmpty();
    }

    /** owner 内容默认 view+edit 并入视角权利。 */
    @Test
    void rightsForIncludesOwnerRights() {
        when(aclRepo.inheritFlags(ObjectType.CONTENT, content)).thenReturn(List.of(false));
        when(aclRepo.findByObjectTypeAndObjectId(ObjectType.CONTENT, content)).thenReturn(List.of());
        UserEntity owner = new UserEntity();
        owner.setId(userU);
        owner.setUsername("alice");
        when(userRepo.findById(userU)).thenReturn(Optional.of(owner));
        ContentItemEntity item = new ContentItemEntity();
        item.setId(content);
        item.setCreatedBy("alice");
        when(contentRepo.findById(content)).thenReturn(Optional.of(item));

        assertThat(service.rightsFor(userU, ObjectType.CONTENT, content))
            .containsExactlyInAnyOrder("view", "edit");
    }

    private SiteEntity siteOf() {
        SiteEntity e = new SiteEntity();
        e.setId(site);
        e.setOrganizationId(org);
        return e;
    }
}
