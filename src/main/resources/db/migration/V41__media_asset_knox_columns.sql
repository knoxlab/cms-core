-- P1 媒体管理：media_asset 扩展（FR-KNX-012/004）
-- source 标记资产来源：KNOX（默认，经 Knox 上传）/ LOCAL（降级本地盘）/ EXTERNAL（外部 URL 引用）。
-- owner_site_id 冗余 site_id 用于共享库场景下区分「上传来源站点」与「当前可见站点」；folder_path 支持库内目录组织。

alter table media_asset add column source varchar(20) not null default 'KNOX';
alter table media_asset add column owner_site_id «uuid»;
alter table media_asset add column folder_path varchar(500);

update media_asset set owner_site_id = site_id where owner_site_id is null;
