package com.powercms.cms.identity.repo;

import com.powercms.cms.identity.entity.UserRoleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRoleRepository extends JpaRepository<UserRoleEntity, UUID> {

    List<UserRoleEntity> findByUserId(UUID userId);

    List<UserRoleEntity> findByUserIdAndSiteId(UUID userId, UUID siteId);

    List<UserRoleEntity> findBySiteId(UUID siteId);

    /**
     * 角色的用户绑定（角色引用保护：非空时禁止删除角色并列出引用用户，见 TC-M02-009）。
     */
    List<UserRoleEntity> findByRoleId(UUID roleId);

    void deleteByUserId(UUID userId);

    void deleteByUserIdAndRoleIdAndSiteId(UUID userId, UUID roleId, UUID siteId);

    @Query("""
        select ur from UserRoleEntity ur
        where ur.userId = :userId and ur.roleId = :roleId
          and ((:siteId is null and ur.siteId is null) or ur.siteId = :siteId)
        """)
    Optional<UserRoleEntity> findBinding(@Param("userId") UUID userId,
                                         @Param("roleId") UUID roleId,
                                         @Param("siteId") UUID siteId);

    @Modifying
    @Query("""
        delete from UserRoleEntity ur
        where ur.userId = :userId and ur.roleId = :roleId
          and ((:siteId is null and ur.siteId is null) or ur.siteId = :siteId)
        """)
    int deleteBinding(@Param("userId") UUID userId,
                      @Param("roleId") UUID roleId,
                      @Param("siteId") UUID siteId);

    @Query("""
        select count(distinct u.id) from UserEntity u, UserRoleEntity ur, RoleEntity r
        where u.id = ur.userId and ur.roleId = r.id
          and u.organizationId = :orgId and u.status = :status
          and ur.siteId is null and r.code = :roleCode
        """)
    long countActiveWithOrgRole(@Param("orgId") UUID orgId,
                                @Param("status") String status,
                                @Param("roleCode") String roleCode);

    @Query("""
        select count(distinct u.id) from UserEntity u, UserRoleEntity ur, RoleEntity r
        where u.id = ur.userId and ur.roleId = r.id
          and u.status = 'ACTIVE' and ur.siteId = :siteId
          and lower(r.code) = 'site-admin'
        """)
    long countActiveSiteAdmins(@Param("siteId") UUID siteId);

    /**
     * 站点角色的在用用户名单（互动审核通知把「受理角色」展开成收件人，
     * 对应规范 {@code docs/workflow-notification-specification.md} §8.1 的
     * 「站点 reviewer 角色成员」解析；无绑定时返回空列表，由调用方记录
     * {@code NOTIFICATION_NO_RECIPIENT} 并跳过）。
     */
    @Query("""
        select u.username from UserEntity u, UserRoleEntity ur, RoleEntity r
        where u.id = ur.userId and ur.roleId = r.id
          and ur.siteId = :siteId
          and u.status = 'ACTIVE'
          and lower(r.code) = lower(:roleCode)
        order by u.username
        """)
    List<String> findActiveUsernamesBySiteRole(@Param("siteId") UUID siteId,
                                               @Param("roleCode") String roleCode);
}
