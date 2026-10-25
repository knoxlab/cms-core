-- FR-AI-GEO-004 AI 引用统计：问答日志增补被引用内容条目，支撑「内容详情页 AI 引用统计面板」。
-- 数据口径（规划 §6.4）：以站内 AI 问答引用日志为基础（首次版本），第三方可见性数据导入为可选。
-- cited_content_ids：本次答案实际引用的内容条目 ID（JSON 数组，取自 RAG-005 引用校验器
-- 输出的 usedHits，仅含通过校验的真实引用；无答案/被拦截为 '[]'）。
ALTER TABLE ai_ask_log
    ADD COLUMN cited_content_ids «txt» NOT NULL «def_arr»;

«cm_V93»
