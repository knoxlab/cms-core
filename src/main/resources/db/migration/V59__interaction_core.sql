-- P1 互动核心：UGC 消息、点赞、举报、审核流/任务、站点设置（H2/PG 兼容）
create table interaction_message (
    id «uuid» primary key,
    site_id «uuid» not null,
    kind varchar(20) not null,
    thread_type varchar(20) not null,
    thread_id «uuid»,
    thread_key varchar(200),
    parent_id «uuid»,
    author_id «uuid»,
    author_name varchar(100) not null,
    author_avatar varchar(500),
    body «txt» not null,
    body_rich «bool» not null default «lit_f»,
    status varchar(20) not null,
    visible «bool» not null default «lit_f»,
    like_count integer not null default 0,
    reply_count integer not null default 0,
    submit_ip_hash varchar(64),
    user_agent varchar(300),
    reviewed_by varchar(100),
    reviewed_at «ts»,
    review_note varchar(500),
    room_seq bigint,
    created_at «ts» not null «def_now»,
    updated_at «ts» not null «def_now»
);
create index idx_msg_thread on interaction_message (site_id, kind, thread_type, thread_id, status, created_at);
create index idx_msg_status on interaction_message (site_id, status, created_at);
create index idx_msg_parent on interaction_message (parent_id);
create index idx_msg_author on interaction_message (site_id, author_id);

create table interaction_report (
    id «uuid» primary key,
    site_id «uuid» not null,
    message_id «uuid» not null,
    reporter_id «uuid»,
    reporter_ip_hash varchar(64),
    dedup_key varchar(80) not null,
    reason varchar(50) not null,
    detail varchar(500),
    status varchar(20) not null default 'OPEN',
    handled_by varchar(100),
    handled_at «ts»,
    created_at «ts» not null «def_now»,
    unique (message_id, dedup_key)
);
create index idx_report_open on interaction_report (site_id, status, created_at);

create table interaction_message_like (
    id «uuid» primary key,
    site_id «uuid» not null,
    message_id «uuid» not null,
    liker_id «uuid»,
    liker_ip_hash varchar(64),
    dedup_key varchar(80) not null,
    liked «bool» not null default «lit_t»,
    created_at «ts» not null «def_now»,
    unique (message_id, dedup_key)
);

create table interaction_review_flow (
    id «uuid» primary key,
    site_id «uuid» not null,
    target_kind varchar(20) not null,
    mode varchar(20) not null,
    steps_json «txt» not null,
    enabled «bool» not null default «lit_t»,
    created_at «ts» not null «def_now»,
    updated_at «ts» not null «def_now»,
    unique (site_id, target_kind)
);

create table interaction_review_task (
    id «uuid» primary key,
    site_id «uuid» not null,
    flow_id «uuid» not null,
    step_order integer not null,
    message_id «uuid»,
    submission_id «uuid»,
    assignee_role varchar(50),
    assignee_user varchar(100),
    status varchar(20) not null,
    required_count integer not null default 1,
    approved_count integer not null default 0,
    «col_comment» varchar(1000),
    due_at «ts»,
    timeout_action varchar(20),
    resolved_by varchar(100),
    resolved_at «ts»,
    created_at «ts» not null «def_now»
);
create index idx_task_queue on interaction_review_task (site_id, assignee_role, status, created_at);
create index idx_task_message on interaction_review_task (message_id);

create table interaction_review_confirmation (
    id «uuid» primary key,
    task_id «uuid» not null,
    actor varchar(100) not null,
    created_at «ts» not null «def_now»,
    unique (task_id, actor)
);

create table interaction_site_settings (
    site_id «uuid» primary key,
    ip_limit_policy varchar(20) not null default 'STRICT',
    api_base varchar(500),
    static_mode varchar(20) not null default 'DYNAMIC',
    captcha varchar(20) not null default 'on',
    allow_guest «bool» not null default «lit_t»,
    hmac_secret varchar(200),
    created_at «ts» not null «def_now»,
    updated_at «ts» not null «def_now»
);
