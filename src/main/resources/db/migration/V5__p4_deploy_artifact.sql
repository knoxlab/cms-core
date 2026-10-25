-- ============================================================
-- P4：构建产物、发布目标与部署回滚
-- H2 / PostgreSQL 兼容
-- ============================================================

-- 发布目标（FR-PUBLISH-002~003）：Nginx 本地目录 / Git 仓库
create table deploy_target (
    id          «uuid» primary key,
    site_id     «uuid»         not null references site (id),
    name        varchar(200) not null,
    target_type varchar(20)  not null default 'LOCAL_NGINX', -- LOCAL_NGINX / GIT
    config      «txt»         not null, -- JSON: {basePath:, keepReleases:5, gitUrl:, gitBranch:, healthCheckUrl:}
    created_at  «ts» not null «def_now»,
    updated_at  «ts» not null «def_now»,
    «uq_sp_name»
);

create index idx_dt_site on deploy_target (site_id);

-- 构建产物（FR-BUILD-001,007）：不可变，关联 build_job 和 release
create table build_artifact (
    id              «uuid» primary key,
    build_job_id    «uuid» references build_job (id),
    release_id      «uuid»         not null references «tbl_release» (id),
    artifact_path   varchar(500) not null,
    file_count      int          not null default 0,
    size_bytes      bigint       not null default 0,
    route_manifest  «txt»,        -- JSON: [{path,title,contentType}]
    file_manifest   «txt»,        -- JSON: [{path,hash,size}]
    input_digest    varchar(128),
    build_type      varchar(20)  not null default 'FULL', -- FULL / INCREMENTAL
    created_at      «ts» not null «def_now»
);

create index idx_ba_job on build_artifact (build_job_id);
create index idx_ba_release on build_artifact (release_id);

-- 部署记录（FR-PUBLISH-002, 004~007）
create table deployment (
    id                  «uuid» primary key,
    site_id             «uuid»         not null references site (id),
    release_id          «uuid»         not null references «tbl_release» (id),
    deploy_target_id    «uuid»         not null references deploy_target (id),
    artifact_id         «uuid» references build_artifact (id),
    build_type          varchar(20)  not null default 'FULL', -- FULL / INCREMENTAL
    status              varchar(20)  not null default 'QUEUED',
    -- QUEUED / BUILDING / VALIDATING / DEPLOYING / INDEXING / DEPLOYED / FAILED / ROLLED_BACK / CANCELLED
    previous_deployment_id «uuid» references deployment (id),
    health_check_before varchar(20),
    health_check_after  varchar(20),
    started_at          «ts»,
    finished_at         «ts»,
    scheduled_at        «ts», -- 排期发布（FR-PUBLISH-004）
    deployed_by         varchar(100),
    note                varchar(1000),
    created_at          «ts» not null «def_now»
);

create index idx_deploy_site on deployment (site_id);
create index idx_deploy_target on deployment (deploy_target_id);
create index idx_deploy_status on deployment (status);
