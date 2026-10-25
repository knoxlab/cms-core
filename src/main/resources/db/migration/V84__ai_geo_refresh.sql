-- FR-AI-GEO-005 结构化数据批量刷新：刷新任务追踪 + 站点定时调度配置。
-- JSON-LD/meta 在渲染时实时注入（PageRenderService→SeoInjector），故「重生成」落点为：
-- 重索引受影响内容（检索口径）+ 触发整站重新发布（页面口径）。任务表负责可追踪、可审计；
-- 「可回滚」由既有 Release 机制承接（刷新只新增 Release，历史 Release 可一键回退）。
create table ai_geo_refresh_job (
    id «uuid» primary key,
    site_id «uuid» not null,
    -- MANUAL：手动触发；SCHEDULED：定时调度触发
    trigger_type varchar(20) not null default 'MANUAL',
    status varchar(20) not null default 'PENDING',
    total integer not null default 0,
    processed integer not null default 0,
    succeeded integer not null default 0,
    failed integer not null default 0,
    -- 刷新产生的 Release（发布链路）；null 表示未触发发布或站点无 FTL 模板
    release_id «uuid»,
    created_by varchar(100) not null,
    created_at «ts» not null «def_now»,
    started_at «ts»,
    finished_at «ts»,
    error_message varchar(2000)
);
create index idx_ai_geo_refresh_site on ai_geo_refresh_job (site_id, status, created_at);

-- 站点级定时刷新配置（cron 表达式；enabled=false 时不调度）
create table ai_geo_refresh_schedule (
    site_id «uuid» primary key,
    enabled «bool» not null default «lit_f»,
    cron_expr varchar(50) not null default '0 0 4 * * *',
    last_run_at «ts»,
    updated_at «ts» not null «def_now»
);
