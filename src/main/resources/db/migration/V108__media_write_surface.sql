-- 批次 5 媒体写面（FR-AGT-MEDIA-002～006）：补两处领域缺口。
--
-- 1) media_asset.alt_text_model（GOV-007 产物 AI 标识）：
--    此前 MediaAltTextService 的类注释声称「alt_text_model 落资产字段」，但列并不存在，
--    AI 生成的 Alt 文本与人工文本在库内不可区分。补齐后：
--      - AI 生成（media.alt_text 场景）写模型标识；
--      - 人工 / Agent 修订清空模型标识 → 即「人工修订标记」，配合审计可追溯。
--
-- 2) media_folder（FR-AGT-MEDIA-005 「站点媒体目录」）：
--    历史上目录只是 media_asset.folder_path 字符串（上传自动归类派生），没有目录实体，
--    无法校验/枚举/搬移目录。本表建立显式目录树：
--      - path 为物化路径（形如 /images/2026），(site_id, path) 唯一；
--      - 资产仍以 folder_path 字符串挂载（保持既有读取路径零改动）；
--      - 目录搬移时同步重写子目录与资产的 folder_path 前缀；
--      - 目录 ACL 复用站点能力（read/write），目录级独立 ACL 不在本批次范围。
alter table media_asset add column alt_text_model varchar(100);

create table media_folder (
    id «uuid» primary key,
    site_id «uuid» not null,
    parent_id «uuid»,
    name varchar(150) not null,
    path varchar(500) not null,
    sort_order int not null default 0,
    created_at «ts» not null,
    updated_at «ts» not null
);

«idx_v108»;
create index idx_media_folder_parent on media_folder (site_id, parent_id, sort_order);
