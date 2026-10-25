-- FR-AI-RAG-002：混合检索权重站点可覆盖（默认 BM25 0.4 / k-NN 0.6）。
-- 范围 [0, 1]，站点可独立设置（生效于本站点管理/访客搜索）。
alter table ai_site_policy
    add column bm25_weight double precision not null default 0.4;
alter table ai_site_policy
    add column knn_weight  double precision not null default 0.6;

-- FR-AI-RAG-001：embedding 模型维度配置 + 死信表 + 全量重建进度表。
-- 模型维度随配置实体持久化，避免不同 embedding 模型在同一索引上写入不同维度的向量。
-- 死信表记录 EmbeddingPipelineService 不可恢复的失败（供运维人工干预 + 重建任务排空）。

alter table ai_model_config
    add column embedding_dimensions integer;

-- embedding 死信（FR-AI-RAG-001）：单条/分块 embedding 失败后落死信，不阻塞主流程。
-- content_id 可空：部分调用（如 `embed(siteId, text)`）不带内容上下文。
create table embedding_dead_letter (
    id           «uuid» primary key,
    content_id   «uuid»,
    site_id      «uuid»,
    model        varchar(200) not null,
    failure_kind varchar(20)  not null, -- EMBED / INDEX
    message      varchar(2000),
    attempts     integer      not null default 0,
    created_at   «ts»    not null «def_now»
);

create index idx_embedding_dl_content on embedding_dead_letter (content_id, created_at);
create index idx_embedding_dl_created on embedding_dead_letter (created_at);

-- 全量重建任务进度（FR-AI-RAG-001 / M0）：记录每站点最近一次重建任务的状态、维度版本、已处理/总量。
create table embedding_rebuild_progress (
    site_id              «uuid» primary key,
    status               varchar(20)  not null,        -- PENDING / RUNNING / COMPLETED / FAILED
    embedding_version    varchar(100) not null,        -- powercms.ai.embedding-model@dimension
    total                integer      not null default 0,
    processed            integer      not null default 0,
    succeeded            integer      not null default 0,
    failed               integer      not null default 0,
    started_at           «ts»    not null,
    finished_at          «ts»,
    error_message        varchar(2000)
);

