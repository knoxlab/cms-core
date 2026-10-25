-- FR-AI-SRC-003：跨源内容关系建模（产品 → 参数表(PIM) + 说明书(DAM) + FAQ(CMS)）。
-- 两部分：① content_item.source_domain 来源域标记；② 关系模板（跨源挂链规则）。

-- ① 来源域：内容条目的来源系统标记，取值 "CMS" / "DAM:<endpointName>" / "PIM:<datasourceName>"。
--    SRC-001 同步创建的条目写 PIM:<名称>；SRC-002 媒体同步的 MEDIA 条目写 DAM:<端点名>；
--    人工/编辑器创建的条目保持 CMS（默认）。联合检索命中带此标注（FR-AI-SRC-003 验收）。
ALTER TABLE content_item
    ADD COLUMN source_domain VARCHAR(100) NOT NULL DEFAULT 'CMS';

create «idx» idx_content_item_source_domain
    ON content_item (source_domain);

-- ② 关系模板：站点级「外部记录 → 锚点内容」自动挂链规则。
--    数据源同步（SRC-001）成功 upsert 条目后，按模板把新条目与锚点产品（锚点条目
--    最新版本 fieldValues 中 anchorField 的值 == 记录 anchorValue）建立 content_relation 实例。
CREATE TABLE relation_template (
    id «uuid» NOT NULL,
    site_id «uuid» NOT NULL,
    name VARCHAR(200) NOT NULL,
    datasource_id «uuid» NOT NULL,                 -- PIM 数据源（SRC-001）
    relation_name VARCHAR(200) NOT NULL,         -- 目标 content_relation.relation_name
    anchor_field VARCHAR(100) NOT NULL,          -- 锚点字段：产品条目 fieldValues 中的字段 code
    record_field VARCHAR(100) NOT NULL,          -- 记录字段：外部记录 fieldValues 中携带锚点值的字段 code
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE / PAUSED
    created_by VARCHAR(100),
    created_at «tstz» NOT NULL,
    updated_at «tstz» NOT NULL,
    CONSTRAINT pk_relation_template PRIMARY KEY (id),
    «uq_v89_site»
);
CREATE INDEX idx_relation_template_ds ON relation_template (datasource_id);

-- 挂链审计：模板触发的自动关联记录（去重 + 可追溯）。
CREATE TABLE relation_template_link_log (
    id «uuid» NOT NULL,
    template_id «uuid» NOT NULL,
    datasource_id «uuid» NOT NULL,
    record_content_id «uuid» NOT NULL,             -- 同步创建/更新的条目（PIM 侧）
    anchor_content_id «uuid» NOT NULL,             -- 锚点条目（CMS 产品）
    relation_id «uuid» NOT NULL,
    created_at «tstz» NOT NULL,
    CONSTRAINT pk_relation_template_link_log PRIMARY KEY (id),
    CONSTRAINT uq_link_log_unique UNIQUE (template_id, record_content_id, anchor_content_id)
);
CREATE INDEX idx_link_log_template ON relation_template_link_log (template_id, created_at);
