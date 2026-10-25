-- FR-CV-VER-001/003/006：版本快照需包含条目级系统字段，否则「查看此版本」只能看到模型字段、
-- 「恢复到此版本」（FR-CV-VER-004）也无法还原标题 / 页面路径 / 页面模板。
-- 既有历史行无法精确回溯，迁移时按「该内容条目当前值」回填（标题类改动此前只落在 content_item）。
-- 说明：这三列对非 PAGE 型内容为 null（route_path / template_path），title 恒有值。
ALTER TABLE content_version
    ADD COLUMN title VARCHAR(500);
ALTER TABLE content_version
    ADD COLUMN route_path VARCHAR(1000);
ALTER TABLE content_version
    ADD COLUMN template_path VARCHAR(500);

UPDATE content_version
   SET title = (SELECT ci.title FROM content_item ci WHERE ci.id = content_version.content_item_id)
 WHERE title IS NULL;

UPDATE content_version
   SET route_path = (SELECT ci.route_path FROM content_item ci WHERE ci.id = content_version.content_item_id)
 WHERE route_path IS NULL
   AND EXISTS (SELECT 1 FROM content_item ci WHERE ci.id = content_version.content_item_id AND ci.route_path IS NOT NULL);

UPDATE content_version
   SET template_path = (SELECT ci.template_path FROM content_item ci WHERE ci.id = content_version.content_item_id)
 WHERE template_path IS NULL
   AND EXISTS (SELECT 1 FROM content_item ci WHERE ci.id = content_version.content_item_id AND ci.template_path IS NOT NULL);

«cm_V120»


