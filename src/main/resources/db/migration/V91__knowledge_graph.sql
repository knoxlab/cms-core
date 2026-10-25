-- FR-AI-SRC-005：多源知识图谱——CMS/DAM/PIM 内容对象与关系建模为可导航的图，
-- 节点/边带来源域（source_domain）与可信度（trust_level）标记，随内容发布增量更新。
--
-- 通用适配器形态：不引入外部图数据库，用两表（节点 + 边）建模，关系实例（content_relation_item）
-- 与模板挂链（relation_template_link_log）是边的唯一来源；内容条目是节点唯一来源。
-- Agent 按图遍历（BFS）与可视化查询都走这两张表。

-- 图节点：与 content_item 1:1（同主键）。
CREATE TABLE kg_node (
    id «uuid» NOT NULL,                            -- = content_item.id
    site_id «uuid» NOT NULL,
    title VARCHAR(500) NOT NULL,
    content_type_id «uuid» NOT NULL,
    status VARCHAR(20) NOT NULL,
    source_domain VARCHAR(100) NOT NULL,         -- CMS / DAM:<端点> / PIM:<数据源>（SRC-003）
    trust_level INT NOT NULL,                    -- 0..3（SRC-004）
    node_kind VARCHAR(20) NOT NULL,              -- PAGE / MEDIA / DATA（PIM 参数行）
    updated_at «tstz» NOT NULL,
    CONSTRAINT pk_kg_node PRIMARY KEY (id)
);
CREATE INDEX idx_kg_node_site ON kg_node (site_id);
CREATE INDEX idx_kg_node_source ON kg_node (site_id, source_domain);

-- 图边：与 content_relation_item 1:1（同主键），方向沿关系定义 source → target。
CREATE TABLE kg_edge (
    id «uuid» NOT NULL,                            -- = content_relation_item.id
    site_id «uuid» NOT NULL,
    relation_id «uuid» NOT NULL,
    relation_name VARCHAR(200) NOT NULL,
    source_node «uuid» NOT NULL,                   -- kg_node.id（content_item.id）
    target_node «uuid» NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    updated_at «tstz» NOT NULL,
    CONSTRAINT pk_kg_edge PRIMARY KEY (id)
);
CREATE INDEX idx_kg_edge_site ON kg_edge (site_id);
CREATE INDEX idx_kg_edge_source ON kg_edge (source_node);
CREATE INDEX idx_kg_edge_target ON kg_edge (target_node);
