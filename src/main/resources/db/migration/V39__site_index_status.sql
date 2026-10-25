-- P2 工作流与交付：访客索引状态登记（FR-SEARCH-007 / FR-SITE-ENV-006）
-- 每站点一行，记录最近一次重建对应的 releaseId 与时间；部署门禁据此校验一致性。

create table site_index_status (
    site_id            «uuid» primary key,
    index_alias        varchar(200) not null,
    last_release_id    «uuid» not null,
    document_count     bigint not null default 0,
    rebuilt_at         «ts» not null «def_now»
);
