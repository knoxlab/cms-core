-- PAGE instances are model-owned content. CATEGORY is an optional taxonomy reference.
«alt_dropnn_v10»;
ALTER TABLE content_item ADD COLUMN route_path VARCHAR(1000);
ALTER TABLE content_item ADD COLUMN template_path VARCHAR(500);
-- 页面路由唯一：«uq_route_v10»;
-- （达梦组渲染为 nvl(route_path, cast(id ...)) 函数唯一索引：DM 唯一索引 NULL 不可区分，
--  同站点两条 route_path NULL 即违反；NULL 行以自身 id 兜底等价 PG 的 NULL-distinct 语义）
CREATE INDEX idx_content_template_path ON content_item (template_path);