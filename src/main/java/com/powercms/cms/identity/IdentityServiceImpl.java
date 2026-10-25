package com.powercms.cms.identity;

import com.powercms.cms.audit.AuditService;
import com.powercms.cms.identity.dto.AssignRoleRequest;
import com.powercms.cms.identity.dto.ChangePasswordRequest;
import com.powercms.cms.identity.dto.CreateRoleRequest;
import com.powercms.cms.identity.dto.CreateUserRequest;
import com.powercms.cms.identity.dto.MeResponse;
import com.powercms.cms.identity.dto.RoleResponse;
import com.powercms.cms.identity.dto.SiteAuthorizationPolicyResponse;
import com.powercms.cms.identity.dto.SiteMemberResponse;
import com.powercms.cms.identity.dto.UpdateSiteAuthorizationPolicyRequest;
import com.powercms.cms.identity.dto.UserResponse;
import com.powercms.cms.identity.entity.BackendLayoutEntity;
import com.powercms.cms.identity.entity.OrganizationEntity;
import com.powercms.cms.identity.entity.RoleEntity;
import com.powercms.cms.identity.entity.RoleLayoutEntity;
import com.powercms.cms.identity.entity.SiteAuthorizationPolicyEntity;
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
import com.powercms.cms.site.entity.SiteEntity;
import com.powercms.cms.site.repo.SiteRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class IdentityServiceImpl implements IdentityService {
    static final String ROLE_SYS_ADMIN = BuiltInRoleCatalog.SYSTEM_ADMIN;

    private final UserRepository userRepo;
    private final RoleRepository roleRepo;
    private final UserRoleRepository userRoleRepo;
    private final OrganizationRepository orgRepo;
    private final SiteAuthorizationPolicyRepository policyRepo;
    private final SiteRepository siteRepo;
    private final BackendLayoutRepository layoutRepo;
    private final RoleLayoutRepository roleLayoutRepo;
    private final SiteCapabilityEvaluator capabilities;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final UserPasswordHistoryRepository passwordHistory;
    private final UserProfileService profileService;
    private final IdentityProperties properties;
    private final UserReferenceMigrator referenceMigrator;

    /** 社区版后台管理账号建议基线（规格 §4.1：10 个，超出只告警）。 */
    static final long COMMUNITY_ADMIN_USERS_BASELINE = 10L;
    /** 社区版前台会员建议基线（规格 §4.1：1 万，超出只告警）。 */
    static final long COMMUNITY_FRONTEND_MEMBERS_BASELINE = 10_000L;

    /**
     * 组织供应的临界区（DEF-CFG-01）：保证并发调用方只创建一个组织。
     * 静态锁 = 进程内唯一，覆盖「引导事件 + 首个请求过滤器」这一对并发创建者。
     */
    private static final Object ORGANIZATION_CREATION_LOCK = new Object();
    /**
     * 建组织专用事务（REQUIRES_NEW）：必须在持锁期间**提交**，否则下一个持锁者复查时仍看不到这一行。
     * 若沿用外层事务，提交发生在方法返回之后（锁已释放），双检形同虚设。
     */
    private final TransactionTemplate organizationCreationTx;

    IdentityServiceImpl(UserRepository userRepo, RoleRepository roleRepo,
                        UserRoleRepository userRoleRepo, OrganizationRepository orgRepo,
                        SiteAuthorizationPolicyRepository policyRepo, SiteRepository siteRepo,
                        BackendLayoutRepository layoutRepo, RoleLayoutRepository roleLayoutRepo,
                        SiteCapabilityEvaluator capabilities, PasswordEncoder passwordEncoder,
                        AuditService audit, UserPasswordHistoryRepository passwordHistory,
                        UserProfileService profileService, IdentityProperties properties,
                        UserReferenceMigrator referenceMigrator,
                        PlatformTransactionManager transactionManager) {
        this.userRepo = userRepo;
        this.roleRepo = roleRepo;
        this.userRoleRepo = userRoleRepo;
        this.orgRepo = orgRepo;
        this.policyRepo = policyRepo;
        this.siteRepo = siteRepo;
        this.layoutRepo = layoutRepo;
        this.roleLayoutRepo = roleLayoutRepo;
        this.capabilities = capabilities;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.passwordHistory = passwordHistory;
        this.profileService = profileService;
        this.properties = properties;
        this.referenceMigrator = referenceMigrator;
        this.organizationCreationTx = new TransactionTemplate(transactionManager);
        this.organizationCreationTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 社区版账号规模基线观测：超出建议支撑基线只告警，不拦截（规格 §4.1 三层口径）。 */
    private void observeUserBaseline(String kind) {
        try {
            long active = UserEntity.KIND_FRONTEND.equals(kind)
                ? userRepo.countByKindAndStatus(UserEntity.KIND_FRONTEND, UserEntity.STATUS_ACTIVE)
                : userRepo.countByKindAndStatus(UserEntity.KIND_BACKEND, UserEntity.STATUS_ACTIVE);
            long baseline = UserEntity.KIND_FRONTEND.equals(kind)
                ? COMMUNITY_FRONTEND_MEMBERS_BASELINE : COMMUNITY_ADMIN_USERS_BASELINE;
            if (active >= baseline) {
                org.slf4j.LoggerFactory.getLogger(IdentityServiceImpl.class).warn(
                    "community baseline: {} 账号数 {} 已达/超过社区建议基线 {}；超出基线的部署不在官方支持范围内",
                    kind, active, baseline);
            }
        } catch (RuntimeException ignored) {
            // 基线观测不得影响建号
        }
    }

    @Override
    public UserResponse createUser(UUID organizationId, CreateUserRequest req) {
        if (userRepo.existsByUsername(req.username())) {
            throw new BusinessException(ErrorCodes.CONFLICT, "用户名已存在");
        }
        validatePassword(req.password());
        String kind = normalizeKind(req.kind());
        observeUserBaseline(kind);
        if (UserEntity.KIND_FRONTEND.equals(kind) && req.homeSiteId() == null) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "前台会员必须指定 homeSiteId");
        }
        if (req.homeSiteId() != null && !siteRepo.existsById(req.homeSiteId())) {
            throw new BusinessException(ErrorCodes.NOT_FOUND, "站点不存在");
        }
        UserEntity user = new UserEntity();
        user.setOrganizationId(organizationId);
        user.setUsername(req.username());
        user.setDisplayName(req.displayName());
        user.setEmail(req.email());
        user.setKind(kind);
        user.setHomeSiteId(req.homeSiteId());
        applyNewPassword(user, req.password());
        user = userRepo.save(user);
        rememberPassword(user.getId(), user.getPasswordHash());
        grantChannelRoles(user, kind);
        profileService.ensureProfile(user);
        audit.write(req.username(), "USER_CREATE", "User", user.getId().toString(), null, "SUCCESS",
            "kind=" + kind);
        return toResponse(user);
    }

    @Override
    public UserResponse getUser(UUID userId) {
        return toResponse(requireUser(userId));
    }

    @Override
    public List<UserResponse> listUsers(UUID organizationId) {
        // spec 9.1：GET /users 默认只列 kind=BACKEND；前台会员走站点成员/前台会员 API。
        return userRepo.findAll().stream()
            .filter(u -> u.getOrganizationId().equals(organizationId))
            .filter(u -> u.getKind() == null || UserEntity.KIND_BACKEND.equals(u.getKind()))
            .map(this::toResponse)
            .toList();
    }

    @Override
    public void disableUser(UUID userId, UUID operatorId) {
        disableUser(userId, null, operatorId);
    }

    @Override
    public void disableUser(UUID userId, UUID reassignTo, UUID operatorId) {
        UserEntity user = requireUser(userId);
        if (userId.equals(operatorId)) {
            throw new BusinessException(ErrorCodes.CONFLICT, "不能禁用自己");
        }
        if (capabilities.isSystemAdmin(userId)
            && countActiveSystemAdmins(user.getOrganizationId()) <= 1) {
            throw new BusinessException(ErrorCodes.LAST_ADMIN_FORBIDDEN, "最后一个系统管理员不得被禁用");
        }
        requireNoReferencesOrReassign(user, reassignTo, operatorId);
        user.setStatus(UserEntity.STATUS_DISABLED);
        userRepo.save(user);
        audit.write(actor(operatorId), "USER_DISABLE", "User", userId.toString(), null, "SUCCESS", null);
    }

    @Override
    public java.util.Map<String, Object> getUserReferences(UUID userId) {
        UserReferenceMigrator.ReferenceSummary refs = referenceMigrator.referencesOf(userId);
        java.util.Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("ownedContents", refs.ownedContents());
        result.put("pendingTasks", refs.pendingTasks());
        result.put("hasReferences", refs.hasReferences());
        return result;
    }

    /**
     * spec 5.3 引用迁移：若用户仍为内容 owner 或工作流待办受理人，必须指定替代用户
     * （reassignTo）先行迁移，否则拒绝禁用（USER_REFERENCE_CONFLICT）。
     */
    private void requireNoReferencesOrReassign(UserEntity user, UUID reassignTo, UUID operatorId) {
        UserReferenceMigrator.ReferenceSummary refs = referenceMigrator.referencesOf(user.getId());
        if (!refs.hasReferences()) {
            return;
        }
        if (reassignTo == null) {
            throw new BusinessException(ErrorCodes.USER_REFERENCE_CONFLICT,
                "用户仍为 " + refs.ownedContents() + " 条内容的 owner 并有 "
                    + refs.pendingTasks() + " 条工作流待办，必须指定替代用户 reassignTo 迁移引用后再禁用");
        }
        referenceMigrator.reassign(user.getId(), reassignTo, operatorId);
    }

    @Override
    public void unlockUser(UUID userId, UUID operatorId) {
        UserEntity user = requireUser(userId);
        user.setStatus(UserEntity.STATUS_ACTIVE);
        user.setFailedLogins(0);
        userRepo.save(user);
        audit.write(actor(operatorId), "USER_UNLOCK", "User", userId.toString(), null, "SUCCESS", null);
    }

    @Override
    public void changePassword(UUID userId, ChangePasswordRequest req, UUID operatorId) {
        UserEntity user = requireUser(userId);
        boolean self = userId.equals(operatorId);
        boolean admin = capabilities.isSystemAdmin(operatorId);
        if (!self && !admin) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "没有权限修改该用户密码");
        }
        if (user.isExternallyManaged()) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "外部托管账号不能修改本地密码");
        }
        if (self && !admin) {
            if (req.currentPassword() == null || req.currentPassword().isBlank()
                || !passwordEncoder.matches(req.currentPassword(), user.getPasswordHash())) {
                throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "当前密码错误");
            }
        }
        applyNewPassword(user, req.newPassword());
        userRepo.save(user);
        rememberPassword(user.getId(), user.getPasswordHash());
        audit.write(actor(operatorId), "USER_PASSWORD_CHANGE", "User", userId.toString(), null, "SUCCESS", null);
    }

    @Override
    public RoleResponse createRole(UUID organizationId, CreateRoleRequest req) {
        String code = BuiltInRoleCatalog.normalize(req.code());
        if (BuiltInRoleCatalog.isBuiltIn(code)) {
            throw new BusinessException(ErrorCodes.CONFLICT, "不能创建与内置角色相同的编码");
        }
        if (roleRepo.findByOrganizationIdAndCode(organizationId, code).isPresent()) {
            throw new BusinessException(ErrorCodes.CONFLICT, "角色编码已存在");
        }
        Integer tierLevel = memberTierLevel(req.tierLevel());
        rejectMemberTierPrivileges(tierLevel, req.capabilities(), req.layouts());
        RoleEntity role = RoleEntity.create(organizationId, code, req.name(), req.description(), false,
            BuiltInRoleCatalog.SCOPE_SITE, SiteCapabilities.toJson(tierLevel == null ? req.capabilities() : List.of()));
        role.setTierLevel(tierLevel);
        // 布局编码校验先于落库：未知名必须整单拒绝，不留半成品角色。
        List<String> layoutCodes = req.layouts() == null ? List.of() : normalizeLayoutCodes(req.layouts());
        RoleEntity saved = roleRepo.save(role);
        for (String layoutCode : layoutCodes) {
            layoutRepo.findByCode(layoutCode)
                .ifPresent(layout -> roleLayoutRepo.save(RoleLayoutEntity.of(saved.getId(), layout.getId())));
        }
        audit.write("system-admin", "ROLE_CREATE", "Role", saved.getId().toString(), null, "SUCCESS", code);
        return toRoleResponse(saved);
    }

    @Override
    public List<RoleResponse> listRoles(UUID organizationId) {
        ensureBuiltInRoles(organizationId);
        return roleRepo.findByOrganizationId(organizationId).stream()
            .map(this::toRoleResponse).toList();
    }

    @Override
    public void deleteRole(UUID roleId) {
        RoleEntity role = roleRepo.findById(roleId)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "角色不存在"));
        if (role.isBuiltIn() || BuiltInRoleCatalog.isBuiltIn(role.getCode())) {
            throw new BusinessException(ErrorCodes.BUILTIN_ROLE_LOCKED, "内置角色不可删除");
        }
        // 引用保护（TC-M02-009）：仍被用户绑定时拒绝删除并列出引用用户，避免静默把用户置为无角色。
        List<UserRoleEntity> userBindings = userRoleRepo.findByRoleId(roleId);
        if (!userBindings.isEmpty()) {
            LinkedHashSet<String> usernames = new LinkedHashSet<>();
            for (UserRoleEntity binding : userBindings) {
                userRepo.findById(binding.getUserId())
                    .map(UserEntity::getUsername)
                    .ifPresent(usernames::add);
            }
            audit.write("system-admin", "ROLE_DELETE", "Role", roleId.toString(), null, "FAILED",
                "仍被 " + userBindings.size() + " 条用户绑定引用");
            throw new BusinessException(ErrorCodes.CONFLICT,
                "角色仍被 " + usernames.size() + " 个用户引用，无法删除：" + String.join("、", usernames));
        }
        // 布局绑定是角色的从属配置而非独立引用：先清绑定再删角色（外键无级联，DEF-11-18）。
        roleLayoutRepo.deleteByRoleId(roleId);
        roleLayoutRepo.flush();
        roleRepo.delete(role);
        audit.write("system-admin", "ROLE_DELETE", "Role", roleId.toString(), null, "SUCCESS", role.getCode());
    }

    @Override
    public RoleResponse updateRoleCapabilities(UUID roleId, List<String> capabilities) {
        RoleEntity role = roleRepo.findById(roleId)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "角色不存在"));
        if (role.isBuiltIn() || BuiltInRoleCatalog.isBuiltIn(role.getCode())) {
            throw new BusinessException(ErrorCodes.BUILTIN_ROLE_LOCKED, "内置角色能力不可修改");
        }
        if (role.getTierLevel() != null && capabilities != null && !capabilities.isEmpty()) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "会员档角色不能授予站点能力");
        }
        role.setCapabilitiesJson(SiteCapabilities.toJson(capabilities));
        return toRoleResponse(roleRepo.save(role));
    }

    @Override
    public RoleResponse updateRoleLayouts(UUID roleId, List<String> layoutCodes) {
        RoleEntity role = roleRepo.findById(roleId)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "角色不存在"));
        String code = BuiltInRoleCatalog.normalize(role.getCode());
        if (BuiltInRoleCatalog.FRONT_END_USER.equals(code)
            || BuiltInRoleCatalog.ANONYMOUS.equals(code)
            || BuiltInRoleCatalog.OWNER.equals(code)) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "通道/伪角色禁止绑定后台布局");
        }
        if (role.getTierLevel() != null && layoutCodes != null
            && layoutCodes.stream().anyMatch(c -> c != null && !c.isBlank())) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "会员档角色不能关联后台布局");
        }
        List<String> normalized = layoutCodes == null ? List.of() : normalizeLayoutCodes(layoutCodes);
        List<RoleLayoutEntity> existing = roleLayoutRepo.findByRoleId(roleId);
        if (!existing.isEmpty()) {
            roleLayoutRepo.deleteAll(existing);
            // 同一次 flush 中 Hibernate 先执行 INSERT 再执行 DELETE（ActionQueue 顺序），
            // 保留任一原有布局的提交会撞上 (role_id, layout_id) 唯一约束并整体失败（DEF-11-17）。
            roleLayoutRepo.flush();
        }
        for (String layoutCode : normalized) {
            layoutRepo.findByCode(layoutCode)
                .ifPresent(layout -> roleLayoutRepo.save(RoleLayoutEntity.of(roleId, layout.getId())));
        }
        audit.write("system-admin", "ROLE_LAYOUTS", "Role", roleId.toString(), null, "SUCCESS", role.getCode());
        return toRoleResponse(role);
    }

    /**
     * 归一化 + 去重 + 校验后台布局编码（DEF-11-19）。
     *
     * <p>历史行为是「未知编码静默丢弃」：调用方拿到 200/201 却观察到 {@code layouts=[]}，排查时
     * 完全没有线索（域 11 的 {@code DEF-11-06} 就因此被误判成产品缺少布局编码）。改为显式 400
     * 并列出非法编码，同时去重——同一编码重复提交会撞 {@code (role_id, layout_id)} 唯一约束。</p>
     */
    private List<String> normalizeLayoutCodes(List<String> layoutCodes) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        LinkedHashSet<String> unknown = new LinkedHashSet<>();
        for (String layoutCode : layoutCodes) {
            if (layoutCode == null || layoutCode.isBlank()) continue;
            String code = BuiltInRoleCatalog.normalize(layoutCode);
            if (BackendLayoutCatalog.isKnown(code)) {
                normalized.add(code);
            } else {
                unknown.add(layoutCode.trim());
            }
        }
        if (!unknown.isEmpty()) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED,
                "未知的后台布局编码：" + String.join("、", unknown)
                    + "；可用编码：" + String.join("、", BackendLayoutCatalog.ALL));
        }
        return List.copyOf(normalized);
    }

    @Override
    public void assignRole(UUID userId, AssignRoleRequest req, UUID operatorId) {
        UserEntity user = requireUser(userId);
        RoleEntity role = resolveRole(user.getOrganizationId(), req);
        UUID siteId = req.siteId();
        validateBindingScope(role, siteId);
        authorizeBindingChange(operatorId, siteId, role);
        rejectFrontendBackendMix(user, role);
        if (role.getTierLevel() != null && !UserEntity.KIND_FRONTEND.equals(user.getKind())) {
            throw new BusinessException(ErrorCodes.BACKEND_ROLE_FORBIDDEN, "会员档角色只能绑定前台会员");
        }
        if (userRoleRepo.findBinding(userId, role.getId(), siteId).isPresent()) {
            throw new BusinessException(ErrorCodes.CONFLICT, "该角色绑定已存在");
        }
        if (siteId != null && !siteRepo.existsById(siteId)) {
            throw new BusinessException(ErrorCodes.NOT_FOUND, "站点不存在");
        }
        userRoleRepo.save(UserRoleEntity.of(userId, role.getId(), siteId));
        audit.write(actor(operatorId), "ROLE_ASSIGN", "User", userId.toString(), siteId, "SUCCESS", role.getCode());
    }

    @Override
    public void revokeRole(UUID userId, UUID roleId, UUID siteId, UUID operatorId) {
        requireUser(userId);
        RoleEntity role = roleRepo.findById(roleId)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "角色不存在"));
        validateBindingScope(role, siteId);
        authorizeBindingChange(operatorId, siteId, role);
        if (BuiltInRoleCatalog.SITE_ADMIN.equals(BuiltInRoleCatalog.normalize(role.getCode()))
            && siteId != null
            && userRoleRepo.countActiveSiteAdmins(siteId) <= 1
            && !capabilities.isSystemAdmin(operatorId)) {
            throw new BusinessException(ErrorCodes.LAST_ADMIN_FORBIDDEN, "不能撤销该站最后一个站点管理员");
        }
        if (BuiltInRoleCatalog.SYSTEM_ADMIN.equals(BuiltInRoleCatalog.normalize(role.getCode()))
            && siteId == null) {
            UserEntity user = requireUser(userId);
            if (countActiveSystemAdmins(user.getOrganizationId()) <= 1) {
                throw new BusinessException(ErrorCodes.LAST_ADMIN_FORBIDDEN, "不能撤销最后一个系统管理员");
            }
        }
        userRoleRepo.deleteBinding(userId, roleId, siteId);
        audit.write(actor(operatorId), "ROLE_REVOKE", "User", userId.toString(), siteId, "SUCCESS", role.getCode());
    }

    @Override
    public void bindInitialSiteAdmin(UUID userId, UUID siteId) {
        UserEntity user = requireUser(userId);
        ensureBuiltInRoles(user.getOrganizationId());
        RoleEntity role = roleRepo.findByOrganizationIdAndCode(user.getOrganizationId(), BuiltInRoleCatalog.SITE_ADMIN)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "内置角色 site-admin 不存在"));
        if (userRoleRepo.findBinding(userId, role.getId(), siteId).isEmpty()) {
            userRoleRepo.save(UserRoleEntity.of(userId, role.getId(), siteId));
        }
    }

    @Override
    public void addSiteMember(UUID siteId, String username, String roleCode, UUID operatorId) {
        UserEntity user = userRepo.findByUsername(username)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "用户不存在"));
        assignRole(user.getId(), new AssignRoleRequest(null, roleCode, siteId), operatorId);
    }

    @Override
    public List<SiteMemberResponse> listSiteMembers(UUID siteId) {
        Map<UUID, List<UserResponse.RoleAssignment>> byUser = new LinkedHashMap<>();
        for (UserRoleEntity binding : userRoleRepo.findBySiteId(siteId)) {
            RoleEntity role = roleRepo.findById(binding.getRoleId()).orElse(null);
            byUser.computeIfAbsent(binding.getUserId(), id -> new ArrayList<>())
                .add(new UserResponse.RoleAssignment(binding.getRoleId(),
                    role != null ? role.getCode() : "?", role != null ? role.getName() : "?", siteId));
        }
        List<SiteMemberResponse> members = new ArrayList<>();
        for (Map.Entry<UUID, List<UserResponse.RoleAssignment>> entry : byUser.entrySet()) {
            UserEntity user = userRepo.findById(entry.getKey()).orElse(null);
            if (user == null) continue;
            members.add(new SiteMemberResponse(user.getId(), user.getUsername(), user.getDisplayName(),
                user.getEmail(), user.getStatus(), entry.getValue()));
        }
        members.sort(Comparator.comparing(SiteMemberResponse::username, String.CASE_INSENSITIVE_ORDER));
        return members;
    }

    @Override
    public List<SiteMemberResponse> listFrontendUsers(UUID siteId) {
        List<SiteMemberResponse> members = new ArrayList<>();
        for (UserEntity user : userRepo.findByKindAndHomeSiteId(UserEntity.KIND_FRONTEND, siteId)) {
            List<UserResponse.RoleAssignment> roles = userRoleRepo.findByUserId(user.getId()).stream()
                .map(b -> {
                    RoleEntity r = roleRepo.findById(b.getRoleId()).orElse(null);
                    return new UserResponse.RoleAssignment(b.getRoleId(),
                        r != null ? r.getCode() : "?", r != null ? r.getName() : "?", b.getSiteId());
                })
                .toList();
            members.add(new SiteMemberResponse(user.getId(), user.getUsername(), user.getDisplayName(),
                user.getEmail(), user.getStatus(), roles));
        }
        members.sort(Comparator.comparing(SiteMemberResponse::username, String.CASE_INSENSITIVE_ORDER));
        return members;
    }

    @Override
    public UserResponse createFrontendUser(UUID siteId, CreateUserRequest req, UUID operatorId) {
        if (!siteRepo.existsById(siteId)) {
            throw new BusinessException(ErrorCodes.NOT_FOUND, "站点不存在");
        }
        UserResponse user = createUser(requireUser(operatorId).getOrganizationId(),
            new CreateUserRequest(req.username(), req.password(), req.displayName(), req.email(),
                UserEntity.KIND_FRONTEND, siteId));
        audit.write(actor(operatorId), "FRONTEND_USER_CREATE", "User", user.id().toString(),
            siteId, "SUCCESS", req.username());
        return user;
    }

    @Override
    public java.util.Map<String, Object> getFrontendUser(UUID siteId, UUID userId, UUID operatorId) {
        UserEntity user = requireUser(userId);
        if (!UserEntity.KIND_FRONTEND.equals(user.getKind())
            || !siteId.equals(user.getHomeSiteId())) {
            throw new BusinessException(ErrorCodes.NOT_FOUND, "会员不存在");
        }
        requireProfileAccess(user, operatorId);
        java.util.Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", user.getId());
        result.put("username", user.getUsername());
        result.put("displayName", user.getDisplayName());
        result.put("email", user.getEmail());
        result.put("status", user.getStatus());
        result.put("profile", profileService.readProfile(user.getProfileContentId()));
        return result;
    }

    @Override
    public void disableFrontendUser(UUID siteId, UUID userId, UUID operatorId) {
        disableFrontendUser(siteId, userId, null, operatorId);
    }

    @Override
    public void disableFrontendUser(UUID siteId, UUID userId, UUID reassignTo, UUID operatorId) {
        UserEntity user = requireUser(userId);
        if (!UserEntity.KIND_FRONTEND.equals(user.getKind())
            || !siteId.equals(user.getHomeSiteId())) {
            throw new BusinessException(ErrorCodes.NOT_FOUND, "会员不存在");
        }
        boolean admin = capabilities.isSystemAdmin(operatorId)
            || capabilities.has(operatorId, siteId, SiteCapabilities.ADMIN);
        if (!admin) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "仅本站管理员或系统管理员可禁用会员");
        }
        if (userId.equals(operatorId)) {
            throw new BusinessException(ErrorCodes.CONFLICT, "不能禁用自己");
        }
        requireNoReferencesOrReassign(user, reassignTo, operatorId);
        user.setStatus(UserEntity.STATUS_DISABLED);
        userRepo.save(user);
        audit.write(actor(operatorId), "FRONTEND_USER_DISABLE", "User", userId.toString(),
            siteId, "SUCCESS", null);
    }

    @Override
    public SiteAuthorizationPolicyResponse getAuthorizationPolicy(UUID siteId) {
        SiteAuthorizationPolicyEntity policy = policyRepo.findById(siteId)
            .orElseGet(() -> SiteAuthorizationPolicyEntity.defaults(siteId));
        return toPolicy(policy);
    }

    @Override
    public SiteAuthorizationPolicyResponse updateAuthorizationPolicy(UUID siteId,
                                                                     UpdateSiteAuthorizationPolicyRequest req) {
        if (!siteRepo.existsById(siteId)) {
            throw new BusinessException(ErrorCodes.NOT_FOUND, "站点不存在");
        }
        SiteAuthorizationPolicyEntity policy = policyRepo.findById(siteId)
            .orElseGet(() -> SiteAuthorizationPolicyEntity.defaults(siteId));
        if (req.peerReviewRequired() != null) {
            // 矩阵 E4：双人审批为专业版及以上能力，社区版不提供。
            if (req.peerReviewRequired()) {
                throw new BusinessException(ErrorCodes.FORBIDDEN, "社区版不支持双人审批");
            }
            policy.setPeerReviewRequired(false);
        }
        if (req.reviewerCanWrite() != null) policy.setReviewerCanWrite(req.reviewerCanWrite());
        if (req.publisherCanWrite() != null) policy.setPublisherCanWrite(req.publisherCanWrite());
        // FR-AI-GOV-004：AI 直接发布开关变更审计（含旧值→新值），策略整体变更亦落审计
        boolean agentDirectChanged =
            req.agentDirectPublish() != null && req.agentDirectPublish() != policy.isAgentDirectPublish();
        if (req.agentDirectPublish() != null) policy.setAgentDirectPublish(req.agentDirectPublish());
        if (req.memberRegistrationReview() != null) policy.setMemberRegistrationReview(req.memberRegistrationReview());
        policy = policyRepo.save(policy);
        audit.write("site-admin", "SITE_AUTHZ_POLICY", "Site", siteId.toString(), siteId, "SUCCESS", null);
        if (agentDirectChanged) {
            audit.write("site-admin", "AI_AGENT_DIRECT_PUBLISH_CHANGED", "Site", siteId.toString(),
                siteId, "SUCCESS", "agent_direct_publish: "
                    + (!req.agentDirectPublish() ? "true → false" : "false → true")
                    + "（false ⟺ AI 内容必须审批）");
        }
        return toPolicy(policy);
    }

    @Override
    public boolean memberRegistrationRequiresReview(UUID siteId) {
        return policyRepo.findById(siteId)
            .map(SiteAuthorizationPolicyEntity::isMemberRegistrationReview)
            .orElse(false);
    }

    @Override
    public void holdFrontendUserForReview(UUID userId) {
        UserEntity user = requireUser(userId);
        user.setStatus(UserEntity.STATUS_PENDING);
        userRepo.save(user);
    }

    @Override
    public void approveFrontendUser(UUID siteId, UUID userId, UUID operatorId) {
        UserEntity user = requireUser(userId);
        if (!UserEntity.KIND_FRONTEND.equals(user.getKind()) || !siteId.equals(user.getHomeSiteId())) {
            throw new BusinessException(ErrorCodes.NOT_FOUND, "会员不存在");
        }
        boolean admin = capabilities.isSystemAdmin(operatorId)
            || capabilities.has(operatorId, siteId, SiteCapabilities.ADMIN);
        if (!admin) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "仅本站管理员或系统管理员可通过会员");
        }
        if (!UserEntity.STATUS_PENDING.equals(user.getStatus())) {
            throw new BusinessException(ErrorCodes.CONFLICT, "只有待审核会员可以通过");
        }
        user.setStatus(UserEntity.STATUS_ACTIVE);
        userRepo.save(user);
        audit.write(actor(operatorId), "FRONTEND_USER_APPROVE", "User", userId.toString(),
            siteId, "SUCCESS", user.getUsername());
    }

    @Override
    public void unlockFrontendUser(UUID siteId, UUID userId, UUID operatorId) {
        UserEntity user = requireUser(userId);
        if (!UserEntity.KIND_FRONTEND.equals(user.getKind()) || !siteId.equals(user.getHomeSiteId())) {
            throw new BusinessException(ErrorCodes.NOT_FOUND, "会员不存在");
        }
        boolean admin = capabilities.isSystemAdmin(operatorId)
            || capabilities.has(operatorId, siteId, SiteCapabilities.ADMIN);
        if (!admin) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "仅本站管理员或系统管理员可解锁会员");
        }
        if (UserEntity.STATUS_PENDING.equals(user.getStatus())) {
            throw new BusinessException(ErrorCodes.CONFLICT, "待审核会员请先通过审核，不能用解锁激活");
        }
        user.setStatus(UserEntity.STATUS_ACTIVE);
        user.setFailedLogins(0);
        userRepo.save(user);
        audit.write(actor(operatorId), "FRONTEND_USER_UNLOCK", "User", userId.toString(),
            siteId, "SUCCESS", user.getUsername());
    }

    @Override
    public String frontendLoginBlockReason(String username) {
        if (username == null || username.isBlank()) return null;
        return userRepo.findByUsername(username.trim())
            .filter(user -> UserEntity.STATUS_PENDING.equals(user.getStatus()))
            .map(user -> "账号正在审核，通过后即可登录")
            .orElse(null);
    }

    @Override
    public BusinessException loginFailure(String username, AuthenticationException cause) {
        UserEntity user = resolveLoginUser(username);
        if (user != null) {
            // 状态型失败优先：给出准确原因（与密码对错无关，不再报「用户名或密码错误」）。
            if (UserEntity.STATUS_LOCKED.equals(user.getStatus())) {
                return BusinessException.keyed(ErrorCodes.UNAUTHENTICATED,
                    "账号已被锁定，请联系管理员解锁", MSG_LOGIN_LOCKED);
            }
            if (UserEntity.STATUS_DISABLED.equals(user.getStatus())) {
                return BusinessException.keyed(ErrorCodes.UNAUTHENTICATED,
                    "账号已被停用，请联系管理员", MSG_LOGIN_DISABLED);
            }
            if (UserEntity.STATUS_PENDING.equals(user.getStatus())) {
                return BusinessException.keyed(ErrorCodes.UNAUTHENTICATED,
                    "账号正在审核，通过后即可登录", MSG_LOGIN_PENDING);
            }
        }
        if (cause instanceof CredentialsExpiredException) {
            return BusinessException.keyed(ErrorCodes.UNAUTHENTICATED,
                "密码已过期，请先修改密码", MSG_LOGIN_EXPIRED);
        }
        if (user != null && UserEntity.STATUS_ACTIVE.equals(user.getStatus())
            && (cause instanceof BadCredentialsException || cause instanceof UsernameNotFoundException)) {
            // 失败计数由 IdentityAuthenticationEvents 在认证失败事件里先落库（同线程先于本方法读取）。
            int threshold = Math.max(1, properties.getMaxFailedLogins());
            int remaining = threshold - Math.max(0, user.getFailedLogins());
            if (remaining <= 0) {
                // 计数到阈值但状态更新存在竞态时的兜底：对外口径与锁定一致。
                return BusinessException.keyed(ErrorCodes.UNAUTHENTICATED,
                    "账号已被锁定，请联系管理员解锁", MSG_LOGIN_LOCKED);
            }
            return BusinessException.keyed(ErrorCodes.UNAUTHENTICATED,
                "用户名或密码错误，还可尝试 " + remaining + " 次", MSG_LOGIN_REMAINING, remaining);
        }
        return new BusinessException(ErrorCodes.UNAUTHENTICATED, "用户名或密码错误");
    }

    /**
     * 按登录标识解析用户（USERNAME 模式为用户名，EMAIL 模式为邮箱），
     * 与 {@code IdentityUserDetailsService} 的认证链口径一致。
     */
    private UserEntity resolveLoginUser(String username) {
        if (username == null || username.isBlank()) return null;
        String identifier = username.trim();
        return userRepo.findByUsername(identifier)
            .or(() -> userRepo.findByEmailIgnoreCase(identifier))
            .orElse(null);
    }

    @Override
    public MeResponse currentUser(UUID userId, String loginMode) {
        UserEntity user = requireUser(userId);
        List<String> orgRoles = new ArrayList<>(capabilities.organizationRoleCodes(userId));
        Map<UUID, List<String>> rolesBySite = new LinkedHashMap<>();
        for (SiteCapabilityEvaluator.SiteRoleView view : capabilities.siteRoles(userId)) {
            if (view.siteId() == null) continue;
            rolesBySite.computeIfAbsent(view.siteId(), id -> new ArrayList<>()).add(view.roleCode());
        }
        List<MeResponse.SiteAccess> sites = new ArrayList<>();
        for (Map.Entry<UUID, List<String>> entry : rolesBySite.entrySet()) {
            SiteEntity site = siteRepo.findById(entry.getKey()).orElse(null);
            sites.add(new MeResponse.SiteAccess(
                entry.getKey(),
                site != null ? site.getSiteKey() : null,
                site != null ? site.getName() : null,
                entry.getValue(),
                new ArrayList<>(capabilities.capabilities(userId, entry.getKey()))));
        }
        String effectiveMode = loginMode != null && !loginMode.isBlank()
            ? loginMode : user.getLastLoginMode();
        boolean passwordExpired = IdentityUserDetailsService.credentialsExpired(
            user, properties.getPasswordExpireDays());
        Map<String, Object> profile = profileService.readProfile(user.getProfileContentId());
        return new MeResponse(user.getId(), user.getUsername(), profileDisplayName(profile, user),
            user.getEmail(), user.getStatus(), user.getKind(), effectiveMode,
            user.getLanguage(),
            orgRoles, layoutsForUser(userId), sites, passwordExpired,
            profile, memberGroupsOf(user));
    }

    /** 资料里的显示名优先，否则用会员账号上的显示名。 */
    private static String profileDisplayName(Map<String, Object> profile, UserEntity user) {
        Object raw = profile.get("displayName_s");
        if (raw != null && !raw.toString().isBlank()) return raw.toString().trim();
        if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) return user.getDisplayName();
        return user.getUsername();
    }

    /** 会员组：绑定到本人主站点（或未限定站点）且带会员等级的角色。 */
    private List<MeResponse.MemberGroup> memberGroupsOf(UserEntity user) {
        List<MeResponse.MemberGroup> groups = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        UUID home = user.getHomeSiteId();
        for (SiteCapabilityEvaluator.SiteRoleView view : capabilities.siteRoles(user.getId())) {
            if (view.siteId() != null && home != null && !home.equals(view.siteId())) continue;
            RoleEntity role = roleRepo.findById(view.roleId()).orElse(null);
            if (role == null || role.getTierLevel() == null) continue;
            if (!seen.add(view.roleCode())) continue;
            String name = role.getName() == null || role.getName().isBlank() ? view.roleCode() : role.getName();
            groups.add(new MeResponse.MemberGroup(view.roleCode(), name));
        }
        groups.sort(Comparator.comparing(MeResponse.MemberGroup::name, String.CASE_INSENSITIVE_ORDER));
        return groups;
    }

    @Override
    public java.util.Map<String, Object> getUserProfile(UUID userId, UUID operatorId) {
        UserEntity user = requireUser(userId);
        requireProfileAccess(user, operatorId);
        return profileService.readProfile(user.getProfileContentId());
    }

    @Override
    public java.util.Map<String, Object> updateUserProfile(UUID userId,
                                                           java.util.Map<String, Object> fields,
                                                           UUID operatorId) {
        UserEntity user = requireUser(userId);
        requireProfileAccess(user, operatorId);
        if (fields.containsKey("kind") || fields.containsKey("status") || fields.containsKey("username")) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED,
                "身份核心字段（kind/status/username）不得经资料接口修改（spec 5.2）");
        }
        profileService.updateProfile(user, fields, actor(operatorId));
        if (user.getProfileContentId() != null) {
            userRepo.save(user);
        }
        audit.write(actor(operatorId), "USER_PROFILE_UPDATE", "User", userId.toString(),
            user.getHomeSiteId(), "SUCCESS", null);
        return profileService.readProfile(user.getProfileContentId());
    }

    /**
     * spec 9.1：本人、system-admin，或（仅前台会员）本站 site-admin 可读写资料。
     * 读取场景（operatorId=null）允许本人或任意系统管理员，其余由调用方另行约束。
     */
    private void requireProfileAccess(UserEntity user, UUID operatorId) {
        if (operatorId == null) {
            // 读取：本人或 system-admin
            return;
        }
        boolean self = user.getId().equals(operatorId);
        boolean admin = capabilities.isSystemAdmin(operatorId);
        boolean siteAdmin = user.getHomeSiteId() != null
            && capabilities.has(operatorId, user.getHomeSiteId(), SiteCapabilities.ADMIN);
        if (!self && !admin && !siteAdmin) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "无权访问该用户资料");
        }
    }

    @Override
    public UUID ensureOrganization(String name) {
        UUID orgId = currentOrganizationId(name);
        ensureBuiltInRoles(orgId);
        return orgId;
    }

    /**
     * 当前组织 id（DEF-CFG-01）：走 {@link OrganizationRepository#findAllIds()} 这一**唯一口径**，
     * 与 {@code OrganizationSettingsService.defaultOrganizationId()} 同源，保证「写组织设置的组织」
     * 与「读组织设置的组织」永远是同一个。
     *
     * <p>创建侧是**双重检查 + 临界区**：两个并发创建者（{@code IdentityBootstrap} 的
     * ApplicationReadyEvent 与 {@code OrganizationContextFilter} 的首个请求）会同时看到空表，
     * 域 11 实测因此产生两份同名「默认组织」（createdAt 相差 8ms）。锁内的复查与插入在一个
     * REQUIRES_NEW 事务里完成并**在持锁期间提交**，后来者复查即可见，从而只有一个创建者。
     */
    private UUID currentOrganizationId(String fallbackName) {
        List<UUID> existing = orgRepo.findAllIds();
        if (!existing.isEmpty()) return existing.get(0);
        synchronized (ORGANIZATION_CREATION_LOCK) {
            return organizationCreationTx.execute(status -> {
                List<UUID> rechecked = orgRepo.findAllIds();
                if (!rechecked.isEmpty()) return rechecked.get(0);
                OrganizationEntity org = new OrganizationEntity();
                org.setName(fallbackName);
                return orgRepo.saveAndFlush(org).getId();
            });
        }
    }

    @Override
    public void ensureBuiltInRoles(UUID organizationId) {
        // 与 currentOrganizationId 同口径的「双重检查 + 临界区」：新库首启时
        // IdentityBootstrap（ApplicationReadyEvent）与首个请求的 OrganizationContextFilter
        // 会并发进入空表分支（域 11 同款竞态），锁内逐个复查+插入保证同进程只有一个创建者；
        // 跨进程（多实例同时首启）由 DataIntegrityViolationException 捕获后复查兜底。
        // 必须 saveAndFlush：违规在 flush 时立即抛出才可捕获（无外层事务时各自独立提交）。
        synchronized (ORGANIZATION_CREATION_LOCK) {
            for (BuiltInRoleCatalog.Seed seed : BuiltInRoleCatalog.seeds()) {
                RoleEntity existing = roleRepo.findByOrganizationIdAndCode(organizationId, seed.code()).orElse(null);
                if (existing == null) {
                    try {
                        roleRepo.saveAndFlush(RoleEntity.create(organizationId, seed.code(), seed.name(),
                            seed.description(), true, seed.scope(), null));
                        continue;
                    } catch (DataIntegrityViolationException ex) {
                        existing = roleRepo.findByOrganizationIdAndCode(organizationId, seed.code())
                            .orElseThrow(() -> ex);
                    }
                }
                existing.setBuiltIn(true);
                existing.setScope(seed.scope());
                if (existing.getName() == null || existing.getName().isBlank()) {
                    existing.setName(seed.name());
                }
                roleRepo.save(existing);
            }
        }
    }

    @Override
    public void ensureBackendLayouts() {
        List<RoleEntity> allRoles = roleRepo.findAll();
        for (BackendLayoutCatalog.Seed seed : BackendLayoutCatalog.seeds()) {
            if (layoutRepo.findByCode(seed.code()).isEmpty()) {
                layoutRepo.save(BackendLayoutEntity.of(seed.code(), seed.name()));
            }
        }
        for (RoleEntity role : allRoles) {
            if (!role.isBuiltIn() && !BuiltInRoleCatalog.isBuiltIn(role.getCode())) {
                continue;
            }
            String code = BuiltInRoleCatalog.normalize(role.getCode());
            if (BuiltInRoleCatalog.FRONT_END_USER.equals(code)
                || BuiltInRoleCatalog.ANONYMOUS.equals(code)
                || BuiltInRoleCatalog.OWNER.equals(code)) {
                continue; // 通道/伪角色禁止绑定布局
            }
            for (String layoutCode : BackendLayoutCatalog.layoutsForRole(code)) {
                layoutRepo.findByCode(layoutCode).ifPresent(layout -> {
                    if (!roleLayoutRepo.existsByRoleIdAndLayoutId(role.getId(), layout.getId())) {
                        roleLayoutRepo.save(RoleLayoutEntity.of(role.getId(), layout.getId()));
                    }
                });
            }
        }
    }

    @Override
    public boolean hasBackendRole(UUID userId) {
        UserEntity user = userId == null ? null : userRepo.findById(userId).orElse(null);
        if (user == null || !UserEntity.KIND_BACKEND.equals(user.getKind())) {
            return false;
        }
        return capabilities.organizationRoleCodes(userId).contains(BuiltInRoleCatalog.BACK_END_USER);
    }

    @Override
    public boolean hasBackendAccess(UUID userId) {
        return hasBackendRole(userId) && !layoutsForUser(userId).isEmpty();
    }

    @Override
    public boolean hasFrontendAccess(UUID userId) {
        return userId != null && capabilities.organizationRoleCodes(userId).contains(BuiltInRoleCatalog.FRONT_END_USER);
    }

    @Override
    public List<String> layoutsForUser(UUID userId) {
        if (userId == null) return List.of();
        List<UUID> roleIds = userRoleRepo.findByUserId(userId).stream()
            .map(UserRoleEntity::getRoleId)
            .distinct()
            .toList();
        if (roleIds.isEmpty()) return List.of();
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        for (RoleLayoutEntity binding : roleLayoutRepo.findByRoleIdIn(roleIds)) {
            layoutRepo.findById(binding.getLayoutId())
                .ifPresent(l -> codes.add(l.getCode()));
        }
        return List.copyOf(codes);
    }

    @Transactional
    public RoleEntity bootstrapRole(UUID organizationId, String code, String name, String desc) {
        ensureBuiltInRoles(organizationId);
        return roleRepo.findByOrganizationIdAndCode(organizationId, code)
            .orElseGet(() -> roleRepo.save(RoleEntity.create(organizationId, code, name, desc, true,
                BuiltInRoleCatalog.scope(code), null)));
    }

    @Transactional
    public UserEntity bootstrapAdmin(UUID organizationId, String username, String password, UUID roleId) {
        UserEntity user = new UserEntity();
        user.setOrganizationId(organizationId);
        user.setUsername(username);
        user.setDisplayName("系统管理员");
        user.setKind(UserEntity.KIND_BACKEND);
        applyNewPassword(user, password);
        user = userRepo.save(user);
        rememberPassword(user.getId(), user.getPasswordHash());
        userRoleRepo.save(UserRoleEntity.of(user.getId(), roleId, null));
        grantChannelRoles(user, UserEntity.KIND_BACKEND);
        profileService.ensureProfile(user);
        return user;
    }

    @Override
    public void recordSuccessfulLogin(UUID userId, String ip, String loginMode) {
        UserEntity user = requireUser(userId);
        user.setLastLoginAt(Instant.now());
        if (loginMode != null && !loginMode.isBlank()) {
            user.setLastLoginMode(loginMode);
        }
        if (ip != null && !ip.isBlank()) {
            user.setLastLoginIp(ip.length() > 64 ? ip.substring(0, 64) : ip);
        }
        if (user.getFailedLogins() != 0) user.setFailedLogins(0);
        userRepo.save(user);
    }

    OrganizationEntity getOrganization(UUID id) {
        return orgRepo.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "组织不存在"));
    }

    UserEntity requireUser(UUID id) {
        return userRepo.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "用户不存在"));
    }

    private UserResponse toResponse(UserEntity user) {
        List<UserRoleEntity> bindings = userRoleRepo.findByUserId(user.getId());
        List<UserResponse.RoleAssignment> roles = bindings.stream().map(b -> {
            RoleEntity r = roleRepo.findById(b.getRoleId()).orElse(null);
            return new UserResponse.RoleAssignment(b.getRoleId(),
                r != null ? r.getCode() : "?", r != null ? r.getName() : "?", b.getSiteId());
        }).toList();
        return UserResponse.from(user, roles);
    }

    private RoleResponse toRoleResponse(RoleEntity role) {
        List<String> caps = role.isBuiltIn() || BuiltInRoleCatalog.isBuiltIn(role.getCode())
            ? new ArrayList<>(BuiltInRoleCatalog.capabilities(role.getCode()))
            : SiteCapabilities.parse(role.getCapabilitiesJson());
        List<String> layouts = roleLayoutRepo.findByRoleId(role.getId()).stream()
            .map(binding -> layoutRepo.findById(binding.getLayoutId()).orElse(null))
            .filter(java.util.Objects::nonNull)
            .map(BackendLayoutEntity::getCode)
            .sorted()
            .toList();
        return RoleResponse.from(role, caps, layouts);
    }

    private static SiteAuthorizationPolicyResponse toPolicy(SiteAuthorizationPolicyEntity policy) {
        return new SiteAuthorizationPolicyResponse(policy.getSiteId(), policy.isPeerReviewRequired(),
            policy.isReviewerCanWrite(), policy.isPublisherCanWrite(), policy.isAgentDirectPublish(),
            policy.isMemberRegistrationReview());
    }

    private RoleEntity resolveRole(UUID organizationId, AssignRoleRequest req) {
        if (req.roleId() != null) {
            RoleEntity role = roleRepo.findById(req.roleId())
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "角色不存在"));
            if (!organizationId.equals(role.getOrganizationId())) {
                throw new BusinessException(ErrorCodes.FORBIDDEN, "角色不属于当前组织");
            }
            return role;
        }
        if (req.roleCode() == null || req.roleCode().isBlank()) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "roleId 或 roleCode 必填");
        }
        String code = BuiltInRoleCatalog.normalize(req.roleCode());
        return roleRepo.findByOrganizationIdAndCode(organizationId, code)
            .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "角色不存在"));
    }

    private void validateBindingScope(RoleEntity role, UUID siteId) {
        String code = BuiltInRoleCatalog.normalize(role.getCode());
        boolean orgRole = BuiltInRoleCatalog.SCOPE_ORG.equals(role.getScope())
            || BuiltInRoleCatalog.isOrgScope(code);
        if (orgRole && siteId != null) {
            throw new BusinessException(ErrorCodes.ROLE_SCOPE_INVALID, "组织角色不得绑定站点");
        }
        if (!orgRole && siteId == null) {
            throw new BusinessException(ErrorCodes.ROLE_SCOPE_INVALID, "站点角色必须指定 siteId");
        }
    }

    private static Integer memberTierLevel(Integer tierLevel) {
        if (tierLevel == null) return null;
        if (tierLevel < 0) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "会员等级不能为负数");
        }
        return tierLevel;
    }

    /** R3：会员档没有站点能力，也不进入后台布局。 */
    private static void rejectMemberTierPrivileges(Integer tierLevel, List<String> capabilities, List<String> layouts) {
        if (tierLevel == null) return;
        if (capabilities != null && !capabilities.isEmpty()) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "会员档角色不能授予站点能力");
        }
        if (layouts != null && layouts.stream().anyMatch(c -> c != null && !c.isBlank())) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "会员档角色不能关联后台布局");
        }
    }

    /** spec 3.6：kind=FRONTEND 禁止授予 back-end-user / 组织管理角色，返回 409。 */
    private void rejectFrontendBackendMix(UserEntity user, RoleEntity role) {
        if (!UserEntity.KIND_FRONTEND.equals(user.getKind())) {
            return;
        }
        String code = BuiltInRoleCatalog.normalize(role.getCode());
        boolean backendChannel = BuiltInRoleCatalog.BACK_END_USER.equals(code);
        boolean orgManagement = BuiltInRoleCatalog.SYSTEM_ADMIN.equals(code)
            || BuiltInRoleCatalog.MIGRATION_ADMIN.equals(code)
            || BuiltInRoleCatalog.MIGRATION_AUDITOR.equals(code);
        if (backendChannel || orgManagement) {
            throw new BusinessException(ErrorCodes.BACKEND_ROLE_FORBIDDEN,
                "前台会员不得授予后台登录或组织管理角色");
        }
    }

    /** spec 5.3：后台员工授予 front-end-user + back-end-user；前台会员仅 front-end-user。 */
    private void grantChannelRoles(UserEntity user, String kind) {
        UUID orgId = user.getOrganizationId();
        ensureBuiltInRoles(orgId);
        if (UserEntity.KIND_FRONTEND.equals(kind)) {
            bindOrgRoleIfAbsent(user.getId(), orgId, BuiltInRoleCatalog.FRONT_END_USER);
            return;
        }
        bindOrgRoleIfAbsent(user.getId(), orgId, BuiltInRoleCatalog.FRONT_END_USER);
        bindOrgRoleIfAbsent(user.getId(), orgId, BuiltInRoleCatalog.BACK_END_USER);
    }

    private void bindOrgRoleIfAbsent(UUID userId, UUID orgId, String roleCode) {
        roleRepo.findByOrganizationIdAndCode(orgId, roleCode).ifPresent(role -> {
            if (userRoleRepo.findBinding(userId, role.getId(), null).isEmpty()) {
                userRoleRepo.save(UserRoleEntity.of(userId, role.getId(), null));
            }
        });
    }

    private static String normalizeKind(String kind) {
        if (kind == null || kind.isBlank()) return UserEntity.KIND_BACKEND;
        if (UserEntity.KIND_FRONTEND.equalsIgnoreCase(kind.trim())) return UserEntity.KIND_FRONTEND;
        return UserEntity.KIND_BACKEND;
    }

    private void authorizeBindingChange(UUID operatorId, UUID siteId, RoleEntity role) {
        if (capabilities.isSystemAdmin(operatorId)) return;
        String code = BuiltInRoleCatalog.normalize(role.getCode());
        if (BuiltInRoleCatalog.isOrgScope(code) || BuiltInRoleCatalog.SCOPE_ORG.equals(role.getScope())) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "仅系统管理员可分配组织角色");
        }
        if (siteId == null || !capabilities.has(operatorId, siteId, SiteCapabilities.ADMIN)) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "只能管理本站点成员");
        }
    }

    private long countActiveSystemAdmins(UUID orgId) {
        return userRoleRepo.countActiveWithOrgRole(orgId, UserEntity.STATUS_ACTIVE, ROLE_SYS_ADMIN);
    }

    private String actor(UUID operatorId) {
        if (operatorId == null) return "system";
        return userRepo.findById(operatorId).map(UserEntity::getUsername).orElse(operatorId.toString());
    }

    private void applyNewPassword(UserEntity user, String rawPassword) {
        validatePassword(rawPassword);
        rejectReusedPassword(user, rawPassword);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setPasswordChangedAt(Instant.now());
    }

    private void rejectReusedPassword(UserEntity user, String rawPassword) {
        int n = Math.max(0, properties.getPasswordHistorySize());
        if (n <= 0) return;
        if (user.getPasswordHash() != null && passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "不能使用最近使用过的密码");
        }
        if (user.getId() == null) return;
        List<UserPasswordHistoryEntity> history = passwordHistory.findByUserIdOrderByCreatedAtDesc(user.getId());
        int limit = Math.min(n, history.size());
        for (int i = 0; i < limit; i++) {
            if (passwordEncoder.matches(rawPassword, history.get(i).getPasswordHash())) {
                throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "不能使用最近使用过的密码");
            }
        }
    }

    private void rememberPassword(UUID userId, String hash) {
        passwordHistory.save(UserPasswordHistoryEntity.of(userId, hash));
        int keep = Math.max(1, properties.getPasswordHistorySize());
        List<UserPasswordHistoryEntity> all = passwordHistory.findByUserIdOrderByCreatedAtDesc(userId);
        if (all.size() > keep) {
            passwordHistory.deleteAll(all.subList(keep, all.size()));
        }
    }

    /** 密码策略（spec 5.6）：长度/复杂度可配置，来自 {@link IdentityProperties}。 */
    void validatePassword(String password) {
        int minLength = Math.max(1, properties.getPasswordMinLength());
        if (password == null || password.length() < minLength) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "密码至少 " + minLength + " 位");
        }
        boolean upper = false, lower = false, digit = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isUpperCase(c)) upper = true;
            else if (Character.isLowerCase(c)) lower = true;
            else if (Character.isDigit(c)) digit = true;
        }
        List<String> missing = new ArrayList<>();
        if (properties.isPasswordRequireUpper() && !upper) missing.add("大写字母");
        if (properties.isPasswordRequireLower() && !lower) missing.add("小写字母");
        if (properties.isPasswordRequireDigit() && !digit) missing.add("数字");
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "密码须包含" + String.join("、", missing));
        }
    }
}
