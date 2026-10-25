-- 阶段三 FR-AGT-MCP-012 × FR-AI-MCP-006 ②「配额联动」收尾（G9-⑥）：
-- 原登记偏差：异步任务的条目在后台线程执行，不经过 MCP 调用维度限流，
-- 「一次创建 + 后台跑 N 条」可绕过 Token 的每分钟/每日调用配额。
-- 本迁移固化任务与调用方 Token 的关联，并登记「因配额超限而暂停」的状态：
--   ① agent_token_id：创建任务时由 MCP 调用线程的 AgentCallContext 固化；后台用户创建为 null（不受限）；
--   ② quota_paused：true 表示任务在条目边界因创建者配额超限转入 PAUSED，配额恢复后可续跑（默认 false，行为不变）。
alter table agent_job add column agent_token_id «uuid»;
alter table agent_job add column quota_paused «bool» not null default «lit_f»;
