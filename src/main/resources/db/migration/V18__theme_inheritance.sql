-- P2-1 phase 3: fixed-version theme inheritance
alter table site_theme add column parent_theme_id «uuid»;
alter table site_theme add constraint fk_site_theme_parent foreign key (parent_theme_id) references site_theme(id);
alter table site_theme add column parent_version int;
alter table site_theme_revision add column parent_theme_id «uuid»;
alter table site_theme_revision add constraint fk_site_theme_revision_parent foreign key (parent_theme_id) references site_theme(id);
alter table site_theme_revision add column parent_theme_version int;
create index idx_site_theme_parent on site_theme(parent_theme_id);
create index idx_site_theme_revision_parent on site_theme_revision(parent_theme_id, parent_theme_version);
