-- WEBHOOK 生命周期钩子监听事件筛选（FR-CM-MEDIA-012 扩展）：
-- listen_events 为空/NULL 表示监听全部 Knox webhook 事件；非空时为逗号分隔的事件白名单，
-- 例如 'media.added,metadata.obtained,poster.obtained'。脚本执行时按此筛选。
ALTER TABLE content_type_lifecycle ADD COLUMN listen_events VARCHAR(500);
