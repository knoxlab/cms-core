-- ============================================================
-- P2：工作流、Release、站内通知
-- H2 / PostgreSQL 兼容
-- ============================================================

-- 工作流定义（FR-WF-001）：版本化，发布后不可原地修改
create table workflow_definition (
    id          «uuid» primary key,
    site_id     «uuid»         not null references site (id),
    name        varchar(200) not null,
    description varchar(1000),
    version     int          not null default 1,
    status      varchar(20)  not null default 'DRAFT', -- DRAFT / PUBLISHED
    created_at  «ts» not null «def_now»,
    updated_at  «ts» not null «def_now»
);

create index idx_wfdef_site on workflow_definition (site_id);

-- 工作流转换（FR-WF-002）：允许角色、前置状态、目标状态、是否必填意见
create table workflow_transition (
    id                      «uuid» primary key,
    workflow_definition_id  «uuid»        not null references workflow_definition (id),
    source_status           varchar(20) not null,
    target_status           varchar(20) not null,
    allowed_role_id         «uuid»        not null references sys_role (id),
    required_comment        «bool»     not null default «lit_f»,
    sort_order              int         not null default 0,
    unique (workflow_definition_id, source_status, target_status, allowed_role_id)
);

-- 工作流任务（FR-WF-003~005）：审核/驳回/委派
create table workflow_task (
    id              «uuid» primary key,
    workflow_def_id «uuid»         not null references workflow_definition (id),
    content_item_id «uuid»         not null references content_item (id),
    content_version int          not null, -- 审核关联的具体版本
    source_status   varchar(20)  not null,
    target_status   varchar(20)  not null,
    assignee        varchar(100) not null, -- 被指派人 username
    status          varchar(20)  not null default 'PENDING', -- PENDING / APPROVED / REJECTED / DELEGATED
    «col_comment»         varchar(2000),
    resolved_by     varchar(100),
    resolved_at     «ts»,
    created_at      «ts»    not null «def_now»
);

create index idx_wftask_content on workflow_task (content_item_id);
create index idx_wftask_assignee on workflow_task (assignee, status);

-- 站内通知（FR-WF-006）
create table notification (
    id              «uuid» primary key,
    recipient       varchar(100) not null, -- username
    type            varchar(50)  not null, -- WORKFLOW_APPROVE / WORKFLOW_REJECT / WORKFLOW_DELEGATE / SYSTEM
    title           varchar(200) not null,
    body            varchar(2000),
    read_at         «ts»,
    related_type    varchar(50),
    related_id      «uuid»,
    created_at      «ts» not null «def_now»
);

create index idx_notif_recipient on notification (recipient, read_at);

-- 不可变 Release（FR-PUBLISH-001）：冻结内容/模型/模板/配置/媒体的版本快照
create table «tbl_release» (
    id              «uuid» primary key,
    site_id         «uuid»         not null references site (id),
    name            varchar(200) not null,
    description     varchar(1000),
    status          varchar(20)  not null default 'DRAFT', -- DRAFT / FROZEN / DEPLOYED / ROLLED_BACK
    frozen_at       «ts»,
    created_at      «ts» not null «def_now»,
    updated_at      «ts» not null «def_now»,
    lob_init        text -- DM 哨兵：建表含 LOB 解除达梦 cluster+lob add 限制（其他库无害，不映射实体）
);

create index idx_release_site on «tbl_release» (site_id);

-- Release 内冻结的内容版本（FR-PUBLISH-001：固定内容版本号）
create table release_content (
    release_id      «uuid» not null references «tbl_release» (id),
    content_item_id «uuid» not null references content_item (id),
    version         int  not null,
    primary key (release_id, content_item_id)
);
