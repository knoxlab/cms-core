-- FR-AI-SRC-005 图谱节点来源事实字段 + DAM/PIM 原生节点（验收缺口：节点唯一来源为 content_item，
-- 无独立 DAM/PIM 原生对象与来源事实字段：外部对象 ID / 来源版本 / 有效期 / 版权 / 同步游标）。
--
-- ① 节点来源：CONTENT（content_item 投影，默认，行为不变）/ NATIVE（DAM/PIM 外部对象直接建模）。
-- ② 来源事实字段：外部对象 ID、来源系统、来源版本、审批状态、有效期、版权、同步游标。
-- ③ 原生节点无对应 content_item，故 content_type_id 允许为空。
ALTER TABLE kg_node ADD COLUMN node_origin VARCHAR(20) NOT NULL DEFAULT 'CONTENT';
ALTER TABLE kg_node ADD COLUMN source_system VARCHAR(100);
ALTER TABLE kg_node ADD COLUMN external_object_id VARCHAR(500);
ALTER TABLE kg_node ADD COLUMN source_version VARCHAR(100);
ALTER TABLE kg_node ADD COLUMN approval_status VARCHAR(20);
ALTER TABLE kg_node ADD COLUMN valid_from «tstz»;
ALTER TABLE kg_node ADD COLUMN valid_until «tstz»;
ALTER TABLE kg_node ADD COLUMN copyright VARCHAR(1000);
ALTER TABLE kg_node ADD COLUMN sync_cursor VARCHAR(500);
«alt_dropnn_v112»;

create «idx» idx_kg_node_origin ON kg_node (site_id, node_origin);
create «idx» idx_kg_node_external ON kg_node (site_id, source_system, external_object_id);
create «idx» idx_kg_node_valid_until ON kg_node (valid_until);

«cm_V112»


