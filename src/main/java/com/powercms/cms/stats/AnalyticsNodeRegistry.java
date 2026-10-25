package com.powercms.cms.stats;

import com.powercms.cms.stats.entity.AnalyticsNodeEntity;
import com.powercms.cms.stats.repo.AnalyticsNodeRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.time.Instant;
import java.util.List;

/**
 * 采集节点生命周期（FR-STAT-107）：启动注册（重复 server_node 拒绝启动）、
 * 周期心跳（队列/吞吐/丢弃/版本）、优雅下线（DRAINING → flush → OFFLINE）。
 * 心跳超时节点由管理任务标记 OFFLINE 并告警。
 */
@Service
public class AnalyticsNodeRegistry {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsNodeRegistry.class);

    private final AnalyticsNodeRepository nodeRepo;
    private final AnalyticsIngestPipeline pipeline;
    private final String serverNode;
    private final String host;
    private final String version;
    private final long heartbeatTimeoutMs;

    public AnalyticsNodeRegistry(AnalyticsNodeRepository nodeRepo,
                                 AnalyticsIngestPipeline pipeline,
                                 @Value("${powercms.analytics.server-node:}") String configuredNode,
                                 @Value("${powercms.analytics.heartbeat-timeout-ms:90000}") long heartbeatTimeoutMs) {
        this.nodeRepo = nodeRepo;
        this.pipeline = pipeline;
        this.serverNode = configuredNode == null || configuredNode.isBlank() ? defaultNodeName() : configuredNode;
        this.host = localHost();
        this.version = "1.0";
        this.heartbeatTimeoutMs = heartbeatTimeoutMs;
    }

    private static String defaultNodeName() {
        try {
            String hostName = InetAddress.getLocalHost().getHostName();
            return hostName + "-" + ProcessHandle.current().pid();
        } catch (Exception e) {
            return "node-" + ProcessHandle.current().pid();
        }
    }

    private static String localHost() {
        try { return InetAddress.getLocalHost().getHostAddress(); } catch (Exception e) { return "unknown"; }
    }

    @Transactional
    public void register() {
        if (nodeRepo.existsById(serverNode)) {
            throw new IllegalStateException("重复的 server_node: " + serverNode + "（FR-STAT-107 集群内必须唯一）");
        }
        try {
            nodeRepo.save(AnalyticsNodeEntity.register(serverNode, host, version));
            log.info("analytics node registered: {}", serverNode);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException("重复的 server_node: " + serverNode, e);
        }
    }

    @Transactional
    public void heartbeat() {
        nodeRepo.findById(serverNode).ifPresent(node -> {
            node.setStatus(pipeline.draining() ? AnalyticsNodeEntity.STATUS_DRAINING : AnalyticsNodeEntity.STATUS_ONLINE);
            node.setQueueDepth(pipeline.queueDepth());
            node.setEventsPerMin((int) Math.min(Integer.MAX_VALUE, pipeline.acceptedCount()));
            node.setDroppedCount(pipeline.droppedCount());
            nodeRepo.save(node);
        });
    }

    /** 标记心跳超时节点为 OFFLINE（由分钟任务调用，FR-STAT-107）。 */
    @Transactional
    public int markTimedOutNodes() {
        Instant threshold = Instant.now().minusMillis(heartbeatTimeoutMs);
        List<AnalyticsNodeEntity> stale = nodeRepo.findByLastHeartbeatAtAfter(Instant.EPOCH).stream()
            .filter(n -> !serverNode.equals(n.getServerNode()))
            .filter(n -> n.getLastHeartbeatAt().isBefore(threshold))
            .filter(n -> !AnalyticsNodeEntity.STATUS_OFFLINE.equals(n.getStatus()))
            .toList();
        stale.forEach(n -> n.setStatus(AnalyticsNodeEntity.STATUS_OFFLINE));
        nodeRepo.saveAll(stale);
        return stale.size();
    }

    /** 优雅下线：停止接流 → flush 队列 → 标记 OFFLINE（FR-STAT-107）。 */
    @PreDestroy
    @Transactional
    public void gracefulShutdown() {
        try {
            pipeline.drain(10_000);
            nodeRepo.findById(serverNode).ifPresent(node -> {
                node.setStatus(AnalyticsNodeEntity.STATUS_OFFLINE);
                node.setQueueDepth(pipeline.queueDepth());
                nodeRepo.save(node);
            });
            log.info("analytics node drained and offline: {}", serverNode);
        } catch (Exception e) {
            log.error("graceful shutdown failed for node {}", serverNode, e);
        }
    }

    public String serverNode() { return serverNode; }
}
