package com.powercms.cms.identity.permission.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * 对象 ACL 行（spec 8.6）：一条记录授予某主体（角色或个别用户）对某对象的若干权利。
 * {@code inherit} 为对象级标志，同对象所有行取值一致：false = 该对象打破继承，本表即其个别 ACL。
 */
@Entity
@Table(name = "object_acl",
    uniqueConstraints = @UniqueConstraint(name = "uq_object_acl",
        columnNames = {"object_type", "object_id", "principal_type", "principal_id"}))
public class ObjectAclEntity {

    public static final String PRINCIPAL_ROLE = "ROLE";
    public static final String PRINCIPAL_USER = "USER";

    @Id
    private UUID id;

    @Column(name = "site_id")
    private UUID siteId;

    @Column(name = "object_type", nullable = false, length = 30)
    private String objectType;

    @Column(name = "object_id", nullable = false)
    private UUID objectId;

    @Column(name = "principal_type", nullable = false, length = 10)
    private String principalType;

    @Column(name = "principal_id", nullable = false)
    private UUID principalId;

    /** JSON 字符串数组，如 ["view","edit"]（spec 8.6）。 */
    @Column(nullable = false, length = 1000)
    private String permissions;

    /** 对象级：该对象是否仍继承父级（同对象所有行一致）。 */
    @Column(nullable = false)
    private boolean inherit = true;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    public static ObjectAclEntity of(UUID siteId, String objectType, UUID objectId,
                                     String principalType, UUID principalId,
                                     String permissionsJson, boolean inherit, UUID createdBy) {
        ObjectAclEntity e = new ObjectAclEntity();
        e.siteId = siteId;
        e.objectType = objectType;
        e.objectId = objectId;
        e.principalType = principalType;
        e.principalId = principalId;
        e.permissions = permissionsJson;
        e.inherit = inherit;
        e.createdBy = createdBy;
        return e;
    }

    public UUID getId() { return id; }
    public UUID getSiteId() { return siteId; }
    public void setSiteId(UUID siteId) { this.siteId = siteId; }
    public String getObjectType() { return objectType; }
    public UUID getObjectId() { return objectId; }
    public String getPrincipalType() { return principalType; }
    public UUID getPrincipalId() { return principalId; }
    public String getPermissions() { return permissions; }
    public void setPermissions(String permissions) { this.permissions = permissions; }
    public boolean isInherit() { return inherit; }
    public void setInherit(boolean inherit) { this.inherit = inherit; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
