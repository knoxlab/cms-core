/**
 * 系统日志（FR-LOG 双轨：运行日志 RuntimeLog + 访问日志 AccessLog）。
 * <ul>
 *   <li>采集：Logback 自定义 Appender（运行日志）+ {@code AccessLogFilter}（访问日志）；</li>
 *   <li>管道：内存有界队列异步批量落库，文件通道独立兜底，写入失败不影响业务（FR-LOG-ST-002）；</li>
 *   <li>权限：{@code logs} / {@code logs-admin} 能力键 + ORG/SITE 双可见域（FR-LOG-PM-001）；</li>
 *   <li>留痕：查询/导出复用既有 {@code audit_event}，不新建留痕表（FR-LOG-PM-005）。</li>
 * </ul>
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "System Log",
    type = org.springframework.modulith.ApplicationModule.Type.OPEN,
    allowedDependencies = { "identity", "audit", "site", "platform" }
)
package com.powercms.cms.systemlog;
