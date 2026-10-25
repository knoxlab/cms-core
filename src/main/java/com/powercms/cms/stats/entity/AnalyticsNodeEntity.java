package com.powercms.cms.stats.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 采集节点注册与心跳（FR-STAT-107）。server_node 集群内唯一。
 */
@Entity
@Table(name = "analytics_node")
public class AnalyticsNodeEntity {

    public static final String STATUS_ONLINE = "ONLINE";
    public static final String STATUS_DRAINING = "DRAINING";
    public static final String STATUS_OFFLINE = "OFFLINE";

    @Id
    @Column(name = "server_node", length = 100)
    private String serverNode;

    @Column(name = "host", length = 200)
    private String host;

    @Column(name = "version", length = 50)
    private String version;

    @Column(name = "registered_at")
    private Instant registeredAt;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    @Column(name = "status", nullable = false, length = 20)
    private String status = STATUS_ONLINE;

    @Column(name = "queue_depth")
    private int queueDepth;

    @Column(name = "events_per_min")
    private int eventsPerMin;

    @Column(name = "dropped_count")
    private long droppedCount;

    @Column(name = "config_version", length = 50)
    private String configVersion;

    @Column(name = "geo_lib_version", length = 50)
    private String geoLibVersion;

    public static AnalyticsNodeEntity register(String serverNode, String host, String version) {
        AnalyticsNodeEntity n = new AnalyticsNodeEntity();
        n.serverNode = serverNode;
        n.host = host;
        n.version = version;
        n.registeredAt = Instant.now();
        n.lastHeartbeatAt = Instant.now();
        n.status = STATUS_ONLINE;
        return n;
    }

    public String getServerNode() { return serverNode; }
    public String getHost() { return host; }
    public String getVersion() { return version; }
    public Instant getRegisteredAt() { return registeredAt; }
    public Instant getLastHeartbeatAt() { return lastHeartbeatAt; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public int getQueueDepth() { return queueDepth; }
    public void setQueueDepth(int v) { this.queueDepth = v; }
    public int getEventsPerMin() { return eventsPerMin; }
    public void setEventsPerMin(int v) { this.eventsPerMin = v; }
    public long getDroppedCount() { return droppedCount; }
    public void setDroppedCount(long v) { this.droppedCount = v; }
    public String getConfigVersion() { return configVersion; }
    public void setConfigVersion(String v) { this.configVersion = v; }
    public String getGeoLibVersion() { return geoLibVersion; }
    public void setGeoLibVersion(String v) { this.geoLibVersion = v; }
}
