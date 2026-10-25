-- P2-1 final phase: pin theme revisions consumed by frozen releases
create table release_theme_version (
    id «uuid» primary key,
    release_id «uuid» not null references «tbl_release»(id) on delete cascade,
    theme_id «uuid» not null references site_theme(id),
    theme_version int not null,
    owner_site_id «uuid» not null references site(id),
    source varchar(20) not null check (source in ('OWNED', 'SHARED')),
    digest varchar(64) not null,
    created_at «ts» not null «def_now»,
    unique(release_id, theme_id)
);
create index idx_release_theme_version_theme on release_theme_version(theme_id, theme_version);
create index idx_release_theme_version_release on release_theme_version(release_id);
