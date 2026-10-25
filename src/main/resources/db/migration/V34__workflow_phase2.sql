-- ============================================================
-- 工作流二期：会签、超时升级、通知偏好与模板
-- ============================================================

alter table workflow_definition add column timeout_minutes int not null default 0;
alter table workflow_definition add column reminder_interval_minutes int not null default 0;
alter table workflow_definition add column timeout_action_key varchar(32) not null default 'NOTIFY';

alter table workflow_transition add column min_approvers int not null default 0;
alter table workflow_transition add column action_key varchar(64);
alter table workflow_transition add column action_name varchar(100);

alter table workflow_task add column delegated_from varchar(100);
alter table workflow_task add column last_timeout_warned_at «ts»;
alter table workflow_task add column timeout_escalated_at «ts»;

create table workflow_task_confirmation (
    id            «uuid» primary key,
    task_id       «uuid»         not null,
    action_key    varchar(64)  not null,
    actor         varchar(100) not null,
    confirmed_at  «ts»    not null «def_now»,
    unique (task_id, action_key, actor)
);

create index idx_wftask_status_created on workflow_task (status, created_at);

create table notification_preference (
    id          «uuid» primary key,
    username    varchar(100) not null,
    event_type  varchar(64)  not null,
    channel     varchar(16)  not null,
    enabled     «bool»      not null default «lit_t»,
    unique (username, event_type, channel)
);

create table notification_template (
    id            «uuid» primary key,
    site_id       «uuid»,
    template_key  varchar(64)  not null,
    subject       varchar(300) not null,
    body_html     varchar(8000) not null,
    body_text     varchar(4000),
    updated_by    varchar(100),
    updated_at    «ts»    not null «def_now»
);

create unique index idx_notif_tpl_site_key on notification_template (site_id, template_key);

-- 模板正文使用 FreeMarker 插值（美元符号加大括号）。Flyway 占位符前后缀已改为尖括号对
-- （见 application.yml 的 placeholder-prefix/suffix），${...} 字面量不在替换范围。V34 种子同此约定。
insert into notification_template (id, site_id, template_key, subject, body_html, body_text, updated_by, updated_at)
values
('11111111-1111-1111-1111-111111111001', null, 'task_submitted',
 '审核任务：${contentTitle}',
 '<p>内容《${contentTitle}》等待审核。</p><p>提交人：${submitter}</p>',
 '内容《${contentTitle}》等待审核。提交人：${submitter}', 'system', current_timestamp),
('11111111-1111-1111-1111-111111111002', null, 'task_approved',
 '审核通过：${contentTitle}',
 '<p>内容《${contentTitle}》已批准。</p><p>处理人：${reviewer}</p>',
 '内容《${contentTitle}》已批准。处理人：${reviewer}', 'system', current_timestamp),
('11111111-1111-1111-1111-111111111003', null, 'task_rejected',
 '审核驳回：${contentTitle}',
 '<p>内容《${contentTitle}》被驳回。</p><p>原因：${reason}</p>',
 '内容《${contentTitle}》被驳回。原因：${reason}', 'system', current_timestamp),
('11111111-1111-1111-1111-111111111004', null, 'publish_succeeded',
 '发布完成：${contentTitle}',
 '<p>内容《${contentTitle}》已发布。</p>',
 '内容《${contentTitle}》已发布。', 'system', current_timestamp),
('11111111-1111-1111-1111-111111111005', null, 'task_timeout',
 '审核超时：${contentTitle}',
 '<p>内容《${contentTitle}》的审核任务已超时，请尽快处理。</p>',
 '内容《${contentTitle}》的审核任务已超时。', 'system', current_timestamp),
('11111111-1111-1111-1111-111111111006', null, 'task_delegated',
 '审核委派：${contentTitle}',
 '<p>内容《${contentTitle}》已委派给您审核。</p>',
 '内容《${contentTitle}》已委派给您审核。', 'system', current_timestamp),
('11111111-1111-1111-1111-111111111007', null, 'build_failed',
 '构建失败：${contentTitle}',
 '<p>内容《${contentTitle}》发布构建失败。</p><p>${failureDetail}</p>',
 '内容《${contentTitle}》发布构建失败。${failureDetail}', 'system', current_timestamp);
