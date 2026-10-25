-- FR-AI-RAG-007 问答黑名单：主题词/内容条目两级（站点级配置）。
-- 命中黑名单的问答请求返回预设话术，不产生模型调用（含审计）。
create table ai_ask_blacklist (
    id «uuid» primary key,
    site_id «uuid» not null,
    -- BLOCKED_TOPIC：主题词（问题文本包含即命中）；BLOCKED_CONTENT：内容条目 ID（不允许进入问答引用）
    match_type varchar(20) not null,
    pattern varchar(500) not null,
    content_id «uuid»,
    -- 命中后返回的预设话术；空则用系统默认话术
    canned_response varchar(1000),
    enabled «bool» not null default «lit_t»,
    created_by varchar(100),
    created_at «ts» not null «def_now»
);
create index idx_ai_ask_bl_site on ai_ask_blacklist (site_id, enabled);
