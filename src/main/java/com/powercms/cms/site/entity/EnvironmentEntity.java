package com.powercms.cms.site.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

/**
 * 环境（FR-SITE-002）：preview / staging / production。
 * 每个站点默认创建 preview 环境。
 */
@Entity
@Table(name = "site_environment", uniqueConstraints = @UniqueConstraint(columnNames = { "site_id", "name" }))
public class EnvironmentEntity {

    public static final String TYPE_PREVIEW = "PREVIEW";
    public static final String TYPE_STAGING = "STAGING";
    public static final String TYPE_PRODUCTION = "PRODUCTION";

    @Id
    private UUID id;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false, length = 20)
    private String type;

    public static EnvironmentEntity create(UUID siteId, String name, String type) {
        EnvironmentEntity e = new EnvironmentEntity();
        e.id = UUID.randomUUID();
        e.siteId = siteId;
        e.name = name;
        e.type = type;
        return e;
    }

    public UUID getId() { return id; }
    public UUID getSiteId() { return siteId; }
    public void setSiteId(UUID siteId) { this.siteId = siteId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
}
