-- 媒体内容建模联动（内容模型规格第 20.9 节 + 媒体-Knox 规格 FR-KNX-005/014/024，v1.2）
-- 1) content_type：MEDIA 对象类型的媒体专属配置（FR-CM-MEDIA-001~006/040/050）
--    - media_kind           video/audio/image/document/other（objectType=MEDIA 时必填）
--    - storage_path_pattern 默认存储路径模式（宏 {yyyy}/{mm}/{dd}/{objectId}/{kind}/{siteId}，FR-CM-MEDIA-050）
--    - media_accept         类型级 MIME 白名单（逗号分隔，FR-CM-MEDIA-040）
--    - media_max_file_length 类型级上传字节上限（FR-CM-MEDIA-040）
-- 2) media_asset：媒体内容类型绑定与结构化元数据（FR-KNX-024/FR-KNX-015）
--    - media_type_id  归类的媒体内容类型（预置 media_video 等）
--    - kind           视频/音频/图片/文档/其他（自动归类驱动）
--    - metadata       处理产物结构化元数据 JSON（duration/width/height/format/... 驱动搜索索引）
-- 3) knox_endpoint：多发布环境映射（FR-KNX-005）与存储路由 pattern（FR-KNX-014）
--    - environments   逗号分隔 PREVIEW/STAGING/LIVE（可多选）
--    - route_pattern  路径前缀正则，上传按 target_dir 前缀路由到该端点

alter table content_type add column media_kind varchar(20);
alter table content_type add column storage_path_pattern varchar(500);
alter table content_type add column media_accept varchar(1000);
alter table content_type add column media_max_file_length bigint;

alter table media_asset add column media_type_id «uuid»;
alter table media_asset add column kind varchar(20);
alter table media_asset add column metadata «txt»;

alter table knox_endpoint add column environments varchar(100);
alter table knox_endpoint add column route_pattern varchar(500);

create index idx_media_asset_kind on media_asset (kind);
create index idx_media_asset_media_type on media_asset (media_type_id);
