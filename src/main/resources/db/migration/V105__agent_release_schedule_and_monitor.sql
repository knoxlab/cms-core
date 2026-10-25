-- 阶段三 AGENT-003（规则化调度发布）+ AGENT-004（异常自动回滚）：
-- ① 发布监控策略：站点级开关与阈值，默认关闭（显式开启后才参与自动判定与自动回退）。
create table release_monitor_policy (
    site_id «uuid» primary key,
    -- 默认关闭：未显式开启的站点不做任何监控判定与自动回滚
    enabled «bool» not null default «lit_f»,
    -- 统计窗口（分钟）与窗口内失败阈值
    window_minutes integer not null default 60,
    failure_threshold integer not null default 1,
    -- AUTO_ROLLBACK：健康度劣化时自动回退；NOTIFY_ONLY：仅记录事件与告警
    action varchar(30) not null default 'AUTO_ROLLBACK',
    updated_by varchar(100),
    updated_at «ts» not null «def_now»
);

-- ② 发布异常事件：监控判定命中的记录（含自动回滚结果与回滚演练）。
create table release_incident (
    id «uuid» primary key,
    site_id «uuid» not null,
    -- DEPLOY_FAILED（窗口内失败达阈值）/ HEALTH_DEGRADED（部署后健康度劣化）/ ROLLBACK_DRILL（演练）
    incident_type varchar(40) not null,
    deployment_id «uuid»,
    release_id «uuid»,
    -- NOTIFIED / AUTO_ROLLED_BACK / ROLLBACK_FAILED / SIMULATED
    status varchar(30) not null,
    -- NOTIFY / AUTO_ROLLBACK / DRILL
    action varchar(30) not null,
    detail «txt»,
    -- 自动回滚生成的新部署记录
    rolled_back_deployment_id «uuid»,
    detected_at «ts» not null,
    resolved_at «ts»
);
create index idx_release_incident_site on release_incident (site_id, detected_at);
create index idx_release_incident_deployment on release_incident (deployment_id, incident_type);
