package com.powercms.cms.identity;

import com.powercms.cms.audit.AuditService;
import com.powercms.cms.identity.dto.AssignRoleRequest;
import com.powercms.cms.identity.dto.ChangePasswordRequest;
import com.powercms.cms.identity.dto.CreateRoleRequest;
import com.powercms.cms.identity.dto.CreateUserRequest;
import com.powercms.cms.identity.entity.BackendLayoutEntity;
import com.powercms.cms.identity.entity.RoleEntity;
import com.powercms.cms.identity.entity.RoleLayoutEntity;
import com.powercms.cms.identity.entity.UserEntity;
import com.powercms.cms.identity.entity.UserPasswordHistoryEntity;
import com.powercms.cms.identity.entity.UserRoleEntity;
import com.powercms.cms.identity.repo.BackendLayoutRepository;
import com.powercms.cms.identity.repo.OrganizationRepository;
import com.powercms.cms.identity.repo.RoleLayoutRepository;
import com.powercms.cms.identity.repo.RoleRepository;
import com.powercms.cms.identity.repo.SiteAuthorizationPolicyRepository;
import com.powercms.cms.identity.repo.UserPasswordHistoryRepository;
import com.powercms.cms.identity.repo.UserRepository;
import com.powercms.cms.identity.repo.UserRoleRepository;
import com.powercms.cms.site.repo.SiteRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdentityServiceImplTest {

    private final UserRepository users = mock(UserRepository.class);
    private final RoleRepository roles = mock(RoleRepository.class);
    private final UserRoleRepository userRoles = mock(UserRoleRepository.class);
    private final SiteCapabilityEvaluator capabilities = mock(SiteCapabilityEvaluator.class);
    private final SiteRepository sites = mock(SiteRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final UserPasswordHistoryRepository history = mock(UserPasswordHistoryRepository.class);
    private final UserReferenceMigrator refMigrator = mock(UserReferenceMigrator.class);
    private final BackendLayoutRepository layouts = mock(BackendLayoutRepository.class);
    private final RoleLayoutRepository roleLayouts = mock(RoleLayoutRepository.class);
    private IdentityServiceImpl service;

    @BeforeEach
    void setUp() {
        when(encoder.encode(any())).thenReturn("{bcrypt}hash");
        when(history.findByUserIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(refMigrator.referencesOf(any())).thenReturn(new UserReferenceMigrator.ReferenceSummary(0, 0));
        service = new IdentityServiceImpl(users, roles, userRoles, mock(OrganizationRepository.class),
            mock(SiteAuthorizationPolicyRepository.class), sites, layouts,
            roleLayouts, capabilities, encoder, mock(AuditService.class),
            history, mock(UserProfileService.class), new IdentityProperties(),
            refMigrator, mock(PlatformTransactionManager.class));
    }

    @Test
    void disableLastSystemAdminIsForbidden() {
        UUID org = UUID.randomUUID(), admin = UUID.randomUUID(), operator = UUID.randomUUID();
        UserEntity user = new UserEntity();
        user.setId(admin);
        user.setOrganizationId(org);
        user.setStatus(UserEntity.STATUS_ACTIVE);
        when(users.findById(admin)).thenReturn(Optional.of(user));
        when(capabilities.isSystemAdmin(admin)).thenReturn(true);
        when(userRoles.countActiveWithOrgRole(org, UserEntity.STATUS_ACTIVE, "system-admin")).thenReturn(1L);

        assertThatThrownBy(() -> service.disableUser(admin, operator))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ErrorCodes.LAST_ADMIN_FORBIDDEN);
        verify(users, never()).save(any());
    }

    @Test
    void disableUserWithReferencesRefusesWithoutReassignTo() {
        UUID userId = UUID.randomUUID(), operator = UUID.randomUUID(), org = UUID.randomUUID();
        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setOrganizationId(org);
        user.setStatus(UserEntity.STATUS_ACTIVE);
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(refMigrator.referencesOf(userId))
            .thenReturn(new UserReferenceMigrator.ReferenceSummary(3, 1));

        assertThatThrownBy(() -> service.disableUser(userId, null, operator))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ErrorCodes.USER_REFERENCE_CONFLICT);
        verify(refMigrator, never()).reassign(any(), any(), any());
        verify(users, never()).save(any());
    }

    @Test
    void disableUserWithReassignToMigratesThenDisables() {
        UUID userId = UUID.randomUUID(), operator = UUID.randomUUID(), org = UUID.randomUUID();
        UUID replacement = UUID.randomUUID();
        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setOrganizationId(org);
        user.setStatus(UserEntity.STATUS_ACTIVE);
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(refMigrator.referencesOf(userId))
            .thenReturn(new UserReferenceMigrator.ReferenceSummary(2, 1));

        service.disableUser(userId, replacement, operator);

        verify(refMigrator).reassign(userId, replacement, operator);
        assertThat(user.getStatus()).isEqualTo(UserEntity.STATUS_DISABLED);
    }

    @Test
    void disableUserWithoutReferencesSkipsMigration() {
        UUID userId = UUID.randomUUID(), operator = UUID.randomUUID(), org = UUID.randomUUID();
        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setOrganizationId(org);
        user.setStatus(UserEntity.STATUS_ACTIVE);
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(refMigrator.referencesOf(userId))
            .thenReturn(new UserReferenceMigrator.ReferenceSummary(0, 0));

        service.disableUser(userId, UUID.randomUUID(), operator);

        verify(refMigrator, never()).reassign(any(), any(), any());
        assertThat(user.getStatus()).isEqualTo(UserEntity.STATUS_DISABLED);
    }

    @Test
    void cannotAssignSiteRoleWithoutSiteId() {
        UUID userId = UUID.randomUUID(), operator = UUID.randomUUID(), org = UUID.randomUUID();
        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setOrganizationId(org);
        RoleEntity editor = RoleEntity.create(org, "editor", "编辑", null, true, "SITE", null);
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(roles.findByOrganizationIdAndCode(org, "editor")).thenReturn(Optional.of(editor));
        when(capabilities.isSystemAdmin(operator)).thenReturn(true);

        assertThatThrownBy(() -> service.assignRole(userId, new AssignRoleRequest(null, "editor", null), operator))
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ErrorCodes.ROLE_SCOPE_INVALID);
    }

    @Test
    void siteAdminCannotAssignOrgRole() {
        UUID userId = UUID.randomUUID(), operator = UUID.randomUUID(), org = UUID.randomUUID(), site = UUID.randomUUID();
        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setOrganizationId(org);
        RoleEntity sys = RoleEntity.create(org, "system-admin", "系统管理员", null, true, "ORG", null);
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(roles.findByOrganizationIdAndCode(org, "system-admin")).thenReturn(Optional.of(sys));
        when(capabilities.isSystemAdmin(operator)).thenReturn(false);
        when(capabilities.has(operator, site, SiteCapabilities.ADMIN)).thenReturn(true);

        assertThatThrownBy(() -> service.assignRole(userId, new AssignRoleRequest(null, "system-admin", null), operator))
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ErrorCodes.FORBIDDEN);
    }

    @Test
    void weakPasswordRejected() {
        when(users.existsByUsername("editor1")).thenReturn(false);
        assertThatThrownBy(() -> service.createUser(UUID.randomUUID(),
            new CreateUserRequest("editor1", "password", "编辑", null)))
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ErrorCodes.VALIDATION_FAILED);
    }

    @Test
    void rejectsPasswordFromRecentHistory() {
        UUID userId = UUID.randomUUID();
        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setPasswordHash("{bcrypt}current");
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(capabilities.isSystemAdmin(userId)).thenReturn(true);
        UserPasswordHistoryEntity old = UserPasswordHistoryEntity.of(userId, "{bcrypt}old");
        when(history.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(old));
        when(encoder.matches("OldPass1!", "{bcrypt}old")).thenReturn(true);

        assertThatThrownBy(() -> service.changePassword(userId, new ChangePasswordRequest(null, "OldPass1!"), userId))
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ErrorCodes.VALIDATION_FAILED);
        verify(users, never()).save(any());
    }

    @Test
    void externallyManagedUserCannotChangePassword() {
        UUID userId = UUID.randomUUID();
        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setExternallyManaged(true);
        user.setPasswordHash("{bcrypt}current");
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(capabilities.isSystemAdmin(userId)).thenReturn(true);

        assertThatThrownBy(() -> service.changePassword(userId, new ChangePasswordRequest(null, "NewPass1!"), userId))
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ErrorCodes.FORBIDDEN);
    }

    @Test
    void frontendUserCannotBeGrantedBackendRole() {
        UUID userId = UUID.randomUUID(), operator = UUID.randomUUID(), org = UUID.randomUUID();
        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setOrganizationId(org);
        user.setKind(UserEntity.KIND_FRONTEND);
        RoleEntity backendRole = RoleEntity.create(org, BuiltInRoleCatalog.BACK_END_USER, "后台登录用户",
            null, true, "ORG", null);
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(roles.findByOrganizationIdAndCode(org, BuiltInRoleCatalog.BACK_END_USER))
            .thenReturn(Optional.of(backendRole));
        when(capabilities.isSystemAdmin(operator)).thenReturn(true);

        assertThatThrownBy(() -> service.assignRole(userId,
            new AssignRoleRequest(null, BuiltInRoleCatalog.BACK_END_USER, null), operator))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ErrorCodes.BACKEND_ROLE_FORBIDDEN);
    }

    @Test
    void frontendUserCannotBeGrantedOrgAdminRole() {
        UUID userId = UUID.randomUUID(), operator = UUID.randomUUID(), org = UUID.randomUUID();
        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setOrganizationId(org);
        user.setKind(UserEntity.KIND_FRONTEND);
        RoleEntity sys = RoleEntity.create(org, "system-admin", "系统管理员", null, true, "ORG", null);
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(roles.findByOrganizationIdAndCode(org, "system-admin")).thenReturn(Optional.of(sys));
        when(capabilities.isSystemAdmin(operator)).thenReturn(true);

        assertThatThrownBy(() -> service.assignRole(userId,
            new AssignRoleRequest(null, "system-admin", null), operator))
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ErrorCodes.BACKEND_ROLE_FORBIDDEN);
    }

    @Test
    void frontendUserCannotBeGrantedBackEndUserChannelRole() {
        UUID userId = UUID.randomUUID(), operator = UUID.randomUUID(), org = UUID.randomUUID();
        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setOrganizationId(org);
        user.setKind(UserEntity.KIND_FRONTEND);
        RoleEntity be = RoleEntity.create(org, "back-end-user", "后台登录用户", null, true, "ORG", null);
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(roles.findByOrganizationIdAndCode(org, "back-end-user")).thenReturn(Optional.of(be));
        when(capabilities.isSystemAdmin(operator)).thenReturn(true);

        assertThatThrownBy(() -> service.assignRole(userId,
            new AssignRoleRequest(null, "back-end-user", null), operator))
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ErrorCodes.BACKEND_ROLE_FORBIDDEN);
        verify(userRoles, never()).save(any(UserRoleEntity.class));
    }

    @Test
    void backendUserCanBeGrantedBusinessSiteRole() {
        UUID userId = UUID.randomUUID(), operator = UUID.randomUUID(), org = UUID.randomUUID(), site = UUID.randomUUID();
        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setOrganizationId(org);
        user.setKind(UserEntity.KIND_BACKEND);
        RoleEntity editor = RoleEntity.create(org, "editor", "编辑", null, true, "SITE", null);
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(roles.findByOrganizationIdAndCode(org, "editor")).thenReturn(Optional.of(editor));
        when(capabilities.isSystemAdmin(operator)).thenReturn(true);
        when(sites.existsById(site)).thenReturn(true);
        when(userRoles.findBinding(userId, editor.getId(), site)).thenReturn(Optional.empty());

        service.assignRole(userId, new AssignRoleRequest(null, "editor", site), operator);

        verify(userRoles).save(any(UserRoleEntity.class));
    }

    @Test
    void createFrontendUserRequiresHomeSite() {
        when(users.existsByUsername("member1")).thenReturn(false);
        assertThatThrownBy(() -> service.createUser(UUID.randomUUID(),
            new CreateUserRequest("member1", "Passw0rd!", "会员", null,
                UserEntity.KIND_FRONTEND, null)))
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ErrorCodes.VALIDATION_FAILED);
    }

    @Test
    void createBackendUserGrantsBothChannelRoles() {
        UUID org = UUID.randomUUID();
        when(users.existsByUsername("editor1")).thenReturn(false);
        when(users.save(any())).thenAnswer(inv -> {
            UserEntity u = inv.getArgument(0);
            if (u.getId() == null) u.setId(UUID.randomUUID());
            return u;
        });
        when(roles.findByOrganizationIdAndCode(any(), any())).thenReturn(Optional.of(
            RoleEntity.create(org, "front-end-user", "前台登录用户", null, true, "ORG", null)));
        when(userRoles.findBinding(any(), any(), any())).thenReturn(Optional.empty());

        service.createUser(org, new CreateUserRequest("editor1", "Passw0rd!", "编辑", null));

        verify(userRoles, times(2)).save(any(UserRoleEntity.class));
    }

    @Test
    void createFrontendUserGrantsOnlyFrontendRole() {
        UUID org = UUID.randomUUID(), site = UUID.randomUUID();
        when(users.existsByUsername("member1")).thenReturn(false);
        when(users.save(any())).thenAnswer(inv -> {
            UserEntity u = inv.getArgument(0);
            if (u.getId() == null) u.setId(UUID.randomUUID());
            return u;
        });
        when(roles.findByOrganizationIdAndCode(any(), any())).thenReturn(Optional.of(
            RoleEntity.create(org, "front-end-user", "前台登录用户", null, true, "ORG", null)));
        when(userRoles.findBinding(any(), any(), any())).thenReturn(Optional.empty());
        when(sites.existsById(site)).thenReturn(true);

        service.createUser(org, new CreateUserRequest("member1", "Passw0rd!", "会员", null,
            UserEntity.KIND_FRONTEND, site));

        verify(userRoles).save(any(UserRoleEntity.class));
    }

    @Test
    void listUsersExcludesFrontendMembers() {
        UUID org = UUID.randomUUID();
        UserEntity backend = new UserEntity();
        backend.setId(UUID.randomUUID());
        backend.setOrganizationId(org);
        backend.setKind(UserEntity.KIND_BACKEND);
        UserEntity frontend = new UserEntity();
        frontend.setId(UUID.randomUUID());
        frontend.setOrganizationId(org);
        frontend.setKind(UserEntity.KIND_FRONTEND);
        when(users.findAll()).thenReturn(List.of(backend, frontend));

        assertThat(service.listUsers(org)).hasSize(1);
        assertThat(service.listUsers(org).get(0).id()).isEqualTo(backend.getId());
    }

    @Test
    void backendAccessRequiresRoleAndLayout() {
        UUID userId = UUID.randomUUID(), org = UUID.randomUUID();
        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setOrganizationId(org);
        user.setKind(UserEntity.KIND_BACKEND);
        when(users.findById(userId)).thenReturn(Optional.of(user));

        when(capabilities.organizationRoleCodes(userId)).thenReturn(Set.of());
        assertThat(service.hasBackendRole(userId)).isFalse();

        when(capabilities.organizationRoleCodes(userId)).thenReturn(Set.of(BuiltInRoleCatalog.BACK_END_USER));
        assertThat(service.hasBackendRole(userId)).isTrue();
        // 无布局 → 无后台访问资格
        assertThat(service.hasBackendAccess(userId)).isFalse();
    }

    // ---- DEF-11-17：布局绑定保存在同一 flush 中先 INSERT 后 DELETE，必须显式 flush ----

    @Test
    void updateRoleLayoutsFlushesPendingDeletesBeforeReinserting() {
        UUID org = UUID.randomUUID();
        RoleEntity viewer = RoleEntity.create(org, "viewer", "只读用户", null, false, "SITE", null);
        UUID layoutId = UUID.randomUUID();
        // 角色当前已绑定 content，再次提交含 content 的集合即为「有交集」场景
        RoleLayoutEntity bound = RoleLayoutEntity.of(viewer.getId(), layoutId);
        when(roles.findById(viewer.getId())).thenReturn(Optional.of(viewer));
        when(roleLayouts.findByRoleId(viewer.getId())).thenReturn(List.of(bound));
        when(layouts.findByCode("content")).thenReturn(Optional.of(layoutWithId(layoutId, "content")));
        when(layouts.findByCode("media"))
            .thenReturn(Optional.of(layoutWithId(UUID.randomUUID(), "media")));

        service.updateRoleLayouts(viewer.getId(), List.of("content", "media"));

        // 若缺少 flush()，删除语句会排在插入之后并触发唯一约束冲突（DEF-11-17）
        InOrder ordered = inOrder(roleLayouts);
        ordered.verify(roleLayouts).deleteAll(List.of(bound));
        ordered.verify(roleLayouts).flush();
        ordered.verify(roleLayouts, times(2)).save(any(RoleLayoutEntity.class));
    }

    @Test
    void updateRoleLayoutsDeduplicatesRepeatedCodes() {
        UUID org = UUID.randomUUID();
        RoleEntity role = RoleEntity.create(org, "viewer", "只读用户", null, false, "SITE", null);
        UUID layoutId = UUID.randomUUID();
        when(roles.findById(role.getId())).thenReturn(Optional.of(role));
        when(roleLayouts.findByRoleId(role.getId())).thenReturn(List.of());
        when(layouts.findByCode("content")).thenReturn(Optional.of(layoutWithId(layoutId, "content")));

        service.updateRoleLayouts(role.getId(), List.of("content", "content", " "));

        verify(roleLayouts, times(1)).save(any(RoleLayoutEntity.class));
        verify(roleLayouts, never()).deleteAll(any());
    }

    @Test
    void updateRoleLayoutsRejectsUnknownLayoutCodes() {
        UUID org = UUID.randomUUID();
        RoleEntity role = RoleEntity.create(org, "viewer", "只读用户", null, false, "SITE", null);
        when(roles.findById(role.getId())).thenReturn(Optional.of(role));

        // DEF-11-19：历史行为是静默丢弃（200 但 layouts=[]），改为显式 400 并列出非法编码
        assertThatThrownBy(() -> service.updateRoleLayouts(role.getId(), List.of("dashboard")))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ErrorCodes.VALIDATION_FAILED);
        assertThatThrownBy(() -> service.updateRoleLayouts(role.getId(), List.of("dashboard")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("dashboard")
            .hasMessageContaining("workflow");
        // 校验先于任何写入：不得先清空既有绑定再失败
        verify(roleLayouts, never()).deleteAll(any());
        verify(roleLayouts, never()).save(any(RoleLayoutEntity.class));
    }

    @Test
    void createRoleRejectsUnknownLayoutCodes() {
        UUID org = UUID.randomUUID();
        when(roles.findByOrganizationIdAndCode(org, "viewer")).thenReturn(Optional.empty());
        when(roles.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> service.createRole(org, new CreateRoleRequest(
            "viewer", "只读用户", null, List.of("read"), List.of("dashboard"), null)))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ErrorCodes.VALIDATION_FAILED);
        verify(roleLayouts, never()).save(any(RoleLayoutEntity.class));
    }

    // ---- DEF-11-18：删除角色前必须清理布局绑定（外键无级联，否则 409） ----

    @Test
    void deleteRoleClearsLayoutBindingsBeforeRemovingRole() {
        UUID org = UUID.randomUUID();
        RoleEntity role = RoleEntity.create(org, "viewer", "只读用户", null, false, "SITE", null);
        when(roles.findById(role.getId())).thenReturn(Optional.of(role));
        when(userRoles.findByRoleId(role.getId())).thenReturn(List.of());

        service.deleteRole(role.getId());

        InOrder ordered = inOrder(roleLayouts, roles);
        ordered.verify(roleLayouts).deleteByRoleId(role.getId());
        ordered.verify(roleLayouts).flush();
        ordered.verify(roles).delete(role);
    }

    // ---- TC-M02-009：角色仍被用户引用时删除必须被阻断并列出引用用户 ----

    @Test
    void deleteRoleWithUserBindingsIsBlockedAndListsUsers() {
        UUID org = UUID.randomUUID(), userId = UUID.randomUUID();
        RoleEntity role = RoleEntity.create(org, "viewer", "只读用户", null, false, "SITE", null);
        when(roles.findById(role.getId())).thenReturn(Optional.of(role));
        when(userRoles.findByRoleId(role.getId()))
            .thenReturn(List.of(UserRoleEntity.of(userId, role.getId(), null)));
        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setUsername("editor1");
        when(users.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.deleteRole(role.getId()))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ErrorCodes.CONFLICT);
        assertThatThrownBy(() -> service.deleteRole(role.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("editor1");
        verify(roles, never()).delete(any());
        verify(roleLayouts, never()).deleteByRoleId(any());
    }

    @Test
    void memberTierRoleRejectsSiteCapabilitiesAndLayouts() {
        UUID org = UUID.randomUUID();
        when(roles.findByOrganizationIdAndCode(org, "vip")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createRole(org, new CreateRoleRequest(
            "vip", "VIP", null, List.of("read"), List.of(), 1)))
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ErrorCodes.VALIDATION_FAILED);
        assertThatThrownBy(() -> service.createRole(org, new CreateRoleRequest(
            "vip", "VIP", null, List.of(), List.of("content"), 1)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("后台布局");
        verify(roles, never()).save(any());
    }

    @Test
    void memberTierRoleStoresLevelWithoutCapabilities() {
        UUID org = UUID.randomUUID();
        when(roles.findByOrganizationIdAndCode(org, "vip")).thenReturn(Optional.empty());
        when(roles.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(roleLayouts.findByRoleId(any())).thenReturn(List.of());

        var created = service.createRole(org, new CreateRoleRequest(
            "vip", "VIP", null, List.of(), List.of(), 2));

        assertThat(created.tierLevel()).isEqualTo(2);
        assertThat(created.capabilities()).isEmpty();
        assertThat(created.layouts()).isEmpty();
    }

    @Test
    void memberTierBindsOnlyFrontendUsers() {
        UUID userId = UUID.randomUUID(), operator = UUID.randomUUID(), org = UUID.randomUUID(), site = UUID.randomUUID();
        UserEntity backend = new UserEntity();
        backend.setId(userId);
        backend.setOrganizationId(org);
        backend.setKind(UserEntity.KIND_BACKEND);
        RoleEntity vip = RoleEntity.create(org, "vip", "VIP", null, false, "SITE", "[]");
        vip.setTierLevel(1);
        when(users.findById(userId)).thenReturn(Optional.of(backend));
        when(roles.findByOrganizationIdAndCode(org, "vip")).thenReturn(Optional.of(vip));
        when(capabilities.isSystemAdmin(operator)).thenReturn(true);
        when(sites.existsById(site)).thenReturn(true);

        assertThatThrownBy(() -> service.assignRole(userId, new AssignRoleRequest(null, "vip", site), operator))
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ErrorCodes.BACKEND_ROLE_FORBIDDEN);
        verify(userRoles, never()).save(any(UserRoleEntity.class));

        UserEntity member = new UserEntity();
        member.setId(userId);
        member.setOrganizationId(org);
        member.setKind(UserEntity.KIND_FRONTEND);
        when(users.findById(userId)).thenReturn(Optional.of(member));
        when(userRoles.findBinding(userId, vip.getId(), site)).thenReturn(Optional.empty());

        service.assignRole(userId, new AssignRoleRequest(null, "vip", site), operator);
        verify(userRoles).save(any(UserRoleEntity.class));
    }

    @Test
    void unlockFrontendUserRestoresActiveAndClearsFailures() {
        UUID userId = UUID.randomUUID(), operator = UUID.randomUUID(), site = UUID.randomUUID();
        UserEntity member = lockedMember(userId, site);
        when(users.findById(userId)).thenReturn(Optional.of(member));
        when(capabilities.isSystemAdmin(operator)).thenReturn(false);
        when(capabilities.has(operator, site, SiteCapabilities.ADMIN)).thenReturn(true);

        service.unlockFrontendUser(site, userId, operator);

        assertThat(member.getStatus()).isEqualTo(UserEntity.STATUS_ACTIVE);
        assertThat(member.getFailedLogins()).isZero();
        verify(users).save(member);
    }

    @Test
    void unlockFrontendUserRejectsMemberOfAnotherSite() {
        UUID userId = UUID.randomUUID(), operator = UUID.randomUUID();
        UUID homeSite = UUID.randomUUID(), otherSite = UUID.randomUUID();
        UserEntity member = lockedMember(userId, homeSite);
        when(users.findById(userId)).thenReturn(Optional.of(member));
        when(capabilities.isSystemAdmin(operator)).thenReturn(false);

        assertThatThrownBy(() -> service.unlockFrontendUser(otherSite, userId, operator))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ErrorCodes.NOT_FOUND);
        verify(users, never()).save(any());
    }

    @Test
    void unlockFrontendUserRequiresSiteAdmin() {
        UUID userId = UUID.randomUUID(), operator = UUID.randomUUID(), site = UUID.randomUUID();
        UserEntity member = lockedMember(userId, site);
        when(users.findById(userId)).thenReturn(Optional.of(member));
        when(capabilities.isSystemAdmin(operator)).thenReturn(false);
        when(capabilities.has(operator, site, SiteCapabilities.ADMIN)).thenReturn(false);

        assertThatThrownBy(() -> service.unlockFrontendUser(site, userId, operator))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ErrorCodes.FORBIDDEN);
        verify(users, never()).save(any());
    }

    /** 待审核会员必须走 approveFrontendUser，不能被「解锁」直接激活绕过审核。 */
    @Test
    void unlockFrontendUserRejectsPendingMember() {
        UUID userId = UUID.randomUUID(), operator = UUID.randomUUID(), site = UUID.randomUUID();
        UserEntity member = lockedMember(userId, site);
        member.setStatus(UserEntity.STATUS_PENDING);
        when(users.findById(userId)).thenReturn(Optional.of(member));
        when(capabilities.isSystemAdmin(operator)).thenReturn(false);
        when(capabilities.has(operator, site, SiteCapabilities.ADMIN)).thenReturn(true);

        assertThatThrownBy(() -> service.unlockFrontendUser(site, userId, operator))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ErrorCodes.CONFLICT);
        verify(users, never()).save(any());
    }

    // ---------------- 登录失败文案（FR-IAM-002）：剩余尝试次数 / 账号状态原因 ----------------

    private static UserEntity activeUser(String username, int failedLogins) {
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setStatus(UserEntity.STATUS_ACTIVE);
        user.setFailedLogins(failedLogins);
        return user;
    }

    @Test
    void loginFailureReportsRemainingAttemptsForActiveUser() {
        when(users.findByUsername("alice")).thenReturn(Optional.of(activeUser("alice", 2)));

        BusinessException ex = service.loginFailure("alice", new BadCredentialsException("Bad credentials"));

        assertThat(ex.getCode()).isEqualTo(ErrorCodes.UNAUTHENTICATED);
        assertThat(ex.getMessageKey()).isEqualTo(IdentityServiceImpl.MSG_LOGIN_REMAINING);
        assertThat(ex.getMessageArgs()).containsExactly(3);
        assertThat(ex.getMessage()).isEqualTo("用户名或密码错误，还可尝试 3 次");
    }

    @Test
    void loginFailureReportsLockedReasonWhenAccountIsLocked() {
        UserEntity locked = activeUser("admin", 5);
        locked.setStatus(UserEntity.STATUS_LOCKED);
        when(users.findByUsername("admin")).thenReturn(Optional.of(locked));

        BusinessException ex = service.loginFailure("admin", new BadCredentialsException("Bad credentials"));

        assertThat(ex.getMessageKey()).isEqualTo(IdentityServiceImpl.MSG_LOGIN_LOCKED);
        assertThat(ex.getMessageArgs()).isEmpty();
        assertThat(ex.getMessage()).isEqualTo("账号已被锁定，请联系管理员解锁");
    }

    /** 第 5 次失败把计数打满但状态更新存在竞态时，对外口径与锁定一致（不再报剩余次数）。 */
    @Test
    void loginFailureReportsLockedWhenAttemptsExhaustedEvenIfStatusLagging() {
        when(users.findByUsername("alice")).thenReturn(Optional.of(activeUser("alice", 5)));

        BusinessException ex = service.loginFailure("alice", new BadCredentialsException("Bad credentials"));

        assertThat(ex.getMessageKey()).isEqualTo(IdentityServiceImpl.MSG_LOGIN_LOCKED);
    }

    @Test
    void loginFailureForUnknownUserKeepsGenericMessage() {
        when(users.findByUsername("ghost")).thenReturn(Optional.empty());
        when(users.findByEmailIgnoreCase("ghost")).thenReturn(Optional.empty());

        BusinessException ex = service.loginFailure("ghost", new BadCredentialsException("Bad credentials"));

        assertThat(ex.getMessageKey()).isNull();
        assertThat(ex.getMessage()).isEqualTo("用户名或密码错误");
    }

    @Test
    void loginFailureForDisabledAndPendingAccountsReturnStatusKeys() {
        UserEntity disabled = activeUser("bob", 0);
        disabled.setStatus(UserEntity.STATUS_DISABLED);
        when(users.findByUsername("bob")).thenReturn(Optional.of(disabled));
        assertThat(service.loginFailure("bob", new BadCredentialsException("x")).getMessageKey())
            .isEqualTo(IdentityServiceImpl.MSG_LOGIN_DISABLED);

        UserEntity pending = activeUser("carol", 0);
        pending.setStatus(UserEntity.STATUS_PENDING);
        when(users.findByUsername("carol")).thenReturn(Optional.of(pending));
        assertThat(service.loginFailure("carol", new BadCredentialsException("x")).getMessageKey())
            .isEqualTo(IdentityServiceImpl.MSG_LOGIN_PENDING);
    }

    @Test
    void loginFailureForExpiredCredentialsReturnsExpiredKey() {
        when(users.findByUsername("alice")).thenReturn(Optional.of(activeUser("alice", 0)));

        BusinessException ex = service.loginFailure("alice", new CredentialsExpiredException("expired"));

        assertThat(ex.getMessageKey()).isEqualTo(IdentityServiceImpl.MSG_LOGIN_EXPIRED);
    }

    /** EMAIL 登录标识模式：按邮箱解析用户，剩余次数提示同样生效。 */
    @Test
    void loginFailureResolvesUserByEmailWhenUsernameLookupMisses() {
        when(users.findByUsername("alice@example.com")).thenReturn(Optional.empty());
        when(users.findByEmailIgnoreCase("alice@example.com"))
            .thenReturn(Optional.of(activeUser("alice", 1)));

        BusinessException ex = service.loginFailure("alice@example.com", new BadCredentialsException("x"));

        assertThat(ex.getMessageKey()).isEqualTo(IdentityServiceImpl.MSG_LOGIN_REMAINING);
        assertThat(ex.getMessageArgs()).containsExactly(4);
    }

    private static UserEntity lockedMember(UUID userId, UUID homeSite) {
        UserEntity member = new UserEntity();
        member.setId(userId);
        member.setKind(UserEntity.KIND_FRONTEND);
        member.setHomeSiteId(homeSite);
        member.setStatus(UserEntity.STATUS_LOCKED);
        member.setFailedLogins(5);
        return member;
    }

    private static BackendLayoutEntity layoutWithId(UUID id, String code) {
        BackendLayoutEntity layout = BackendLayoutEntity.of(code, code);
        try {
            var field = BackendLayoutEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(layout, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return layout;
    }
}
