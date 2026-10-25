package com.powercms.cms.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * 后台工具布局（spec 4.8 / 8.7）：内置工具编入布局，角色绑定布局后获得工具可见性。
 */
@Entity
@Table(name = "sys_layout")
public class BackendLayoutEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 60)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    public static BackendLayoutEntity of(String code, String name) {
        BackendLayoutEntity l = new BackendLayoutEntity();
        l.id = UUID.randomUUID();
        l.code = code;
        l.name = name;
        return l;
    }

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
