-- ============================================================
-- 版本授权与配额（docs/license-and-offline-subscription-design.md §3.4）
-- 三张表：
--   license_state  单行，保存当前生效 License 的**验签后明文**与单调水位
--   license_node   多节点登记（heartbeat × 3 内视为在线，用于 appNodes 配额）
--   license_audit  授权生命周期审计（激活/续期/吊销/到期/指纹漂移/时钟回拨）
--
-- 设计约束：
--   1) 一次上传全集群生效：状态写 license_state 单行，其余节点按 version 变化热加载；
--   2) 离线续期：raw_license 保存原文，便于纠纷核对与重新验签；
--   3) seq 单调 + last_seen_at 水位：防止重放旧文件与系统时钟回拨（§4.2）；
--   4) 社区版不依赖 License：无行时按 COMMUNITY 默认能力与配额运行。
-- H2（本地开发）与 PostgreSQL（生产）兼容语法；JSON 以文本列保存（不依赖 jsonb）。
-- ============================================================

create table license_state (
    id                     «uuid»         not null,
    singleton_key          varchar(16)  not null,
    version                bigint       not null,
    license_id             varchar(64)  not null,
    seq                    bigint       not null,
    edition                varchar(32)  not null,
    licensee               varchar(400) not null,
    deployment             varchar(16)  not null,
    not_before             «ts»    not null,
    not_after              «ts»    not null,
    grace_days             int          not null default 15,
    max_not_after          «ts»,
    lease_days             int,
    lease_until            «ts»,
    capabilities           «txt»         not null,
    quotas                 «txt»         not null,
    revoke_list            «txt»,
    binding_mode           varchar(24)  not null,
    fingerprints           «txt»,
    key_id                 varchar(64),
    signature_alg          varchar(40),
    fingerprint_components «txt»,
    drift_events           int          not null default 0,
    rebind_used            int          not null default 0,
    last_seen_at           «ts»    not null,
    clock_regressions      int          not null default 0,
    revoked                «bool»      not null default «lit_f»,
    raw_license            «txt»         not null,
    created_at             «ts»    not null,
    updated_at             «ts»    not null,
    constraint pk_license_state primary key (id),
    constraint uq_license_state_singleton unique (singleton_key)
);

create table license_node (
    id             «uuid»       not null,
    node_key       varchar(64)  not null,
    host_name      varchar(200),
    product_version varchar(40),
    fingerprint    varchar(128) not null,
    first_seen_at  «ts»   not null,
    last_seen_at   «ts»   not null,
    created_at     «ts»   not null,
    constraint pk_license_node primary key (id),
    constraint uq_license_node_key unique (node_key)
);

create «idx» idx_license_node_seen on license_node (last_seen_at desc);

create table license_audit (
    id          «uuid»       not null,
    occurred_at «ts»   not null,
    event_type  varchar(48)  not null,
    license_id  varchar(64),
    seq         bigint,
    node_key    varchar(64),
    actor       varchar(200),
    detail      «txt»,
    constraint pk_license_audit primary key (id)
);

create «idx» idx_license_audit_time on license_audit (occurred_at desc);
create «idx» idx_license_audit_type on license_audit (event_type, occurred_at desc);
