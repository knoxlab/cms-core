-- 修正：V56 中 member_progress 缺少单列主键 id（JPA 实体 @Id UUID）。
-- 环境若已用旧版 V56 建表（复合主键），本迁移补列并重建主键；全新环境由 V56 直接建对，本脚本需容错跳过。
-- H2 与 PostgreSQL 语法差异用动态 SQL 规避成本高；此处采用「先删旧约束再加列」的兼容写法仅适用于 PostgreSQL。
-- 为保证两端一致，统一走「删除并重建表」：V56 功能尚未上线，无存量数据。
-- 不用 if exists：Flyway Oracle 方言解析器（达梦走此解析路径）不认该子句会报
-- Incomplete statement；且 V56 先于本脚本执行、表必然存在，裸 drop 五库通用。
drop table member_progress;
create table member_progress (
    id                  «uuid» not null,
    site_id             «uuid» not null,
    member_id           «uuid» not null,
    content_id          «uuid» not null,
    content_type_id     «uuid»,
    folder_id           «uuid»,
    farthest_ms         bigint not null default 0,
    resume_ms           bigint not null default 0,
    watched_ms_total    bigint not null default 0,
    completed           «bool» not null default «lit_f»,
    last_watched_at     «ts»,
    updated_at          «ts» not null «def_now»,
    primary key (id),
    constraint uk_member_progress unique (site_id, member_id, content_id)
);
