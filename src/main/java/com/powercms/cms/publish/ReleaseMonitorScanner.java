package com.powercms.cms.publish;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * FR-AI-AGENT-004 发布监控扫描：按固定间隔判定各站点失败阈值与部署后健康度，
 * 命中后记事件并按策略决定是否自动回退（复用 {@link ReleaseMonitorService#scan}）。
 * 通过 {@code powercms.publish.monitor-scan-enabled=false} 可关闭。
 */
@Component
@ConditionalOnProperty(name = "powercms.publish.monitor-scan-enabled", havingValue = "true", matchIfMissing = true)
public class ReleaseMonitorScanner {

    private final ReleaseMonitorService monitorService;

    public ReleaseMonitorScanner(ReleaseMonitorService monitorService) {
        this.monitorService = monitorService;
    }

    @Scheduled(fixedDelayString = "${powercms.publish.monitor-scan-ms:60000}")
    public void scan() {
        monitorService.scan(Instant.now());
    }
}
