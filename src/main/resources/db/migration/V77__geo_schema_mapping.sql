-- FR-AI-GEO-001：内容类型 → schema.org 类型映射。
-- 默认 Article；站点可按 code 指定 NewsArticle / FAQPage / Product / Organization 等。
-- 同时提供 meta 长度默认值（80/160，可被 ai_meta_config 覆盖）。
alter table content_type
    add column schema_org_type varchar(50) not null default 'Article';

-- FR-AI-GEO-003：站点级 meta 长度配置 + SimHash 重复度阈值（默认 0.9）。
create table ai_seo_config (
    site_id              «uuid» primary key,
    meta_title_max       integer     not null default 80,
    meta_description_max integer     not null default 160,
    simhash_duplicate_threshold double precision not null default 0.9,
    json_ld_enabled      «bool»     not null default «lit_t»,
    auto_meta_enabled    «bool»     not null default «lit_t»,
    updated_at           «ts»   not null «def_now»
);
