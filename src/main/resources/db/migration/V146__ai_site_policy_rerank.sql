-- FR-AI-KB-043：重排（Rerank）的站点级开关
--
-- 默认 true（开启本地轻量重排）：验收标准要求「开启重排后 nDCG@10 不低于未重排基线」，
-- 而轻量重排把结构特征做成**有界倍率**（原始相关性始终是主信号），
-- 因此默认开启是安全的；站点可显式关闭以回到纯检索分排序。
--
-- 与既有的 ask_routing_enabled / review_brief_enabled 同形：非空布尔 + 默认值，
-- 读取侧不需要处理 null（避免"三态布尔"——那会让"未配置"与"显式关闭"无法区分）。
alter table ai_site_policy add column rerank_enabled «bool» not null default «lit_t»;
