-- 阶段三 FR-AGT-MCP-012 偏差收尾：任务结果留存可配置（原登记「结果留存可配置未建模」）。
-- 语义：终态任务（COMPLETED / FAILED）超期后由 AgentJobRetentionScanner 清除明细载荷与结果
--       （agent_job.request_json、agent_job_item.payload_json / result_json），
--       保留任务元数据、计数、条目标签与失败原因，供统计与审计继续使用。
-- 幂等：results_purged_at 非空即视为已清理，扫描与手工清理均不会重复处理（不再重复审计）。
alter table agent_job add column results_purged_at «ts»;

-- 到期候选扫描：按状态 + finished_at 过滤未清理任务。
create index idx_agent_job_retention on agent_job (status, finished_at);
