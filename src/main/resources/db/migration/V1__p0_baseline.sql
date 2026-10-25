-- ============================================================
-- P0 平台骨架基线：组织、用户、角色、站点、环境、审计、Outbox
-- H2（本地开发）与 PostgreSQL（生产）兼容语法
-- ============================================================

create table organization (
    id          «uuid» primary key,
    name        varchar(200) not null,
    created_at  «ts» not null «def_now»,
    updated_at  «ts» not null «def_now»
);

create table sys_user (
    id              «uuid» primary key,
    organization_id «uuid»         not null references organization (id),
    username        varchar(100) not null unique,
    password_hash   varchar(200) not null,
    display_name    varchar(200) not null,
    email           varchar(320),
    status          varchar(20)  not null default 'ACTIVE',
    failed_logins   int          not null default 0,
    created_at      «ts» not null «def_now»,
    updated_at      «ts» not null «def_now»
);

create table sys_role (
    id              «uuid» primary key,
    organization_id «uuid»         not null references organization (id),
    code            varchar(100) not null,
    name            varchar(200) not null,
    description     varchar(500),
    built_in        «bool»      not null default «lit_f»,
    unique (organization_id, code)
);

create table site (
    id              «uuid» primary key,
    organization_id «uuid»         not null references organization (id),
    site_key        varchar(100) not null unique,
    name            varchar(200) not null,
    description     varchar(1000),
    default_locale  varchar(20)  not null default 'zh-CN',
    timezone        varchar(60)  not null default 'Asia/Shanghai',
    status          varchar(20)  not null default 'ACTIVE',
    created_at      «ts» not null «def_now»,
    updated_at      «ts» not null «def_now»
);

create table site_environment (
    id      «uuid» primary key,
    site_id «uuid»        not null references site (id),
    name    varchar(50) not null,
    type    varchar(20) not null,
    unique (site_id, name)
);

create table sys_user_role (
    id      «uuid» primary key,
    user_id «uuid» not null references sys_user (id),
    role_id «uuid» not null references sys_role (id),
    site_id «uuid» references site (id),
    unique (user_id, role_id, site_id)
);

create table audit_event (
    id          «uuid» primary key,
    occurred_at «ts»    not null «def_now»,
    actor       varchar(200) not null,
    action      varchar(100) not null,
    object_type varchar(100) not null,
    object_id   varchar(200),
    site_id     «uuid»,
    result      varchar(20)  not null,
    trace_id    varchar(64),
    detail      «txt»
);

create index idx_audit_event_occurred_at on audit_event (occurred_at);
create index idx_audit_event_object on audit_event (object_type, object_id);

create table outbox_event (
    id             «uuid» primary key,
    event_type     varchar(100) not null,
    aggregate_type varchar(100) not null,
    aggregate_id   varchar(200) not null,
    payload        «txt»         not null,
    occurred_at    «ts»    not null «def_now»,
    published_at   «ts»,
    attempts       int          not null default 0,
    last_error     varchar(2000)
);

create index idx_outbox_unpublished on outbox_event (occurred_at, published_at);

-- Spring Modulith 事件发布表（spring-modulith-starter-jpa 要求）
create table event_publication (
    id                «uuid» primary key,
    completion_date   «ts»,
    event_type        varchar(512) not null,
    listener_id       varchar(512) not null,
    publication_date  «ts»    not null,
    serialized_event  varchar(4000) not null
);
