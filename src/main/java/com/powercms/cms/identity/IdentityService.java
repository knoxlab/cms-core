package com.powercms.cms.identity;

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
import com.powercms.common.error.BusinessException;
import org.springframework.security.core.AuthenticationException;

import java.util.List;
import java.util.UUID;

/**
 * 身份管理服务接口：仅暴露方法签名，实现不跨模块泄漏 JPA 实体。
 */
public interface IdentityService {

    /**
     * 登录失败稳定消息键（FR-IAM-002）：资源包 {@code i18n/messages*.properties}，
     * 由 Web 层按键 + 参数本地化（见 {@code ApiExceptionHandler#resolveMessage}）。
     */
    String MSG_LOGIN_REMAINING = "error.auth.login.remaining";
    String MSG_LOGIN_LOCKED = "error.auth.login.locked";
    String MSG_LOGIN_DISABLED = "error.auth.login.disabled";
    String MSG_LOGIN_PENDING = "error.auth.login.pending";
    String MSG_LOGIN_EXPIRED = "error.auth.login.expired";

    UserResponse createUser(UUID organizationId, CreateUserRequest req);
    UserResponse getUser(UUID userId);
    List<UserResponse> listUsers(UUID organizationId);
    void disableUser(UUID userId, UUID operatorId);
    /** spec 5.3：禁用前迁移引用；reassignTo 为空且用户仍有 owner/受理人引用 → USER_REFERENCE_CONFLICT。 */
    void disableUser(UUID userId, UUID reassignTo, UUID operatorId);
    /** spec 5.3：用户引用摘要（内容 owner 数 / 工作流待办数）。 */
    java.util.Map<String, Object> getUserReferences(UUID userId);
    void unlockUser(UUID userId, UUID operatorId);
    void changePassword(UUID userId, ChangePasswordRequest req, UUID operatorId);

    RoleResponse createRole(UUID organizationId, CreateRoleRequest req);
    List<RoleResponse> listRoles(UUID organizationId);
    void deleteRole(UUID roleId);
    RoleResponse updateRoleCapabilities(UUID roleId, List<String> capabilities);
    /** spec 9.2：绑定角色后台布局；front-end-user / 伪角色拒绝。 */
    RoleResponse updateRoleLayouts(UUID roleId, List<String> layoutCodes);

    void assignRole(UUID userId, AssignRoleRequest req, UUID operatorId);
    void revokeRole(UUID userId, UUID roleId, UUID siteId, UUID operatorId);
    void bindInitialSiteAdmin(UUID userId, UUID siteId);

    List<SiteMemberResponse> listSiteMembers(UUID siteId);
    /** spec 9.1.1：站点前台会员列表（kind=FRONTEND）。 */
    List<SiteMemberResponse> listFrontendUsers(UUID siteId);
    /** spec 9.1.1：创建前台会员（kind=FRONTEND、homeSiteId=站点）。 */
    UserResponse createFrontendUser(UUID siteId, CreateUserRequest req, UUID operatorId);

    /** spec 9.1.1：会员详情（含资料字段）；该站 admin、system-admin 或本人。 */
    java.util.Map<String, Object> getFrontendUser(UUID siteId, UUID userId, UUID operatorId);

    /** spec 9.1.1：禁用会员；该站 admin 或 system-admin。 */
    void disableFrontendUser(UUID siteId, UUID userId, UUID operatorId);
    /** spec 5.3：禁用前迁移引用；reassignTo 为空且会员仍有引用 → USER_REFERENCE_CONFLICT。 */
    void disableFrontendUser(UUID siteId, UUID userId, UUID reassignTo, UUID operatorId);
    void addSiteMember(UUID siteId, String username, String roleCode, UUID operatorId);
    SiteAuthorizationPolicyResponse getAuthorizationPolicy(UUID siteId);
    SiteAuthorizationPolicyResponse updateAuthorizationPolicy(UUID siteId, UpdateSiteAuthorizationPolicyRequest req);
    MeResponse currentUser(UUID userId, String loginMode);

    /** spec 9.1：读取用户资料内容字段（system.user-profile / system.frontend-user）。 */
    java.util.Map<String, Object> getUserProfile(UUID userId, UUID operatorId);

    /** spec 9.1：更新用户资料内容字段，email 双向同步 sys_user。 */
    java.util.Map<String, Object> updateUserProfile(UUID userId, java.util.Map<String, Object> fields,
                                                    UUID operatorId);

    void recordSuccessfulLogin(UUID userId, String ip, String loginMode);

    /** 后台登录资格：kind=BACKEND 且绑定 back-end-user（不含布局校验，spec 5.4）。 */
    boolean hasBackendRole(UUID userId);

    /** 后台登录资格：kind=BACKEND 且绑定 back-end-user 且至少一块布局（spec 5.4）。 */
    boolean hasBackendAccess(UUID userId);

    /** 前台登录资格：绑定 front-end-user（spec 5.4）。 */
    boolean hasFrontendAccess(UUID userId);

    /** 用户全部角色布局并集（spec 4.8）。 */
    List<String> layoutsForUser(UUID userId);

    UUID ensureOrganization(String name);
    void ensureBuiltInRoles(UUID organizationId);
    void ensureBackendLayouts();

    /** 站点是否要求自助注册先审核。无策略行时为直接通过。 */
    boolean memberRegistrationRequiresReview(UUID siteId);

    /** 自助注册提交后改为待审核。 */
    void holdFrontendUserForReview(UUID userId);

    /** 后台通过待审核会员。 */
    void approveFrontendUser(UUID siteId, UUID userId, UUID operatorId);

    /**
     * spec 9.1.1：解锁会员（恢复 ACTIVE 并清零登录失败计数）；该站 admin 或 system-admin。
     * 待审核（PENDING）会员不走解锁，须由 {@link #approveFrontendUser} 通过。
     */
    void unlockFrontendUser(UUID siteId, UUID userId, UUID operatorId);

    /** 待审核账号的登录提示；可登录时返回 null。 */
    String frontendLoginBlockReason(String username);

    /**
     * 登录失败对外异常（FR-IAM-002）：
     * <ul>
     *   <li>凭据错误且账号存在 → 稳定键 {@code error.auth.login.remaining}，附剩余尝试次数
     *       （{@code maxFailedLogins - failedLogins}；失败计数已由
     *       {@code IdentityAuthenticationEvents} 在认证事件里先落库）；</li>
     *   <li>账号状态异常 → 对应原因键：LOCKED / DISABLED / PENDING、密码过期；</li>
     *   <li>用户不存在等其余情况 → 回落「用户名或密码错误」通用文案。</li>
     * </ul>
     *
     * @param username 登录标识（USERNAME 模式为用户名，EMAIL 模式为邮箱）
     * @param cause    认证链抛出的失败原因
     */
    BusinessException loginFailure(String username, AuthenticationException cause);
}
