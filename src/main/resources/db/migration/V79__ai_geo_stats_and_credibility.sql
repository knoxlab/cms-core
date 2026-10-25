-- FR-AI-GEO-004 AI 检索/降级/注入事件埋点
-- FR-AI-GEO-005 内容可信度（来源权威 + 时间新鲜度）

alter table ai_seo_config add column citation_weight double precision not null default 0.4;
alter table ai_seo_config add column freshness_weight double precision not null default 0.3;
alter table ai_seo_config add column authority_weight double precision not null default 0.3;
alter table ai_seo_config add column freshness_decay_days integer not null default 180;

-- AI 检索埋点（按天聚合即可，避免写入风暴）
create table ai_search_event (
    id «uuid» primary key,
    site_id «uuid» not null,
    actor varchar(120) not null,    -- 用户ID / 'anonymous'
    query_hash varchar(64) not null, -- query 文本 SHA-256，不落明文
    hit_count integer not null,
    sem_available «bool» not null,
    degraded «bool» not null,
    latency_ms integer not null,
    created_at «ts» not null «def_now»
);
create index ai_search_event_site_day_idx on ai_search_event(site_id, created_at);

-- AI 注入检测埋点（GOV-005 已记入 ai_injection_event，这里只追加 GEO 维度：检索上下文）
create table ai_search_degrade_event (
    id «uuid» primary key,
    site_id «uuid» not null,
    actor varchar(120) not null,
    reason varchar(100) not null, -- 'sem_unavailable' / 'rate_limited' / 'acl_denied'
    created_at «ts» not null «def_now»
);
create index ai_search_degrade_event_site_idx on ai_search_degrade_event(site_id, created_at);

-- 内容引用计数（每条内容被 AI 引用一次 +1）
create table content_citation_count (
    content_id «uuid» primary key,
    site_id «uuid» not null,
    citation_count bigint not null default 0,
    last_cited_at «ts»,
    authority_score double precision not null default 0.5
);
create index content_citation_count_site_idx on content_citation_count(site_id, citation_count desc);
