-- P2-1 phase 4: cross-site read-only theme sharing with pinned revisions
create table site_theme_share (
    id «uuid» primary key,
    theme_id «uuid» not null references site_theme(id) on delete cascade,
    consumer_site_id «uuid» not null references site(id) on delete cascade,
    pinned_version int not null,
    status varchar(20) not null,
    created_by varchar(255),
    created_at «ts» not null «def_now»,
    updated_at «ts» not null «def_now»,
    unique(theme_id, consumer_site_id)
);
create index idx_site_theme_share_consumer on site_theme_share(consumer_site_id, status);
create index idx_site_theme_share_theme on site_theme_share(theme_id, status);
