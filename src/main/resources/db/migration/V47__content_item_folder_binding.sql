-- 内容项关联栏目（FR-CM-FOLDER-001）：content_item 新增 folder_id 外键
ALTER TABLE content_item ADD COLUMN folder_id «uuid»;
ALTER TABLE content_item ADD CONSTRAINT fk_content_item_folder
    FOREIGN KEY (folder_id) REFERENCES site_folder(id);
CREATE INDEX idx_content_item_folder ON content_item(site_id, folder_id);
