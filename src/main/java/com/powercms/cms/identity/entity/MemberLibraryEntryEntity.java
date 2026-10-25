package com.powercms.cms.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/** 会员在站点内的收藏（FAVORITE）或浏览（VIEW）。 */
@Entity
@Table(name = "member_library_entry", uniqueConstraints = @UniqueConstraint(
    name = "uk_member_library",
    columnNames = {"site_id", "member_id", "content_id", "entry_type"}))
public class MemberLibraryEntryEntity {

    public static final String FAVORITE = "FAVORITE";
    public static final String VIEW = "VIEW";
    public static final String LIKE = "LIKE";

    @Id
    private UUID id;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    @Column(name = "member_id", nullable = false)
    private UUID memberId;

    @Column(name = "content_id", nullable = false)
    private UUID contentId;

    @Column(name = "entry_type", nullable = false, length = 20)
    private String entryType;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getSiteId() { return siteId; }
    public void setSiteId(UUID siteId) { this.siteId = siteId; }
    public UUID getMemberId() { return memberId; }
    public void setMemberId(UUID memberId) { this.memberId = memberId; }
    public UUID getContentId() { return contentId; }
    public void setContentId(UUID contentId) { this.contentId = contentId; }
    public String getEntryType() { return entryType; }
    public void setEntryType(String entryType) { this.entryType = entryType; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
