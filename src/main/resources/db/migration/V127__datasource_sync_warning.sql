-- FR-AI-SRC-001 同步幂等与配置提示：
-- 1) 同步运行记录新增「配置级提示」列：非阻断告警（如 externalKeyField 未映射到任何目标字段时，
--    重复同步的幂等性仅依赖来源事实锚点），供 UI 告警位展示；
-- 2) content_source_fact 补「来源系统 + 外部对象 ID」锚点索引：同步按该键定位既有条目
--    （修复前外部键未映射时每轮重复新建，落点无界累积）。
ALTER TABLE datasource_sync_log ADD COLUMN warning_message VARCHAR(1000);

«cm_V127»

create «idx» idx_content_source_fact_anchor
    ON content_source_fact (site_id, source_system, source_object_id);
