-- FR-AI-EDIT-005：AI 建议态暂存（不进入内容版本历史；人工确认后才写入草稿并生成版本）
create table ai_content_suggestion (
    id «uuid» primary key,
    site_id «uuid» not null references site (id),
    content_item_id «uuid» not null references content_item (id),
    content_type_id «uuid» not null references content_type (id),
    field_code varchar(100) not null,
    suggestion_text «txt» not null,
    scene_key varchar(100),
    ai_model varchar(200),
    ai_prompt_version integer,
    expected_version integer not null,
    status varchar(20) not null default 'PENDING',
    created_by varchar(100) not null,
    created_at «ts» not null,
    resolved_by varchar(100),
    resolved_at «ts»,
    note varchar(1000)
);
create index idx_ai_suggestion_site_status on ai_content_suggestion (site_id, status, created_at);
