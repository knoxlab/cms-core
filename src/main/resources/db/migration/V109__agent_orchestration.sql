-- 阶段三 FR-AI-AGENT-008：多智能体协同编排。
-- 一次编排 = 一条可回放记录：role 化子代理按确定性计划顺序执行只读/提案工具，
-- 每步记录 role / tool / status / 耗时 / 结果摘要，协同链路可经 get_orchestration 完整回放。
-- 注：编排只执行只读与提案类工具（不落库），落库仍走各自三段式人工确认。

create table agent_orchestration (
    id «uuid» primary key,
    site_id «uuid» not null,
    -- 编排目标：site.build（一句话建站）/ content.launch / site.theme
    goal varchar(60) not null,
    -- PLANNED / RUNNING / COMPLETED / FAILED
    status varchar(20) not null default 'PLANNED',
    -- 计划参数快照（JSON，如 page_spec/style_spec/keyword）
    params_json «txt»,
    -- 子任务计划（JSON 数组：role/tool/args/required_level）
    plan_json «txt»,
    -- 逐步执行回执（JSON 数组：role/tool/status/summary/elapsed_ms/denied_reason）
    steps_json «txt»,
    error_message varchar(2000),
    created_by varchar(100) not null,
    created_at «ts» not null,
    finished_at «ts»
);
create index idx_agent_orchestration_site on agent_orchestration (site_id, created_at);
create index idx_agent_orchestration_status on agent_orchestration (status, created_at);
