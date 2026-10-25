-- 内容访问量：供投放查询按点击量排序（类似 clist orderType=hits）
alter table content_item add column view_count bigint not null default 0;

create index idx_content_item_view_count on content_item (site_id, content_type_id, view_count desc);
