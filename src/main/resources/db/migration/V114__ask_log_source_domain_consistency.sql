-- FR-AI-RAG-012 跨源引用溯源：问答日志记录本次路由生效来源域与引用来源域标注一致率证据
ALTER TABLE ai_ask_log ADD COLUMN route_source_filter VARCHAR(300);
ALTER TABLE ai_ask_log ADD COLUMN cited_source_domains VARCHAR(1000) NOT NULL DEFAULT '';
ALTER TABLE ai_ask_log ADD COLUMN citation_source_consistent «bool» NOT NULL DEFAULT «lit_t»;
ALTER TABLE ai_ask_log ADD COLUMN citation_source_mismatches INT NOT NULL DEFAULT 0;

«cm_V114»




«idx_v114»
