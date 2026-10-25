-- 阶段三 平台契约（FR-AGT-MCP-012）：通用异步任务句柄。
-- ① agent_job：一次长任务（批量起草、全量校验…）的汇总句柄，供 get_task 轮询进度、cancel_task 协作式取消；
--    任务与站点/审计联动，PENDING/PAUSED 状态可被扫描器断点续跑（crash 后仅续跑未完成条目）。
-- ② agent_job_item：任务条目快照（创建时固化），逐条独立落库，单条失败不阻断整体。
-- 注：表名刻意避开 V101 工作台事务表 agent_task（该表承载「预览→确认」状态），二者语义不同。

create table agent_job (
    id «uuid» primary key,
    site_id «uuid» not null,
    -- 任务类型标识，决定由哪个 AgentJobHandler 执行（当前：BATCH_DRAFT）
    task_type varchar(40) not null,
    -- PENDING / RUNNING / PAUSED / COMPLETED / FAILED
    status varchar(20) not null default 'PENDING',
    total int not null default 0,
    processed int not null default 0,
    succeeded int not null default 0,
    failed int not null default 0,
    request_json «txt»,
    error_message varchar(2000),
    created_by varchar(100) not null,
    created_at «ts» not null,
    started_at «ts»,
    finished_at «ts»,
    cancelled_at «ts»
);
create index idx_agent_job_site on agent_job (site_id, created_at);
create index idx_agent_job_status on agent_job (status, created_at);

create table agent_job_item (
    id «uuid» primary key,
    job_id «uuid» not null,
    item_index int not null,
    label varchar(300),
    payload_json «txt»,
    -- PENDING / RUNNING / SUCCEEDED / FAILED
    status varchar(20) not null default 'PENDING',
    result_json «txt»,
    error_message varchar(2000),
    processed_at «ts»
);
create index idx_agent_job_item_job on agent_job_item (job_id, status, item_index);
