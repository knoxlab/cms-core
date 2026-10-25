-- FR-AI-RAG-011 多源检索路由策略（站点级可配置：问题类型 → 关键词 + 源优先级）
-- 升级前：问题分类关键词与「问题类型 → 来源域」映射均硬编码在 AskSourceRouter；
-- 且首选源无命中时直接返回无答案，无「首选无命中 → 次选源」回退证据。
CREATE TABLE ai_source_route_rule (
    id              «uuid» PRIMARY KEY,
    site_id         «uuid» NOT NULL,
    question_kind   VARCHAR(20) NOT NULL,
    keywords        «txt» NOT NULL,
    source_priority VARCHAR(1000) NOT NULL,
    updated_at      «tstz» NOT NULL,
    updated_by      VARCHAR(200),
    CONSTRAINT uq_ai_source_route_rule UNIQUE (site_id, question_kind)
);

CREATE INDEX ix_ai_source_route_rule_site ON ai_source_route_rule (site_id);

«cm_V113»


