-- ============================================================
-- FR-LOG 系统日志双轨存储（docs/system-log-specification.md §5/§12.2）
-- 运行日志 runtime_log + 访问日志 access_log（近期热数据，支撑在线检索）
-- D-01 待确认决策：首期按方案 B 起步（不分区，纯 JPA + 批量 DELETE 清理），
-- 表结构已为方案 A（按月 RANGE 分区）预留：主键含 occurred_at 维度演进空间，
-- 迁移时不建外键、不使用生成列，保证分区分裂脚本可无痛叠加。
-- H2（本地开发）与 PostgreSQL（生产）兼容语法；UTC 口径由 Instant 写入保证。
-- ============================================================

create table runtime_log (
    id              «uuid»         not null,
    occurred_at     «ts»    not null,
    level           varchar(8)   not null,
    service         varchar(64),
    module          varchar(128),
    node            varchar(64),
    thread          varchar(128),
    trace_id        varchar(64),
    site_key        varchar(64),
    message         «txt»         not null,
    exception_type  varchar(256),
    stack           «txt»,
    truncated       «bool»      not null default «lit_f»,
    original_bytes  bigint,
    constraint pk_runtime_log primary key (id)
);

create index idx_runtime_log_occurred on runtime_log (occurred_at desc, level);
create index idx_runtime_log_trace on runtime_log (trace_id);
create index idx_runtime_log_module on runtime_log (module, occurred_at desc);
create index idx_runtime_log_site on runtime_log (site_key, occurred_at desc);

create table access_log (
    id           «uuid»         not null,
    occurred_at  «ts»    not null,
    client_ip    varchar(64),
    method       varchar(8)   not null,
    path         varchar(512) not null,
    status       smallint     not null,
    duration_ms  int          not null default 0,
    resp_bytes   bigint,
    «col_identity»  varchar(128),
    user_agent   varchar(512),
    trace_id     varchar(64),
    site_key     varchar(64),
    outcome      varchar(16)  not null,
    error_code   varchar(100),
    constraint pk_access_log primary key (id)
);

create index idx_access_log_occurred on access_log (occurred_at desc, outcome);
create index idx_access_log_trace on access_log (trace_id);
create index idx_access_log_site on access_log (site_key, occurred_at desc);
create index idx_access_log_outcome on access_log (outcome, occurred_at desc);

-- FR-LOG-ST-007（阶段二）归档预留字段不落列：JSON Lines 对象存储归档以 occurred_at 分区推导，
-- 无需列结构变更；此注释仅为文档锚点（评审 R-15 表结构预留要求）。

-- 查询/导出留痕复用既有 audit_event（FR-LOG-PM-005），不新建留痕表。
