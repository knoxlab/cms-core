-- V138：文档知识库底座（FR-AI-KB-001 ~ 005 / 方案 §5.1）
--
-- 方言约束：本仓库不使用字面 SQL 类型，一律用 Flyway 占位符 token 书写（定界符为书名号），
-- 按 profile 导入 powercms-db-placeholders-{ansi,mysql,mysql55,dm}.sql.yml 替换。
-- 时间列必须写 tstz token（ANSI → timestamp with time zone；MySQL → datetime(6)；DM → timestamp with time zone），
-- 绝不写 TIMESTAMPTZ —— V111/V113 曾因字面 TIMESTAMPTZ 导致 H2（MODE=PostgreSQL）下
-- 全部 @SpringBootTest 集成测试无法启动（事故记录见 ai-llm-integration-plan.md v1.25）。
-- 大文本列用 txt token，布尔列用 bool token，UUID 列用 uuid token。
--
-- 注意：占位符定界符**不得**在注释里成对出现却夹着未定义的键名——Flyway 会在注释中
-- 一并解析占位符，未定义的键名会让整条迁移语句解析失败（本文件早期版本即因此报
-- "No value provided for placeholder"）。注释里只允许出现**已定义**的 token。

-- ── 文档知识库单元：一资产一版本一单元（FR-AI-KB-001）────────────────────
create table doc_unit (
    id                      «uuid»       not null,
    site_id                 «uuid»       not null,
    media_asset_id          «uuid»       not null,
    media_version           integer      not null,
    content_item_id         «uuid»,
    knox_endpoint_id        «uuid»,
    knox_media_id           varchar(64)  not null,
    knox_library_id         varchar(64),
    owner_site_id           «uuid»       not null,
    asset_kind              varchar(20),
    -- FR-AI-KB-080：逗号分隔来源类型
    -- DOCUMENT_BODY / MEDIA_TRANSCRIPT / MEDIA_SUMMARY / MEDIA_CHAPTERS / IMAGE_ALT
    source_kinds            varchar(200),

    file_name               varchar(512) not null,
    mime_type               varchar(200),
    file_size_bytes         bigint,
    content_sha256          varchar(64),

    status                  varchar(24)  not null default 'PENDING',
    status_reason           varchar(500),
    parse_fingerprint       varchar(200),
    embedding_version       varchar(64),
    embedding_model         varchar(200),

    parse_engine            varchar(64),
    parse_engine_version    varchar(64),
    parse_markdown          «txt»,
    parse_markdown_bytes    bigint,
    parse_metadata          «txt»,
    parse_stats             «txt»,
    parse_outline           «txt»,
    parse_warnings          «txt»,
    parsed_at               «tstz»,

    chunk_count             integer      not null default 0,
    chunk_vector_count      integer      not null default 0,
    parent_chunk_count      integer      not null default 0,
    indexed_at              «tstz»,
    index_generation        integer      not null default 0,

    publish_status          varchar(20)  not null default 'DRAFT',
    sensitivity             varchar(20),
    valid_from              «tstz»,
    valid_until             «tstz»,

    created_at              «ts»      not null,
    updated_at              «ts»      not null,
    created_by              varchar(64),
    constraint pk_doc_unit primary key (id)
);

-- 一资产一版本一单元：唯一约束是「解析与分块只做一次」的依据（§13.5.1）
create unique index ux_doc_unit_asset_version on doc_unit (media_asset_id, media_version);
create index ix_doc_unit_site_status   on doc_unit (site_id, status);
create index ix_doc_unit_fingerprint   on doc_unit (parse_fingerprint);
create index ix_doc_unit_content_item  on doc_unit (content_item_id);
create index ix_doc_unit_validity      on doc_unit (site_id, valid_until);
-- ACL 解析按 (owner_site_id, knox_library_id) 定位库级可见性，需索引（FR-AI-KB-050/051）
create index ix_doc_unit_library       on doc_unit (owner_site_id, knox_library_id);

-- ── 来源定位块（FR-DR-021）：按 start 有序，支持 char_start → 页码解析 ──────
create table doc_source_block (
    id                  «uuid»      not null,
    doc_unit_id         «uuid»      not null,
    ordinal             integer     not null,
    markdown_start      integer     not null,
    markdown_end        integer     not null,
    locator_type        varchar(16) not null,
    page                integer,
    bbox_x0             double precision,
    bbox_y0             double precision,
    bbox_x1             double precision,
    bbox_y1             double precision,
    block_index         integer,
    slide               integer,
    sheet_name          varchar(200),
    row_start           integer,
    row_end             integer,
    src_start           integer,
    src_end             integer,
    spine_section       integer,
    source_id           varchar(128),
    quote               varchar(400),
    section_title       varchar(500),
    constraint pk_doc_source_block primary key (id)
);
create index ix_dsb_unit_start on doc_source_block (doc_unit_id, markdown_start);
create index ix_dsb_unit_page  on doc_source_block (doc_unit_id, page);

-- ── 表格结构（FR-DR-034）：按需惰性加载，不随分块一起取 ────────────────────
create table doc_table_structure (
    id                  «uuid»  not null,
    doc_unit_id         «uuid»  not null,
    markdown_start      integer not null,
    markdown_end        integer not null,
    page                integer,
    row_count           integer,
    col_count           integer,
    header_rows         integer,
    continued           «bool»  not null default «lit_f»,
    cells_json          «txt»   not null,
    spans_json          «txt»,
    constraint pk_doc_table_structure primary key (id)
);
create index ix_dts_unit on doc_table_structure (doc_unit_id, markdown_start);

-- ── 分块（FR-AI-KB-014 / FR-AI-KB-080~085）：父块与子块同表，以 chunk_type 区分 ──
create table doc_chunk (
    id                  «uuid»        not null,
    doc_unit_id         «uuid»        not null,
    site_id             «uuid»        not null,
    parent_chunk_id     «uuid»,
    -- child | parent_section | parent_document
    chunk_type          varchar(20)   not null,
    -- 文档内序号；父文档块用 -1（DocIdScheme.PARENT_DOCUMENT_ORDINAL），与子块序号空间不重叠
    ordinal             integer       not null,
    -- FR-AI-KB-080：DOCUMENT_BODY / MEDIA_TRANSCRIPT / MEDIA_SUMMARY / MEDIA_CHAPTERS / IMAGE_ALT
    source_kind         varchar(24)   not null default 'DOCUMENT_BODY',

    content             «txt»         not null,
    context_header      varchar(1000),
    embedding_text      «txt»,

    heading_path        «txt»,
    block_type          varchar(20)   not null,
    has_table           «bool»        not null default «lit_f»,
    has_image           «bool»        not null default «lit_f»,

    -- 页码定位（仅 DOCUMENT_BODY 非空）与时间轴定位（仅 MEDIA_TRANSCRIPT 非空）互斥
    page_from           integer,
    page_to             integer,
    time_start_ms       integer,
    time_end_ms         integer,

    markdown_start      integer,
    markdown_end        integer,
    token_estimate      integer,
    char_count          integer,
    content_hash        varchar(64)   not null,

    -- PENDING / OK / FAILED / SKIPPED
    vector_state        varchar(20)   not null,
    vector_failure      varchar(500),
    embedded_at         «tstz»,

    edited_by_human     «bool»        not null default «lit_f»,
    created_at          «ts»        not null,
    updated_at          «ts»        not null,
    constraint pk_doc_chunk primary key (id)
);
create unique index ux_doc_chunk_unit_ordinal on doc_chunk (doc_unit_id, ordinal);
create index ix_doc_chunk_unit_type    on doc_chunk (doc_unit_id, chunk_type);
create index ix_doc_chunk_vector_state on doc_chunk (site_id, vector_state);
create index ix_doc_chunk_content_hash on doc_chunk (doc_unit_id, content_hash);
create index ix_doc_chunk_parent       on doc_chunk (parent_chunk_id);
create index ix_doc_chunk_source       on doc_chunk (doc_unit_id, source_kind);

-- ── 解析任务（FR-AI-KB-004 / 007）：幂等键为 (site_id, request_id) ─────────
create table doc_parse_job (
    id                  «uuid»       not null,
    site_id             «uuid»       not null,
    doc_unit_id         «uuid»,
    media_asset_id      «uuid»       not null,
    media_version       integer      not null,
    mode                varchar(16)  not null,
    request_id          varchar(200) not null,
    knox_task_id        varchar(128),
    knox_endpoint_id    «uuid»,

    status              varchar(24)  not null,
    attempt             integer      not null default 0,
    max_attempts        integer      not null default 3,
    priority            varchar(10)  not null default 'NORMAL',
    progress_phase      varchar(32),
    progress_percent    integer,
    poll_after_ms       integer,
    next_poll_at        «tstz»,

    error_code          varchar(64),
    error_message       varchar(1000),
    retryable           «bool»,

    submitted_at        «tstz»,
    started_at          «tstz»,
    finished_at         «tstz»,
    created_at          «ts»      not null,
    updated_at          «ts»      not null,
    constraint pk_doc_parse_job primary key (id)
);
create unique index ux_dpj_site_request   on doc_parse_job (site_id, request_id);
create index ix_dpj_site_status    on doc_parse_job (site_id, status);
create index ix_dpj_next_poll      on doc_parse_job (status, next_poll_at);
create index ix_dpj_asset_version  on doc_parse_job (media_asset_id, media_version);

-- ── 索引对账与水位（NFR-AI-KB-005）：文档侧补充既有 IndexSyncGate ───────────
create table doc_index_state (
    id                  «uuid»        not null,
    site_id             «uuid»        not null,
    index_alias         varchar(200)  not null,
    doc_chunk_docs      bigint        not null default 0,
    content_item_docs   bigint        not null default 0,
    embedded_version    varchar(64),
    rebuilt_at          «tstz»,
    reconcile_at        «tstz»,
    reconcile_drift     bigint        not null default 0,
    in_sync             «bool»        not null default «lit_f»,
    constraint pk_doc_index_state primary key (id)
);
create unique index ux_dis_site_alias on doc_index_state (site_id, index_alias);

-- ── 站点级文档知识库策略（FR-AI-KB-003 / 011 / 013 / 043 / 076）──────────
create table doc_site_policy (
    site_id                     «uuid»       not null,
    enabled                     «bool»       not null default «lit_t»,
    auto_parse_on_ready         «bool»       not null default «lit_t»,
    parse_on_publish            «bool»       not null default «lit_t»,
    allowed_mime_groups         varchar(200) not null default 'document',
    max_file_mb                 integer      not null default 100,
    max_pages                   integer      not null default 2000,
    excluded_folders            «txt»,
    excluded_tags               «txt»,
    sensitive_doc_kinds         «txt»,
    chunk_target_tokens         integer      not null default 512,
    -- 2026-10-02 评审裁决：token 口径、默认重叠 12%（替代早期「80 token ≈ 15%」）
    chunk_overlap_percent       integer      not null default 12,
    chunk_min_chars             integer      not null default 100,
    chunk_max_chars             integer      not null default 2000,
    -- NONE / SECTION / DOCUMENT（ADR-08，默认章节级）
    parent_context_mode         varchar(16)  not null default 'SECTION',
    context_header_enabled      «bool»       not null default «lit_t»,
    rerank_enabled              «bool»       not null default «lit_t»,
    rerank_model_config_id      «uuid»,
    bm25_weight                 numeric(4,3),
    knn_weight                  numeric(4,3),
    doc_source_priority         integer      not null default 50,
    -- EXCLUDE / VISIBLE（FR-AI-KB-008）
    expired_citation_policy     varchar(16)  not null default 'EXCLUDE',
    backfill_daily_token_limit  bigint,
    backfill_concurrency        integer      not null default 2,
    backfill_window             varchar(64),
    created_at                  «ts»      not null,
    updated_at                  «ts»      not null,
    constraint pk_doc_site_policy primary key (site_id)
);
