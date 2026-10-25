package com.powercms.cms.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

/**
 * 角色 ↔ 后台布局绑定（spec 8.7）。front-end-user 禁止绑定任何布局。
 */
@Entity
@Table(name = "sys_role_layout", uniqueConstraints = @UniqueConstraint(columnNames = { "role_id", "layout_id" }))
public class RoleLayoutEntity {

    @Id
    private UUID id;

    @Column(name = "role_id", nullable = false)
    private UUID roleId;

    @Column(name = "layout_id", nullable = false)
    private UUID layoutId;

    public static RoleLayoutEntity of(UUID roleId, UUID layoutId) {
        RoleLayoutEntity b = new RoleLayoutEntity();
        b.id = UUID.randomUUID();
        b.roleId = roleId;
        b.layoutId = layoutId;
        return b;
    }

    public UUID getId() { return id; }
    public UUID getRoleId() { return roleId; }
    public UUID getLayoutId() { return layoutId; }
}
