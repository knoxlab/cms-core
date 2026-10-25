-- ============================================================
-- SSG P1/P3：Release 媒体/重写快照 + 增量工件基线
-- H2 / PostgreSQL 兼容
-- ============================================================

alter table «tbl_release» add column global_input_digest varchar(128);

create table release_media_version (
    release_id      «uuid»         not null references «tbl_release» (id),
    media_asset_id  «uuid»         not null,
    media_version   int          not null,
    content_hash    varchar(128) not null,
    filename        varchar(500) not null,
    mime_type       varchar(100),
    file_size       bigint       not null default 0,
    storage_key     varchar(500) not null,
    output_path     varchar(1000) not null,
    primary key (release_id, media_asset_id)
);

create index idx_rmv_release on release_media_version (release_id);

create table release_url_rewrite (
    release_id   «uuid»          not null references «tbl_release» (id),
    rule_id      «uuid»          not null,
    name         varchar(200)  not null,
    pattern      varchar(512)  not null,
    replacement  varchar(1000) not null,
    priority     int           not null,
    primary key (release_id, rule_id)
);

create index idx_rur_release on release_url_rewrite (release_id);

alter table build_artifact add column base_artifact_id «uuid»;
alter table build_artifact add column artifact_digest varchar(128);
alter table build_artifact add column changed_file_manifest «txt»;
alter table build_artifact add column deleted_file_manifest «txt»;

create index idx_ba_base on build_artifact (base_artifact_id);
