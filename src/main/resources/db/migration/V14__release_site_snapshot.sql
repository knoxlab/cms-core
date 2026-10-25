alter table «tbl_release» add column template_project_id «uuid»;
alter table «tbl_release» add constraint fk_release_template_project foreign key (template_project_id) references template_project(id);
alter table «tbl_release» add column template_revision integer;
alter table «tbl_release» add column site_snapshot «txt»;
alter table «tbl_release» add column route_manifest «txt»;
alter table «tbl_release» add column input_digest varchar(128);

create table release_page (
    release_id «uuid» not null references «tbl_release»(id),
    page_id «uuid» not null references content_item(id),
    content_version integer not null,
    composition_version integer,
    route_path varchar(1000) not null,
    html_content «txt» not null,
    input_digest varchar(128) not null,
    primary key(release_id,page_id)
);
«idx_v14»;