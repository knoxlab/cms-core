-- ============================================================
-- P3：在线 IDE — 模板工程、文件、版本快照、构建任务
-- H2 / PostgreSQL 兼容
-- ============================================================

-- 模板工程（FR-IDE-001~002）：站点所属前端工程
create table template_project (
    id              «uuid» primary key,
    site_id         «uuid»         not null references site (id),
    name            varchar(200) not null,
    project_type    varchar(20)  not null default 'REACT', -- REACT / VUE
    runtime_image   varchar(200), -- node:20-alpine
    current_revision int         not null default 0,
    lockfile_hash   varchar(128),
    package_json    «txt»,        -- 最后一次保存的 package.json 原文
    created_at      «ts» not null «def_now»,
    updated_at      «ts» not null «def_now»,
    «uq_sp_name»
);

create index idx_tp_site on template_project (site_id);

-- 模板文件（FR-IDE-001）：文件树、路径限制在工程根目录
create table template_file (
    id                  «uuid» primary key,
    template_project_id «uuid»         not null references template_project (id),
    path                varchar(500) not null, -- 相对路径，禁止 ../ 逃逸
    content             «txt»         not null,
    file_hash           varchar(128), -- SHA-256
    updated_at          «ts» not null «def_now»,
    «uq_v4_tf_path»
);

create index idx_tf_project on template_file (template_project_id);

-- 模板版本（FR-IDE-003）：不可变，记录父版本、文件摘要、作者
create table template_revision (
    id                  «uuid» primary key,
    template_project_id «uuid»         not null references template_project (id),
    version             int          not null,
    parent_version      int,
    author              varchar(100),
    message             varchar(500),
    file_snapshot       «txt»         not null, -- JSON: [{path, content, hash}, ...]
    created_at          «ts» not null «def_now»,
    unique (template_project_id, version)
);

create index idx_tr_project on template_revision (template_project_id);

-- 构建任务（FR-IDE-005）：预览/发布构建跟踪
create table build_job (
    id                  «uuid» primary key,
    template_project_id «uuid»         not null references template_project (id),
    revision            int          not null,
    release_id          «uuid» references «tbl_release» (id), -- 发布构建关联 Release
    job_type            varchar(20)  not null default 'PREVIEW', -- PREVIEW / RELEASE
    status              varchar(20)  not null default 'QUEUED', -- QUEUED / RUNNING / SUCCESS / FAILED / CANCELLED
    started_at          «ts»,
    finished_at         «ts»,
    artifact_url        varchar(1000),
    created_by          varchar(100),
    created_at          «ts» not null «def_now»
);

create index idx_bj_project on build_job (template_project_id);

-- 构建日志流（FR-IDE-005）
create table build_log (
    «col_log_id»,
    build_job_id «uuid»        not null references build_job (id),
    seq         int          not null,
    level       varchar(10)  not null default 'INFO', -- INFO / WARN / ERROR
    line        varchar(2000) not null,
    created_at  «ts» not null «def_now»
);

create index idx_bl_job on build_log (build_job_id, seq);
