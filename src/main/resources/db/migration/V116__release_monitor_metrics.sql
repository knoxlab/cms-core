-- 阶段三 FR-AI-AGENT-004 监控口径收尾（G9-④）：
-- 原登记偏差：监控口径为「可用性 + 窗口内失败计数阈值」，规格 §7.3 述「错误率」指标与「持续时长」判定未单独建模。
-- 本迁移为站点级监控策略补两个口径，**默认取值保持既有行为不变**：
--   ① 错误率阈值：窗口内失败部署数 / 窗口内部署总数 ≥ 阈值即判定异常（与既有计数阈值「或」关系）；
--      null 表示关闭（仅用计数阈值，等价于旧行为）；
--   ② 健康劣化持续时长（分钟）：0 表示立即动作（既有行为）；>0 则先落「持续观测中」事件留痕，
--      达到时长仍未恢复才执行通知/自动回退，观测期内恢复则关单为 RECOVERED。
alter table release_monitor_policy add column error_rate_threshold decimal(5,4);
alter table release_monitor_policy add column degraded_duration_minutes integer not null default 0;
