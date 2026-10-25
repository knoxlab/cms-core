package com.powercms.cms.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

/**
 * 角色：组织角色与站点角色共用定义，绑定时区分范围（FR-IAM-004）。
 * built_in 角色不可删除。
 */
@Entity
@Table(name = "sys_role", uniqueConstraints = @UniqueConstraint(columnNames = { "organization_id", "code" }))
public class RoleEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false, length = 100)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(name = "built_in", nullable = false)
    private boolean builtIn;

    @Column(nullable = false, length = 10)
    private String scope = "SITE";

    @Column(name = "capabilities", length = 1000)
    private String capabilitiesJson;

    /** 会员档排序/展示用。null 表示后台或站点角色，判定不比较大小。 */
    @Column(name = "tier_level")
    private Integer tierLevel;

    public static RoleEntity create(UUID organizationId, String code, String name, String description, boolean builtIn) {
        return create(organizationId, code, name, description, builtIn, "SITE", null);
    }

    public static RoleEntity create(UUID organizationId, String code, String name, String description,
                                    boolean builtIn, String scope, String capabilitiesJson) {
        RoleEntity r = new RoleEntity();
        r.id = UUID.randomUUID();
        r.organizationId = organizationId;
        r.code = code;
        r.name = name;
        r.description = description;
        r.builtIn = builtIn;
        r.scope = scope == null || scope.isBlank() ? "SITE" : scope;
        r.capabilitiesJson = capabilitiesJson;
        return r;
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public void setOrganizationId(UUID organizationId) { this.organizationId = organizationId; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public boolean isBuiltIn() { return builtIn; }
    public void setBuiltIn(boolean builtIn) { this.builtIn = builtIn; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getCapabilitiesJson() { return capabilitiesJson; }
    public void setCapabilitiesJson(String capabilitiesJson) { this.capabilitiesJson = capabilitiesJson; }
    public Integer getTierLevel() { return tierLevel; }
    public void setTierLevel(Integer tierLevel) { this.tierLevel = tierLevel; }
}
