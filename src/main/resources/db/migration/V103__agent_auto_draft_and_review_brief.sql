-- 阶段三 M7 切片：
-- ① FR-AI-AGENT-002 审批辅助摘要：站点级可关闭开关（默认开启，关闭后工具与审核界面均不产出摘要）。
-- ② FR-AI-AGENT-001 AI 自动起草：站点/栏目级选题规则 + 起草任务追踪（全部产出自动提交审批，GOV-004）。
alter table ai_site_policy add column review_brief_enabled «bool» not null default «lit_t»;

-- 自动起草选题规则：来源材料为 JSON 数组，字段映射决定材料属性写入哪个内容字段。
create table ai_auto_draft_rule (
    id «uuid» primary key,
    site_id «uuid» not null,
    name varchar(200) not null,
    -- 关闭时不参与调度，仅可手动试跑
    enabled «bool» not null default «lit_f»,
    content_type_id «uuid» not null,
    category_id «uuid»,
    folder_id «uuid»,
    -- Spring cron（6 段）；默认每天 09:00
    cron_expr varchar(50) not null default '0 0 9 * * *',
    -- 标题模板，支持 {title}/{topic} 占位符
    title_template varchar(500) not null default '{title}',
    -- 来源材料 JSON 数组：[{title, body, summary, source, url, tags}]
    source_materials «txt» not null,
    -- 材料属性 → 内容字段 code 的映射（JSON）；为 null 时使用内置默认映射
    field_mapping «txt»,
    -- 产出草稿后自动提交审批（默认开启；关闭仅产出草稿，不进入审批流）
    auto_submit «bool» not null default «lit_t»,
    -- 内容创建者 / 审批发起方（委托用户）
    actor varchar(100) not null,
    last_run_at «ts»,
    created_by varchar(100) not null,
    created_at «ts» not null «def_now»,
    updated_at «ts» not null «def_now»
);
create index idx_ai_auto_draft_rule_site on ai_auto_draft_rule (site_id, enabled);

-- 起草任务：一次规则执行；items 记录逐条产出（草稿 ID / 审批任务 ID / 失败原因），单条失败不阻断整体。
create table ai_auto_draft_run (
    id «uuid» primary key,
    site_id «uuid» not null,
    rule_id «uuid» not null,
    trigger_type varchar(20) not null default 'MANUAL',
    status varchar(20) not null default 'PENDING',
    total integer not null default 0,
    succeeded integer not null default 0,
    failed integer not null default 0,
    items «txt»,
    error_message varchar(2000),
    created_by varchar(100) not null,
    created_at «ts» not null «def_now»,
    started_at «ts»,
    finished_at «ts»
);
create index idx_ai_auto_draft_run_site on ai_auto_draft_run (site_id, created_at);
create index idx_ai_auto_draft_run_rule on ai_auto_draft_run (rule_id, created_at);
