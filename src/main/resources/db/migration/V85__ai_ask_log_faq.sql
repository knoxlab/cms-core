-- FR-AI-GEO-006 FAQ/问答页结构建议 —— 数据基础：站内问答日志持久化。
-- 此前问答仅走 slf4j 埋点（SearchAnalyticsService），无结构化落库；本迁移补 ai_ask_log 表
-- 作为 GEO-006 高频问题聚合的权威数据源（规划实现步骤①「从问答日志聚高频问题」）。
-- 隐私口径与 FR-AI-GEO-004 一致：question_hash 存 SHA-256 归一化指纹用于聚合计数，
-- question_text 明文保留用于 FAQ 草稿生成（站点内部数据，不外发）。

create table ai_ask_log (
    id «uuid» not null,
    site_id «uuid» not null,
    scope varchar(10) not null,
    question_text varchar(1000) not null,
    question_hash varchar(64) not null,
    answer_ok «bool» not null,
    blocked «bool» not null,
    no_answer «bool» not null,
    model varchar(200),
    actor varchar(100),
    created_at «ts» not null,
    lob_init text, -- DM 哨兵：建表含 LOB 解除达梦 cluster+lob add 限制（其他库无害，不映射实体）
    primary key (id)
);

create index idx_ask_log_site_time on ai_ask_log (site_id, created_at);
create index idx_ask_log_site_hash on ai_ask_log (site_id, question_hash);

-- FR-AI-GEO-006 FAQ 结构建议基线提示词（LLM-009 受控资产）。
-- 变量：questions=高频问题清单（[n] 问题 xN 次）、content=命中内容快照（可选）。
-- 产出契约：单个 JSON 对象 {"question": "...", "answer": "...", "tags": ["..."]}，
-- 由输出解析器做 JSON 剥壳与字段校验，解析失败按生成失败处理（不入审阅流）。
insert into ai_prompt_template (id, scene_key, version, content, status, baseline, approval_id, created_by, created_at) values
('e0000000-0000-0000-0000-000000000102','ai.geo.faq.draft',1,
'你是企业站点的内容运营助手。请依据下列高频用户问题与相关内容素材，生成一条 FAQ 条目草稿。

规则：
1. question 字段：使用干净、完整的问句概括这组高频问题（去除口语与重复，保持用户原意）；
2. answer 字段：仅依据 content 素材中的事实作答，禁止编造素材之外的信息；素材不足时，answer 给出「建议补充内容后再发布」的说明；
3. answer 中引用素材事实时，须使用来源编号标记，如 [1]，编号只能取自素材中已有的编号；
4. tags 字段：给出 1~3 个主题标签建议；
5. 只输出一个 JSON 对象，不要输出任何其他文字或代码块标记。

高频问题：
{{questions}}

内容素材：
{{content}}

请输出 JSON：{"question": "...", "answer": "...", "tags": ["..."]}',
'ACTIVE',«lit_t»,null,'system',current_timestamp);
