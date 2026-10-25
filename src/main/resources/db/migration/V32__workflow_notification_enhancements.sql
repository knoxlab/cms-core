-- ============================================================
-- 工作流与通知增强（对齐 workflow-notification-specification）
-- 增量 ALTER，兼容 H2 / PostgreSQL
-- ============================================================

alter table workflow_definition add column is_system «bool» not null default «lit_f»;

«alt_dropnn_v32»;

alter table workflow_task add column site_id «uuid»;
alter table workflow_task add column submitter varchar(100);

alter table notification add column group_id «uuid»;
alter table notification add column level varchar(8) default 'INFO';
alter table notification add column «col_link» varchar(500);
alter table notification add column idempotency_key varchar(200);

create index idx_notification_idem on notification (idempotency_key);
create index idx_wftask_site_status on workflow_task (site_id, status);

alter table content_item add column in_workflow «bool» not null default «lit_f»;
alter table content_item add column last_rejection_reason varchar(2000);
alter table content_item add column scheduled_withdraw_at «ts»;

create table workflow_transition_log (
    id              bigint «idgen» primary key,
    site_id         «uuid»         not null,
    task_id         «uuid»,
    content_item_id «uuid»         not null,
    action_key      varchar(64)  not null,
    from_status     varchar(32)  not null,
    to_status       varchar(32)  not null,
    actor           varchar(100) not null,
    «col_comment»         varchar(2000),
    created_at      «ts»    not null «def_now»
);

create index idx_tl_content on workflow_transition_log (content_item_id, created_at);
create index idx_tl_task on workflow_transition_log (task_id);
