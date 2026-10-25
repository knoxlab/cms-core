-- 阶段三 M8 切片（FLOW 写面）：
-- 审批执行站点开关：FR-AGT-FLOW-004「站点可配置 agent_review_execute=false（默认 false）强制审批在管理台人工完成」。
alter table ai_site_policy add column agent_review_execute «bool» not null default «lit_f»;
