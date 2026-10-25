package com.powercms.cms.media.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "media_asset")
public class MediaAssetEntity {

    @Id private UUID id;
    @Column(name = "site_id", nullable = false)
    private UUID siteId;
    @Column(length = 500) private String title;
    @Column(name = "alt_text", length = 500) private String altText;
    /** Alt 文本 AI 产物标识（GOV-007）：AI 生成时写模型标识，人工/Agent 修订后清空（即人工修订标记）。 */
    @Column(name = "alt_text_model", length = 100) private String altTextModel;
    @Column(nullable = false, length = 500) private String filename;
    @Column(name = "content_hash", nullable = false, length = 128)
    private String contentHash;
    @Column(name = "mime_type", length = 100) private String mimeType;
    @Column(name = "file_size", nullable = false) private long fileSize;
    @Column(length = 500) private String copyright;
    @Column(length = 1000) private String tags;
    @Column(nullable = false, length = 20) private String status = "ACTIVE";
    @Column(name = "current_version", nullable = false) private int currentVersion;
    /** 资产来源：KNOX / LOCAL / EXTERNAL（V41）。 */
    @Column(nullable = false, length = 20) private String source = "KNOX";
    /** 共享库场景下的上传来源站点（V41）；缺省等于 siteId。 */
    @Column(name = "owner_site_id")
    private UUID ownerSiteId;
    /** 库内目录组织（V41）。 */
    @Column(name = "folder_path", length = 500)
    private String folderPath;
    /** 归类的媒体内容类型（预置 media_video 等，FR-KNX-015）。 */
    @Column(name = "media_type_id")
    private UUID mediaTypeId;
    /** 媒体种类：video/audio/image/document/font/other（自动归类驱动，FR-CM-MEDIA-043）。 */
    @Column(name = "kind", length = 20)
    private String kind;
    /** 处理产物结构化元数据 JSON（驱动媒体搜索索引，FR-KNX-024）。 */
    @Column(name = "metadata", columnDefinition = "text")
    private String metadata;
    /** 同步生成的 MEDIA 内容项（FR-CM-MEDIA-010/011）：媒体作为内容实例参与内容检索。 */
    @Column(name = "content_item_id")
    private UUID contentItemId;
    /** 资产可引用有效窗口起（FR-AI-SRC-002）；null = 不限。 */
    @Column(name = "valid_from")
    private Instant validFrom;
    /** 资产可引用有效窗口止（FR-AI-SRC-002）；null = 不限。过期资产不进入 AI 引用。 */
    @Column(name = "valid_until")
    private Instant validUntil;
    /** 转写稿全文（FR-AI-MEDIA-001）：Knox 字幕 cues 拼接，AI 产物可编辑，参与媒体语义检索。 */
    @Column(name = "transcript", columnDefinition = "text")
    private String transcript;
    /** 转写语种（FR-AI-MEDIA-001）：BCP-47 主子标签（zh/en）。 */
    @Column(name = "transcript_lang", length = 20)
    private String transcriptLang;
    /** 转写模型标识（FR-AI-MEDIA-001 + GOV-007）：如 knox-whisper；回写 MEDIA 内容项 ai_model。 */
    @Column(name = "transcript_model", length = 100)
    private String transcriptModel;
    /** 转写同步游标（FR-AI-MEDIA-001）：null = 从未同步。 */
    @Column(name = "transcript_synced_at")
    private Instant transcriptSyncedAt;
    /** 媒体内容摘要（FR-AI-MEDIA-002）：LLM 基于转写稿生成，可编辑。 */
    @Column(name = "summary", columnDefinition = "text")
    private String summary;
    /** 摘要生成模型（FR-AI-MEDIA-002 + GOV-007）：null = 无摘要或人工撰写。 */
    @Column(name = "summary_model", length = 100)
    private String summaryModel;
    /** 摘要生成时间（FR-AI-MEDIA-002）。 */
    @Column(name = "summary_synced_at")
    private Instant summarySyncedAt;
    /** 智能章节时间戳（FR-AI-MEDIA-002）：JSON 数组，格式 [{"time":0,"title":"Introduction"}, ...] */
    @Column(name = "chapters", columnDefinition = "text")
    private String chapters;
    /** 章节生成模型（FR-AI-MEDIA-002 + GOV-007） */
    @Column(name = "chapters_model", length = 100)
    private String chaptersModel;
    /** 章节生成时间（FR-AI-MEDIA-002） */
    @Column(name = "chapters_synced_at")
    private Instant chaptersSyncedAt;
    /**
     * 转写 cues 时间戳 JSON（V139 / FR-AI-KB-081 路径 B）：
     * {@code [{"start_ms":0,"end_ms":2500,"text":"..."}]}。
     *
     * <p><b>为什么必须单独存</b>：{@link #transcript} 是纯文本（cues 顺次拼接），
     * **不含时间戳**；而知识库分块需要 {@code time_start_ms/time_end_ms} 才能让引用
     * 跳转到视频的具体时间点。在既有同步链路里顺带落库（而非分块时回读 Knox），
     * 使分块**完全可离线重算**，与 ADR-16「CMS 必须持有正文」同理。
     */
    @Column(name = "transcript_cues", columnDefinition = "text")
    private String transcriptCues;
    /** cues 落库时间（与 transcript_synced_at 同步推进） */
    @Column(name = "transcript_cues_synced_at")
    private Instant transcriptCuesSyncedAt;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist void onCreate() { if (id == null) id = UUID.randomUUID(); Instant n = Instant.now(); createdAt = n; updatedAt = n; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    public UUID getId() { return id; } public void setId(UUID id) { this.id = id; }
    public UUID getSiteId() { return siteId; } public void setSiteId(UUID siteId) { this.siteId = siteId; }
    public String getTitle() { return title; } public void setTitle(String title) { this.title = title; }
    public String getAltText() { return altText; } public void setAltText(String altText) { this.altText = altText; }
    public String getAltTextModel() { return altTextModel; } public void setAltTextModel(String altTextModel) { this.altTextModel = altTextModel; }
    public String getFilename() { return filename; } public void setFilename(String filename) { this.filename = filename; }
    public String getContentHash() { return contentHash; } public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public String getMimeType() { return mimeType; } public void setMimeType(String mimeType) { this.mimeType = mimeType; }
    public long getFileSize() { return fileSize; } public void setFileSize(long fileSize) { this.fileSize = fileSize; }
    public String getCopyright() { return copyright; } public void setCopyright(String copyright) { this.copyright = copyright; }
    public String getTags() { return tags; } public void setTags(String tags) { this.tags = tags; }
    public String getStatus() { return status; } public void setStatus(String status) { this.status = status; }
    public int getCurrentVersion() { return currentVersion; } public void setCurrentVersion(int currentVersion) { this.currentVersion = currentVersion; }
    public String getSource() { return source; } public void setSource(String source) { this.source = source; }
    public UUID getOwnerSiteId() { return ownerSiteId; } public void setOwnerSiteId(UUID ownerSiteId) { this.ownerSiteId = ownerSiteId; }
    public String getFolderPath() { return folderPath; } public void setFolderPath(String folderPath) { this.folderPath = folderPath; }
    public UUID getMediaTypeId() { return mediaTypeId; } public void setMediaTypeId(UUID mediaTypeId) { this.mediaTypeId = mediaTypeId; }
    public String getKind() { return kind; } public void setKind(String kind) { this.kind = kind; }
    public String getMetadata() { return metadata; } public void setMetadata(String metadata) { this.metadata = metadata; }
    public UUID getContentItemId() { return contentItemId; } public void setContentItemId(UUID contentItemId) { this.contentItemId = contentItemId; }
    public Instant getValidFrom() { return validFrom; } public void setValidFrom(Instant validFrom) { this.validFrom = validFrom; }
    public Instant getValidUntil() { return validUntil; } public void setValidUntil(Instant validUntil) { this.validUntil = validUntil; }
    public String getTranscript() { return transcript; } public void setTranscript(String transcript) { this.transcript = transcript; }
    public String getTranscriptLang() { return transcriptLang; } public void setTranscriptLang(String transcriptLang) { this.transcriptLang = transcriptLang; }
    public String getTranscriptModel() { return transcriptModel; } public void setTranscriptModel(String transcriptModel) { this.transcriptModel = transcriptModel; }
    public Instant getTranscriptSyncedAt() { return transcriptSyncedAt; } public void setTranscriptSyncedAt(Instant transcriptSyncedAt) { this.transcriptSyncedAt = transcriptSyncedAt; }
    public String getSummary() { return summary; } public void setSummary(String summary) { this.summary = summary; }
    public String getSummaryModel() { return summaryModel; } public void setSummaryModel(String summaryModel) { this.summaryModel = summaryModel; }
    public Instant getSummarySyncedAt() { return summarySyncedAt; } public void setSummarySyncedAt(Instant summarySyncedAt) { this.summarySyncedAt = summarySyncedAt; }
    public String getChapters() { return chapters; } public void setChapters(String chapters) { this.chapters = chapters; }
    public String getChaptersModel() { return chaptersModel; } public void setChaptersModel(String chaptersModel) { this.chaptersModel = chaptersModel; }
    public Instant getChaptersSyncedAt() { return chaptersSyncedAt; } public void setChaptersSyncedAt(Instant chaptersSyncedAt) { this.chaptersSyncedAt = chaptersSyncedAt; }
    public String getTranscriptCues() { return transcriptCues; } public void setTranscriptCues(String transcriptCues) { this.transcriptCues = transcriptCues; }
    public Instant getTranscriptCuesSyncedAt() { return transcriptCuesSyncedAt; } public void setTranscriptCuesSyncedAt(Instant transcriptCuesSyncedAt) { this.transcriptCuesSyncedAt = transcriptCuesSyncedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
