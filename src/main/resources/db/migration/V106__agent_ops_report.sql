-- 阶段三 AGENT-005（AI 运营洞察报告：定期生成 / 订阅分发 / 导出）：
-- ① 报告定义：站点级模板，period 决定定期边界，sections 选择报告章节（发布量/阅读播放/AI 引用）。
-- ② 报告快照：一次生成一条，content_json 为可导出正文，生成即冻结（口径与统计模块一致）。
-- ③ 订阅：定义 + 接收人 + 渠道；④ 分发留痕：报告生成后按订阅落一条投递记录（未接外部渠道时仅站内留痕）。

create table ai_ops_report_definition (
    id «uuid» primary key,
    site_id «uuid» not null,
    name varchar(200) not null,
    -- DAILY / WEEKLY / MONTHLY：决定「最近一个已结束周期」的边界
    period varchar(20) not null default 'WEEKLY',
    -- JSON 数组：PUBLISH / TRAFFIC / AI_CITATION
    sections «txt» not null,
    enabled «bool» not null default «lit_f»,
    last_generated_at «ts»,
    created_by varchar(100) not null,
    created_at «ts» not null «def_now»,
    updated_at «ts» not null «def_now»
);
create index idx_ai_ops_report_def_site on ai_ops_report_definition (site_id, enabled);

create table ai_ops_report (
    id «uuid» primary key,
    site_id «uuid» not null,
    -- 手工即时报告无定义（一次性生成），定期报告携带来源定义
    definition_id «uuid»,
    name varchar(200) not null,
    period varchar(20) not null,
    period_start date not null,
    period_end date not null,
    -- GENERATED / FAILED
    status varchar(20) not null default 'GENERATED',
    sections «txt» not null,
    content_json «txt»,
    summary varchar(2000),
    error_message varchar(2000),
    -- MANUAL / SCHEDULED
    trigger_type varchar(20) not null default 'MANUAL',
    generated_by varchar(100) not null,
    generated_at «ts» not null
);
create index idx_ai_ops_report_site on ai_ops_report (site_id, generated_at);

create table ai_ops_report_subscription (
    id «uuid» primary key,
    site_id «uuid» not null,
    definition_id «uuid» not null,
    recipient varchar(200) not null,
    -- INBOX（站内留痕）/ EMAIL（外部渠道接入点，当前仅留痕不实发）
    channel varchar(20) not null default 'INBOX',
    enabled «bool» not null default «lit_t»,
    created_by varchar(100) not null,
    created_at «ts» not null «def_now»
);
create index idx_ai_ops_report_sub_def on ai_ops_report_subscription (definition_id, enabled);

create table ai_ops_report_delivery (
    id «uuid» primary key,
    site_id «uuid» not null,
    report_id «uuid» not null,
    subscription_id «uuid»,
    recipient varchar(200) not null,
    channel varchar(20) not null,
    -- DELIVERED（站内留痕成功）/ SKIPPED（订阅停用或渠道未接入）
    status varchar(20) not null,
    detail varchar(1000),
    delivered_at «ts» not null
);
create index idx_ai_ops_report_delivery_report on ai_ops_report_delivery (report_id);
create index idx_ai_ops_report_delivery_site on ai_ops_report_delivery (site_id, delivered_at);
