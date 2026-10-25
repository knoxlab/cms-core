package com.powercms.cms.publish.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "build_log")
public class BuildLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "build_job_id", nullable = false) private UUID buildJobId;
    @Column(nullable = false) private int seq;
    @Column(nullable = false, length = 10) private String level = "INFO";
    @Column(nullable = false, length = 2000) private String line;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    public static BuildLogEntity write(UUID jobId, int seq, String level, String line) {
        var l = new BuildLogEntity();
        l.buildJobId = jobId;
        l.seq = seq;
        l.level = level;
        l.line = line;
        l.createdAt = Instant.now();
        return l;
    }

    public Long getId() { return id; }
    public UUID getBuildJobId() { return buildJobId; }
    public int getSeq() { return seq; }
    public String getLevel() { return level; }
    public String getLine() { return line; }
    public Instant getCreatedAt() { return createdAt; }
}
