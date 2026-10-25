-- P2-1 phase 1: persisted theme manifests and binary files
alter table site_theme add column manifest_version varchar(100);
alter table site_theme add column vendor varchar(200);
alter table site_theme add column cms_version_range varchar(100);
alter table site_theme add column validity varchar(20) not null default 'LEGACY';
alter table site_theme add column warnings «txt» not null «def_arr»;

create table site_theme_file (
    id «uuid» primary key,
    theme_id «uuid» not null references site_theme(id) on delete cascade,
    path varchar(1000) not null,
    media_type varchar(255) not null,
    file_size bigint not null,
    sha256 varchar(64) not null,
    content «blob» not null,
    created_at «ts» not null «def_now»,
    «uq_v16»
);
create index idx_site_theme_file_theme on site_theme_file(theme_id);
