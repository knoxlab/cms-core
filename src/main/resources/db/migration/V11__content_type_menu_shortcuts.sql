-- dotCMS-inspired content type shortcuts in the administration content menu.
ALTER TABLE content_type ADD COLUMN content_menu_enabled «bool» NOT NULL DEFAULT «lit_f»;
ALTER TABLE content_type ADD COLUMN content_menu_label VARCHAR(200);
ALTER TABLE content_type ADD COLUMN content_menu_order INTEGER NOT NULL DEFAULT 0;
CREATE INDEX idx_content_type_menu ON content_type (site_id, content_menu_enabled, content_menu_order);
