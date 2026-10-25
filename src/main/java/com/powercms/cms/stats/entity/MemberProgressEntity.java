package com.powercms.cms.stats.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 会员学习进度（FR-STAT-037~039）：唯一键 (site_id, member_id, content_id)，幂等 upsert，
 * watched_ms_total 累加必须数据库侧原子完成（FR-STAT-102）。永久保留，注销按 FR-STAT-096 删除。
 */
@Entity
@Table(name = "member_progress")
public class MemberProgressEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    @Column(name = "member_id", nullable = false)
    private UUID memberId;

    @Column(name = "content_id", nullable = false)
    private UUID contentId;

    @Column(name = "content_type_id")
    private UUID contentTypeId;

    @Column(name = "folder_id")
    private UUID folderId;

    @Column(name = "farthest_ms", nullable = false)
    private long farthestMs;

    @Column(name = "resume_ms", nullable = false)
    private long resumeMs;

    @Column(name = "watched_ms_total", nullable = false)
    private long watchedMsTotal;

    @Column(name = "completed", nullable = false)
    private boolean completed;

    @Column(name = "last_watched_at")
    private Instant lastWatchedAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getSiteId() { return siteId; }
    public UUID getMemberId() { return memberId; }
    public UUID getContentId() { return contentId; }
    public UUID getContentTypeId() { return contentTypeId; }
    public void setContentTypeId(UUID v) { this.contentTypeId = v; }
    public UUID getFolderId() { return folderId; }
    public void setFolderId(UUID v) { this.folderId = v; }
    public long getFarthestMs() { return farthestMs; }
    public void setFarthestMs(long v) { this.farthestMs = v; }
    public long getResumeMs() { return resumeMs; }
    public void setResumeMs(long v) { this.resumeMs = v; }
    public long getWatchedMsTotal() { return watchedMsTotal; }
    public void setWatchedMsTotal(long v) { this.watchedMsTotal = v; }
    public boolean isCompleted() { return completed; }
    public void setCompleted(boolean v) { this.completed = v; }
    public Instant getLastWatchedAt() { return lastWatchedAt; }
    public void setLastWatchedAt(Instant v) { this.lastWatchedAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
