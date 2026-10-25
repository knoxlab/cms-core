package com.powercms.cms.systemlog;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 系统日志配置（powercms.system-log.*，NFR-LOG-016 配置化）：
 * 队列容量、批量写入、保留周期、查询限流与跨度、导出上限、降采样阈值。
 * 修改仅需滚动重启（首期不引入热更新通道）。
 */
@Component
@ConfigurationProperties(prefix = "powercms.system-log")
public class SystemLogProperties {

    /** 内存有界队列容量（FR-LOG-ST-002，默认 10000 条）。 */
    private int queueCapacity = 10_000;

    /** 批量落库单批条数（每 500 条或每 flushInterval 落一次）。 */
    private int batchSize = 500;

    /** 批量写入器扫描间隔。 */
    private Duration flushInterval = Duration.ofSeconds(1);

    /** 运行日志 INFO 及以上保留天数（FR-LOG-ST-003，默认 30 天）。 */
    private int runtimeRetentionDays = 30;

    /** DEBUG/TRACE 保留天数（FR-LOG-ST-003，默认 7 天）。 */
    private int debugRetentionDays = 7;

    /** 访问日志保留天数（FR-LOG-ST-003，默认 90 天）。 */
    private int accessRetentionDays = 90;

    /** 认证失败/限流类访问日志保留天数（FR-LOG-ST-003，默认 180 天）。 */
    private int authFailureRetentionDays = 180;

    /** 文件通道保留天数（FR-LOG-ST-004，默认 180 天；清理任务按此删除过期滚动文件）。 */
    private int fileRetentionDays = 180;

    /** 文件通道滚动目录（空 = 不启用文件清理扫描）。 */
    private String fileLogDir = "";

    /** 单次查询最大时间跨度（FR-LOG-QR-004，默认 30 天）。 */
    private Duration maxQuerySpan = Duration.ofDays(30);

    /** 每用户每分钟最大查询次数（FR-LOG-QR-004，默认 30 次）。 */
    private int queryRatePerMinute = 30;

    /** 单次导出上限（FR-LOG-QR-008，默认 1 万条）。 */
    private int exportLimit = 10_000;

    /** 导出下载有效期（FR-LOG-QR-009，默认 30 分钟）。 */
    private Duration exportTtl = Duration.ofMinutes(30);

    /** 同一匿名路径降采样阈值（FR-LOG-AC-004，条/秒，默认 50）。 */
    private int downsamplePerSecond = 50;

    /** 是否采集访问日志（D-02 待确认：Delivery API 全量纳入会翻倍，可用开关先收口）。 */
    private boolean accessLogDeliveryEnabled = true;

    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public Duration getFlushInterval() { return flushInterval; }
    public void setFlushInterval(Duration flushInterval) { this.flushInterval = flushInterval; }
    public int getRuntimeRetentionDays() { return runtimeRetentionDays; }
    public void setRuntimeRetentionDays(int runtimeRetentionDays) { this.runtimeRetentionDays = runtimeRetentionDays; }
    public int getDebugRetentionDays() { return debugRetentionDays; }
    public void setDebugRetentionDays(int debugRetentionDays) { this.debugRetentionDays = debugRetentionDays; }
    public int getAccessRetentionDays() { return accessRetentionDays; }
    public void setAccessRetentionDays(int accessRetentionDays) { this.accessRetentionDays = accessRetentionDays; }
    public int getAuthFailureRetentionDays() { return authFailureRetentionDays; }
    public void setAuthFailureRetentionDays(int authFailureRetentionDays) { this.authFailureRetentionDays = authFailureRetentionDays; }
    public int getFileRetentionDays() { return fileRetentionDays; }
    public void setFileRetentionDays(int fileRetentionDays) { this.fileRetentionDays = fileRetentionDays; }
    public String getFileLogDir() { return fileLogDir; }
    public void setFileLogDir(String fileLogDir) { this.fileLogDir = fileLogDir; }
    public Duration getMaxQuerySpan() { return maxQuerySpan; }
    public void setMaxQuerySpan(Duration maxQuerySpan) { this.maxQuerySpan = maxQuerySpan; }
    public int getQueryRatePerMinute() { return queryRatePerMinute; }
    public void setQueryRatePerMinute(int queryRatePerMinute) { this.queryRatePerMinute = queryRatePerMinute; }
    public int getExportLimit() { return exportLimit; }
    public void setExportLimit(int exportLimit) { this.exportLimit = exportLimit; }
    public Duration getExportTtl() { return exportTtl; }
    public void setExportTtl(Duration exportTtl) { this.exportTtl = exportTtl; }
    public int getDownsamplePerSecond() { return downsamplePerSecond; }
    public void setDownsamplePerSecond(int downsamplePerSecond) { this.downsamplePerSecond = downsamplePerSecond; }
    public boolean isAccessLogDeliveryEnabled() { return accessLogDeliveryEnabled; }
    public void setAccessLogDeliveryEnabled(boolean accessLogDeliveryEnabled) { this.accessLogDeliveryEnabled = accessLogDeliveryEnabled; }
}
