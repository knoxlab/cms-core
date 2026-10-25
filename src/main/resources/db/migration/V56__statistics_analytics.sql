-- 统计分析子系统（docs/statistics-analytics-specification.md v1.5）
-- H2（MODE=PostgreSQL）与 PostgreSQL 兼容语法；JSON 一律 text；UUID 由应用生成。
-- 分区表暂以普通表实现（NFR-STAT-008 声明式分区在生产 PG 上由运维脚本落地，保留 occurred_at/minute 索引即可平滑升级）。

-- 1. 事件明细（FR-STAT-001/002，按月分区见 NFR-STAT-008；保留期默认 90 天）
create table analytics_event (
    id                  «uuid» primary key,
    site_id             «uuid» not null,
    event_type          varchar(40) not null,
    occurred_at         «ts» not null,
    session_id          varchar(64) not null,
    visitor_id          varchar(64) not null,
    session_started_at  «ts»,
    session_seq         integer,
    member_id           «uuid»,
    ip_hash             varchar(64),
    ip_masked           varchar(45),
    region_province     varchar(50),
    region_city         varchar(50),
    server_node         varchar(100),
    route_path          varchar(1000),
    title               varchar(500),
    content_id          «uuid»,
    content_type_id     «uuid»,
    media_asset_id      «uuid»,
    folder_id           «uuid»,
    referer_host        varchar(200),
    source_type         varchar(30),
    search_keyword      varchar(200),
    utm_source          varchar(200),
    utm_medium          varchar(200),
    utm_campaign        varchar(200),
    os                  varchar(50),
    browser             varchar(50),
    browser_version     varchar(50),
    device_type         varchar(30),
    language            varchar(30),
    screen              varchar(20),
    duration_ms         bigint,
    is_bot              «bool» not null default «lit_f»,
    is_spa_nav          «bool» not null default «lit_f»,
    play_session_id     varchar(64),
    play_position_ms    bigint,
    play_delta_ms       bigint,
    play_buffer         «bool»,
    traffic_bytes       bigint,
    end_reason          varchar(20),
    farthest_ms         bigint,
    error_status        integer,
    attrs               «txt»
);
create index idx_analytics_event_site_time on analytics_event (site_id, occurred_at);
create index idx_analytics_event_session on analytics_event (site_id, session_id, occurred_at);
create index idx_analytics_event_content on analytics_event (site_id, content_id, occurred_at);
create index idx_analytics_event_node on analytics_event (server_node, occurred_at);

-- 2. 会话维表（FR-STAT-014 跳出/入口/退出）
create table analytics_session (
    id                  varchar(64) primary key,
    site_id             «uuid» not null,
    visitor_id          varchar(64) not null,
    started_at          «ts» not null,
    ended_at            «ts»,
    page_count          integer not null default 0,
    entry_path          varchar(1000),
    exit_path           varchar(1000),
    is_bounce           «bool» not null default «lit_f»,
    duration_ms         bigint
);
create index idx_analytics_session_site_time on analytics_session (site_id, started_at);

-- 3. 跨天去重基表（FR-STAT-017，永久保留）
create table analytics_visitor_daily (
    site_id             «uuid» not null,
    stat_date           date not null,
    visitor_hash        varchar(64) not null,
    primary key (site_id, stat_date, visitor_hash)
);
create table analytics_ip_daily (
    site_id             «uuid» not null,
    stat_date           date not null,
    ip_hash             varchar(64) not null,
    primary key (site_id, stat_date, ip_hash)
);

-- 4. 站点×日 / 时段 汇总（FR-STAT-010/012/014）
create table analytics_site_daily (
    site_id             «uuid» not null,
    stat_date           date not null,
    pv                  bigint not null default 0,
    uv                  bigint not null default 0,
    ip_count            bigint not null default 0,
    session_count       bigint not null default 0,
    new_uv              bigint not null default 0,
    return_uv           bigint not null default 0,
    bounce_count        bigint not null default 0,
    avg_duration_ms     bigint,
    primary key (site_id, stat_date)
);
create table analytics_hourly (
    site_id             «uuid» not null,
    stat_date           date not null,
    hour_of_day         integer not null,
    pv                  bigint not null default 0,
    uv                  bigint not null default 0,
    ip_count            bigint not null default 0,
    primary key (site_id, stat_date, hour_of_day)
);
create table analytics_entry_daily (
    site_id             «uuid» not null,
    stat_date           date not null,
    route_path          varchar(1000) not null,
    session_count       bigint not null default 0,
    bounce_count        bigint not null default 0,
    «pk_v56»
);
create table analytics_exit_daily (
    site_id             «uuid» not null,
    stat_date           date not null,
    route_path          varchar(1000) not null,
    session_count       bigint not null default 0,
    «pk_v56»
);

-- 5. 栏目 / 内容 / 内容类型 / 资产 汇总（FR-STAT-020/030/031/009）
create table analytics_folder_daily (
    site_id             «uuid» not null,
    folder_id           «uuid» not null,
    stat_date           date not null,
    pv                  bigint not null default 0,
    uv                  bigint not null default 0,
    ip_count            bigint not null default 0,
    session_count       bigint not null default 0,
    avg_duration_ms     bigint,
    primary key (site_id, folder_id, stat_date)
);
create table analytics_content_daily (
    site_id             «uuid» not null,
    content_id          «uuid» not null,
    content_type_id     «uuid»,
    stat_date           date not null,
    pv                  bigint not null default 0,
    uv                  bigint not null default 0,
    play_count          bigint not null default 0,
    avg_duration_ms     bigint,
    primary key (site_id, content_id, stat_date)
);
create table analytics_content_type_daily (
    site_id             «uuid» not null,
    content_type_id     «uuid» not null,
    stat_date           date not null,
    pv                  bigint not null default 0,
    uv                  bigint not null default 0,
    primary key (site_id, content_type_id, stat_date)
);
create table analytics_asset_daily (
    site_id             «uuid» not null,
    media_asset_id      «uuid» not null,
    stat_date           date not null,
    play_count          bigint not null default 0,
    play_session_count  bigint not null default 0,
    watched_ms_total    bigint not null default 0,
    traffic_bytes_total bigint not null default 0,
    primary key (site_id, media_asset_id, stat_date)
);

-- 6. 客户端 / 来源 / 搜索词 / 自定义事件 汇总（FR-STAT-050/040/042/097）
create table analytics_client_daily (
    site_id             «uuid» not null,
    stat_date           date not null,
    dimension           varchar(20) not null,
    dimension_value     varchar(100) not null,
    pv                  bigint not null default 0,
    uv                  bigint not null default 0,
    primary key (site_id, stat_date, dimension, dimension_value)
);
create table analytics_source_daily (
    site_id             «uuid» not null,
    stat_date           date not null,
    source_type         varchar(30) not null,
    detail              varchar(200),
    pv                  bigint not null default 0,
    uv                  bigint not null default 0,
    «pk_v56_src»
);
create table analytics_search_daily (
    site_id             «uuid» not null,
    stat_date           date not null,
    keyword_hash        varchar(64) not null,
    keyword_masked      varchar(200),
    search_count        bigint not null default 0,
    uv                  bigint not null default 0,
    zero_hit_count      bigint not null default 0,
    click_count         bigint not null default 0,
    primary key (site_id, stat_date, keyword_hash)
);
create table analytics_custom_daily (
    site_id             «uuid» not null,
    stat_date           date not null,
    event_name          varchar(100) not null,
    attr_key            varchar(100),
    attr_value          varchar(200),
    event_count         bigint not null default 0,
    uv                  bigint not null default 0,
    «pk_v56_custom»
);

-- 7. 忠诚度分档：深度/频度为两个独立分布（FR-STAT-060）
create table analytics_depth_daily (
    site_id             «uuid» not null,
    stat_date           date not null,
    depth_bucket        varchar(20) not null,
    session_count       bigint not null default 0,
    primary key (site_id, stat_date, depth_bucket)
);
create table analytics_frequency_daily (
    site_id             «uuid» not null,
    stat_date           date not null,
    frequency_bucket    varchar(20) not null,
    visitor_count       bigint not null default 0,
    primary key (site_id, stat_date, frequency_bucket)
);

-- 8. 播放会话（FR-STAT-034~036）与会员学习进度（FR-STAT-037~039）
create table analytics_play_session (
    id                  varchar(64) primary key,
    site_id             «uuid» not null,
    content_id          «uuid»,
    content_type_id     «uuid»,
    media_asset_id      «uuid»,
    visitor_id          varchar(64) not null,
    member_id           «uuid»,
    started_at          «ts» not null,
    ended_at            «ts»,
    end_reason          varchar(20),
    duration_ms         bigint,
    watched_ms          bigint not null default 0,
    farthest_ms         bigint not null default 0,
    resume_ms           bigint not null default 0,
    traffic_bytes       bigint not null default 0,
    referrer_path       varchar(1000),
    device_type         varchar(30)
);
create index idx_analytics_play_session_content on analytics_play_session (site_id, content_id, started_at);
create index idx_analytics_play_session_member on analytics_play_session (site_id, member_id, started_at);

create table analytics_play_daily (
    site_id             «uuid» not null,
    content_id          «uuid» not null,
    stat_date           date not null,
    play_count          bigint not null default 0,
    play_session_count  bigint not null default 0,
    completed_count     bigint not null default 0,
    watched_ms_total    bigint not null default 0,
    traffic_bytes_total bigint not null default 0,
    exit_buckets        varchar(1000),
    primary key (site_id, content_id, stat_date)
);

create table member_progress (
    site_id             «uuid» not null,
    member_id           «uuid» not null,
    content_id          «uuid» not null,
    content_type_id     «uuid»,
    folder_id           «uuid»,
    farthest_ms         bigint not null default 0,
    resume_ms           bigint not null default 0,
    watched_ms_total    bigint not null default 0,
    completed           «bool» not null default «lit_f»,
    last_watched_at     «ts»,
    updated_at          «ts» not null «def_now»,
    primary key (site_id, member_id, content_id)
);

-- 9. 分钟在线快照（FR-STAT-066~068/106；server_node='__ALL__' 为全局去重行，FR-STAT-068）
create table analytics_online_minute (
    site_id             «uuid» not null,
    server_node         varchar(100) not null,
    minute_bucket       «ts» not null,
    online_count        integer not null default 0,
    watching_count      integer not null default 0,
    top_contents        «txt»,
    primary key (site_id, server_node, minute_bucket)
);

-- 10. 采集节点注册与心跳（FR-STAT-107）
create table analytics_node (
    server_node         varchar(100) primary key,
    host                varchar(200),
    version             varchar(50),
    registered_at       «ts» not null «def_now»,
    last_heartbeat_at   «ts» not null «def_now»,
    status              varchar(20) not null default 'ONLINE',
    queue_depth         integer not null default 0,
    events_per_min      integer not null default 0,
    dropped_count       bigint not null default 0,
    config_version      varchar(50),
    geo_lib_version     varchar(50)
);

-- 11. IP 离线地理库登记（FR-STAT-093，全局配置）
create table ip_geo_library (
    id                  «uuid» primary key,
    version             varchar(50) not null,
    file_ref            varchar(500) not null,
    checksum            varchar(128),
    uploaded_by         varchar(100),
    uploaded_at         «ts» not null «def_now»,
    status              varchar(20) not null default 'ACTIVE',
    activated_at        «ts»
);

-- 12. 自定义事件注册白名单（FR-STAT-097）
create table analytics_custom_event_def (
    id                  «uuid» primary key,
    site_id             «uuid» not null,
    event_name          varchar(100) not null,
    attrs_schema        «txt»,
    enabled             «bool» not null default «lit_t»,
    created_at          «ts» not null «def_now»,
    unique (site_id, event_name)
);
