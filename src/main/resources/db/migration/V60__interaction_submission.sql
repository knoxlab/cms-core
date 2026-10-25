-- P2 互动：问卷/考试答卷与防重复提交（H2/PG 兼容）
create table interaction_submission (
    id «uuid» primary key,
    site_id «uuid» not null,
    kind varchar(10) not null,
    definition_id «uuid» not null,
    definition_ver integer,
    member_id «uuid»,
    author_name varchar(100),
    anonymous «bool» not null default «lit_f»,
    answers_json «txt» not null,
    score numeric(8,2),
    total_score numeric(8,2),
    passed «bool»,
    grade_status varchar(20) not null default 'AUTO_GRADED',
    grader varchar(100),
    grade_note varchar(1000),
    started_at «ts»,
    submitted_at «ts» not null «def_now»,
    duration_sec integer,
    submit_ip_hash varchar(64),
    status varchar(20) not null default 'SUBMITTED',
    created_at «ts» not null «def_now»,
    updated_at «ts» not null «def_now»
);
create index idx_sub_def on interaction_submission (site_id, definition_id, submitted_at);
create index idx_sub_grade on interaction_submission (site_id, grade_status, submitted_at);
create index idx_sub_member on interaction_submission (site_id, member_id);

create table interaction_submission_guard (
    id «uuid» primary key,
    site_id «uuid» not null,
    submission_id «uuid» not null,
    member_id «uuid»,
    idem_key varchar(120) not null,
    created_at «ts» not null «def_now»,
    unique (idem_key)
);
