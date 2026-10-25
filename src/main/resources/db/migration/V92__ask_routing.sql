-- FR-AI-RAG-011 多源检索路由：AI 问答按问题类型路由到不同内容源。
-- 站点级路由开关与规则（JSON 规则数组，按序匹配；null=默认规则）。
-- 规则形态：{"keywords": ["电压","参数"], "sourceDomain": "PIM:", "priority": 10}
--   keywords     问题命中的关键词（任意一个即命中，大小写不敏感）
--   sourceDomain 来源域前缀（"PIM:" / "DAM:" / "CMS"），作为索引过滤（前缀匹配）
--   priority     数值越小越优先
-- 默认规则（代码内）：产品参数类→PIM、文件资料类→DAM、其余→CMS（不过滤）。
ALTER TABLE ai_site_policy
    ADD COLUMN ask_routing_enabled «bool» NOT NULL DEFAULT «lit_t»;

«cm_V92»
