package com.powercms.cms.publish.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "release_url_rewrite")
@IdClass(ReleaseUrlRewriteEntity.Key.class)
public class ReleaseUrlRewriteEntity {
    @Id @Column(name = "release_id") private UUID releaseId;
    @Id @Column(name = "rule_id") private UUID ruleId;
    @Column(nullable = false, length = 200) private String name;
    @Column(nullable = false, length = 512) private String pattern;
    @Column(nullable = false, length = 1000) private String replacement;
    @Column(nullable = false) private int priority;

    public static class Key implements Serializable {
        public UUID releaseId;
        public UUID ruleId;
        public Key() {}
        public Key(UUID releaseId, UUID ruleId) {
            this.releaseId = releaseId;
            this.ruleId = ruleId;
        }
        @Override public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(releaseId, k.releaseId) && Objects.equals(ruleId, k.ruleId);
        }
        @Override public int hashCode() { return Objects.hash(releaseId, ruleId); }
    }

    public UUID getReleaseId() { return releaseId; }
    public void setReleaseId(UUID releaseId) { this.releaseId = releaseId; }
    public UUID getRuleId() { return ruleId; }
    public void setRuleId(UUID ruleId) { this.ruleId = ruleId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPattern() { return pattern; }
    public void setPattern(String pattern) { this.pattern = pattern; }
    public String getReplacement() { return replacement; }
    public void setReplacement(String replacement) { this.replacement = replacement; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
}
