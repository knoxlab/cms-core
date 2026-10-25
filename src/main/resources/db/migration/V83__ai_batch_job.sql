-- FR-AI-EDIT-006 批量 AI 处理：批量任务（汇总）+ 任务条目（逐条状态，支持中断续跑）。
-- 单条失败不影响其余（条目级 FAILED）；中断后 resume 只跑 PENDING 条目。
create table ai_batch_job (
    id «uuid» primary key,
    site_id «uuid» not null,
    -- SUMMARY：批量生成摘要（ai.suggest.summary 场景）；TRANSLATE：批量翻译
    operation varchar(20) not null,
    -- 批量产物去向：SUGGESTION（AI 建议暂存，EDIT-005 链路）；字段级直写后续扩展
    target varchar(20) not null default 'SUGGESTION',
    -- TRANSLATE 专用
    target_language varchar(10),
    -- 摘要写入字段（默认 summary）
    target_field varchar(100),
    status varchar(20) not null default 'PENDING',
    total integer not null default 0,
    processed integer not null default 0,
    succeeded integer not null default 0,
    failed integer not null default 0,
    created_by varchar(100) not null,
    created_at «ts» not null «def_now»,
    started_at «ts»,
    finished_at «ts»,
    cancelled_at «ts»,
    error_message varchar(2000)
);
create index idx_ai_batch_job_site on ai_batch_job (site_id, status, created_at);

create table ai_batch_job_item (
    id «uuid» primary key,
    job_id «uuid» not null,
    content_item_id «uuid» not null,
    status varchar(20) not null default 'PENDING',
    suggestion_id «uuid»,
    error_message varchar(1000),
    processed_at «ts»,
    foreign key (job_id) references ai_batch_job (id)
);
create index idx_ai_batch_item_job on ai_batch_job_item (job_id, status);
