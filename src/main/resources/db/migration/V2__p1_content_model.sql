-- ============================================================
-- P1：内容模型、栏目、内容与版本、媒体资产
-- H2（本地开发）与 PostgreSQL（生产）兼容语法
-- ============================================================

-- 内容模型（FR-MODEL-001~007）：版本化，已发布版本不可原地破坏性修改
create table content_type (
    id              «uuid» primary key,
    site_id         «uuid»         not null references site (id),
    code            varchar(100) not null,
    name            varchar(200) not null,
    description     varchar(1000),
    version         int          not null default 1,
    status          varchar(20)  not null default 'DRAFT', -- DRAFT / PUBLISHED
    created_at      «ts» not null «def_now»,
    updated_at      «ts» not null «def_now»,
    lob_init        text, -- DM 哨兵：建表含 LOB 解除达梦 cluster+lob add 限制（其他库无害，不映射实体）
    unique (site_id, code, version)
);

create index idx_content_type_site on content_type (site_id);

-- 字段定义（FR-MODEL-002~003,006）：类型、校验、国际化、搜索配置
create table field_definition (
    id                «uuid» primary key,
    content_type_id   «uuid»         not null references content_type (id),
    code              varchar(100) not null,
    name              varchar(200) not null,
    field_type        varchar(30)  not null, -- STRING/LONG_TEXT/RICH_TEXT/INTEGER/DECIMAL/BOOLEAN/DATE_TIME/ENUM/OBJECT/LIST/CONTENT_REF/MEDIA_REF/TAXONOMY_REF
    required          «bool»      not null default «lit_f»,
    sort_order        int          not null default 0,
    group_name        varchar(100),
    default_value     varchar(500),
    validation        «txt»,        -- JSON: {minLength,maxLength,pattern,min,max,enum[...]}
    i18n              «bool»      not null default «lit_f»,
    admin_index       «bool»      not null default «lit_f»,   -- 进入管理索引
    public_index      «bool»      not null default «lit_f»,   -- 进入访客索引（敏感字段禁止）
    sortable          «bool»      not null default «lit_f»,
    filterable        «bool»      not null default «lit_f»,
    highlight         «bool»      not null default «lit_f»,
    unique (content_type_id, code)
);

-- 栏目（FR-CONTENT-001~002）：无环树，支持排序、路径别名、允许模型
create table category (
    id              «uuid» primary key,
    site_id         «uuid»         not null references site (id),
    parent_id       «uuid» references category (id),
    name            varchar(200) not null,
    path_alias      varchar(400),
    sort_order      int          not null default 0,
    depth           int          not null default 0,
    status          varchar(20)  not null default 'ACTIVE',
    created_at      «ts» not null «def_now»,
    updated_at      «ts» not null «def_now»
);

create index idx_category_site on category (site_id);
create index idx_category_parent on category (parent_id);

-- 栏目允许的内容模型（FR-MODEL-007 引用图）
create table category_content_type (
    category_id     «uuid» not null references category (id),
    content_type_id «uuid» not null references content_type (id),
    primary key (category_id, content_type_id)
);

-- 内容逻辑实体（FR-CONTENT-003~005）
create table content_item (
    id              «uuid» primary key,
    site_id         «uuid»         not null references site (id),
    category_id     «uuid»         not null references category (id),
    content_type_id «uuid»         not null references content_type (id),
    title           varchar(500) not null,
    status          varchar(20)  not null default 'DRAFT', -- DRAFT/IN_REVIEW/APPROVED/PUBLISHED/WITHDRAWN/ARCHIVED
    current_version int          not null default 0,
    published_version int,
    created_by      varchar(100),
    created_at      «ts» not null «def_now»,
    updated_at      «ts» not null «def_now»
);

create index idx_content_site on content_item (site_id);
create index idx_content_category on content_item (category_id);
create index idx_content_type on content_item (content_type_id);
create index idx_content_status on content_item (status);

-- 不可变内容版本（FR-CONTENT-003~004）：每次保存创建新版本
create table content_version (
    id              «uuid» primary key,
    content_item_id «uuid»         not null references content_item (id),
    version         int          not null,
    field_values    «txt»         not null, -- JSON: {fieldCode: {locale1: value, locale2: value, ...}}
    model_version   int          not null,
    note            varchar(1000),
    created_by      varchar(100) not null,
    created_at      «ts» not null «def_now»,
    unique (content_item_id, version)
);

create index idx_content_version_item on content_version (content_item_id);

-- 媒体逻辑实体（FR-ASSET-001~006）
create table media_asset (
    id              «uuid» primary key,
    site_id         «uuid»         not null references site (id),
    title           varchar(500),
    alt_text        varchar(500),
    filename        varchar(500) not null,
    content_hash    varchar(128) not null, -- SHA-256 去重键
    mime_type       varchar(100),
    file_size       bigint       not null default 0,
    copyright       varchar(500),
    tags            varchar(1000),
    status          varchar(20)  not null default 'ACTIVE',
    current_version int          not null default 0,
    created_at      «ts» not null «def_now»,
    updated_at      «ts» not null «def_now»,
    lob_init        text -- DM 哨兵：建表含 LOB 解除达梦 cluster+lob add 限制（其他库无害，不映射实体）
);

create index idx_media_site on media_asset (site_id);
create index idx_media_hash on media_asset (content_hash);

-- 媒体版本（FR-ASSET-004：替换创建新版本，保留旧版本）
create table media_version (
    id              «uuid» primary key,
    media_asset_id  «uuid»         not null references media_asset (id),
    version         int          not null,
    content_hash    varchar(128) not null,
    filename        varchar(500) not null,
    mime_type       varchar(100),
    file_size       bigint       not null default 0,
    storage_key     varchar(500) not null, -- 对象存储 key
    stored_at       «ts»    not null «def_now»,
    lob_init        text, -- DM 哨兵：建表含 LOB 解除达梦 cluster+lob add 限制（其他库无害，不映射实体）
    unique (media_asset_id, version)
);
