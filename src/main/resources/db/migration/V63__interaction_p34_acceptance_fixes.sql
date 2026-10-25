-- P3/P4 验收修复（A-2/A-4/A-5/A-7/A-9/C-1）：
-- 1. 广告素材新增 freq_per_user（用户级频控）与 invalid_impression（异常曝光，仅计数不计费口径）
-- 2. 广告日统计新增 invalid_impression 列（报表「总量/有效量」两列）
-- 3. 聊天消息归档表（§6.4 v1.1 先归档再清理，§11.1 UGC 留存 ≥6 个月）
-- 4. 站点互动设置新增广告追踪同意开关（§11.11）
alter table interaction_ad_item add column freq_per_user integer;
alter table interaction_ad_stat add column invalid_impression bigint not null default 0;

create table interaction_chat_archive (
    id «uuid» primary key,
    site_id «uuid» not null,
    thread_id «uuid» not null,
    room_seq bigint,
    author_id «uuid»,
    author_name varchar(100),
    body «txt»,
    status varchar(20),
    like_count integer not null default 0,
    reply_count integer not null default 0,
    created_at «ts»,
    archived_at «ts» not null «def_now»,
    integrity_hash varchar(64)
);
create index idx_chat_archive_site on interaction_chat_archive (site_id, thread_id);
create index idx_chat_archive_created on interaction_chat_archive (created_at);

alter table interaction_site_settings add column ad_tracking_allowed «bool» not null default «lit_t»;
