-- P2-1 phase 2: immutable theme package revisions
alter table site_theme add column current_version int not null default 0;
alter table site_theme add column lock_version bigint not null default 0;

create table site_theme_revision (
    id «uuid» primary key,
    theme_id «uuid» not null references site_theme(id) on delete cascade,
    version int not null,
    parent_version int,
    author varchar(255),
    message varchar(1000),
    manifest_snapshot «txt» not null,
    file_digest varchar(64) not null,
    created_at «ts» not null «def_now»,
    unique(theme_id, version)
);
create index idx_site_theme_revision_theme on site_theme_revision(theme_id, version desc);

create table site_theme_revision_file (
    id «uuid» primary key,
    theme_revision_id «uuid» not null references site_theme_revision(id) on delete cascade,
    path varchar(1000) not null,
    media_type varchar(255) not null,
    file_size bigint not null,
    sha256 varchar(64) not null,
    content «blob» not null,
    «uq_v17»
);
create index idx_site_theme_revision_file_revision on site_theme_revision_file(theme_revision_id);
