-- ============================================================
-- 定时发布/撤回 + 邮件投递记录（规格第 7、8.4 章）
-- 增量 ALTER，兼容 H2 / PostgreSQL
-- ============================================================

alter table workflow_task add column scheduled_at «ts»;
alter table workflow_task add column execution_state varchar(24);

create index idx_wftask_due on workflow_task (status, execution_state, scheduled_at);

alter table content_item add column scheduled_publish_at «ts»;

create index idx_content_publish_due on content_item (scheduled_publish_at);
create index idx_content_withdraw_due on content_item (scheduled_withdraw_at);

create table notification_delivery (
    id              bigint «idgen» primary key,
    notification_id «uuid»,
    event_type      varchar(64)  not null,
    idempotency_key varchar(200) not null,
    channel         varchar(16)  not null,
    recipient       varchar(200) not null,
    status          varchar(16)  not null,
    attempts        int          not null default 0,
    last_error      varchar(2000),
    created_at      «ts»    not null «def_now»,
    updated_at      «ts»
);

create index idx_delivery_idem on notification_delivery (idempotency_key);
create index idx_delivery_status on notification_delivery (status, updated_at);
