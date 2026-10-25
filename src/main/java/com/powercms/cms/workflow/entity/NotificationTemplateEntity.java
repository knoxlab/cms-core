package com.powercms.cms.workflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_template")
public class NotificationTemplateEntity {

    @Id private UUID id;
    @Column(name = "site_id")
    private UUID siteId;
    @Column(name = "template_key", nullable = false, length = 64)
    private String templateKey;
    @Column(nullable = false, length = 300)
    private String subject;
    @Column(name = "body_html", nullable = false, length = 8000)
    private String bodyHtml;
    @Column(name = "body_text", length = 4000)
    private String bodyText;
    @Column(name = "updated_by", length = 100)
    private String updatedBy;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UUID getId() { return id; } public void setId(UUID id) { this.id = id; }
    public UUID getSiteId() { return siteId; } public void setSiteId(UUID siteId) { this.siteId = siteId; }
    public String getTemplateKey() { return templateKey; } public void setTemplateKey(String templateKey) { this.templateKey = templateKey; }
    public String getSubject() { return subject; } public void setSubject(String subject) { this.subject = subject; }
    public String getBodyHtml() { return bodyHtml; } public void setBodyHtml(String bodyHtml) { this.bodyHtml = bodyHtml; }
    public String getBodyText() { return bodyText; } public void setBodyText(String bodyText) { this.bodyText = bodyText; }
    public String getUpdatedBy() { return updatedBy; } public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; } public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
