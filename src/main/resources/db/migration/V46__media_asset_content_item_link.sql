-- 媒体资产 ↔ 内容项联动（FR-CM-MEDIA-010/011）：媒体资产应同时作为 MEDIA 内容类型的
-- 内容实例存在，使媒体在内容检索与全局搜索中可发现。
-- 本迁移为 media_asset 增加 content_item_id 关联列，指向其同步生成的 MEDIA 内容项。

alter table media_asset add column content_item_id «uuid»;

create «idx» idx_media_asset_content_item_id on media_asset (content_item_id);
