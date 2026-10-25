package com.powercms.cms.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

/**
 * 用户角色绑定：siteId 为空表示组织级角色（FR-IAM-004 站点级最小权限）。
 */
@Entity
@Table(name = "sys_user_role", uniqueConstraints = @UniqueConstraint(columnNames = { "user_id", "role_id", "site_id" }))
public class UserRoleEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "role_id", nullable = false)
    private UUID roleId;

    @Column(name = "site_id")
    private UUID siteId;

    public static UserRoleEntity of(UUID userId, UUID roleId, UUID siteId) {
        UserRoleEntity ur = new UserRoleEntity();
        ur.id = UUID.randomUUID();
        ur.userId = userId;
        ur.roleId = roleId;
        ur.siteId = siteId;
        return ur;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getRoleId() { return roleId; }
    public UUID getSiteId() { return siteId; }
}
