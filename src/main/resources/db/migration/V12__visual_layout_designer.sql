-- Visual template designer resources: Theme -> Template -> DropZone -> Container
create table site_theme (
    id «uuid» primary key,
    site_id «uuid» not null references site(id),
    name varchar(200) not null,
    description varchar(1000),
    config «txt» not null «def_obj»,
    status varchar(20) not null default 'DRAFT',
    created_at «ts» not null «def_now»,
    updated_at «ts» not null «def_now»,
    «uq_ns_name»
);
create index idx_site_theme_site on site_theme(site_id);

create table site_template (
    id «uuid» primary key,
    site_id «uuid» not null references site(id),
    theme_id «uuid» references site_theme(id),
    name varchar(200) not null,
    description varchar(1000),
    layout_json «txt» not null «def_obj»,
    status varchar(20) not null default 'DRAFT',
    created_at «ts» not null «def_now»,
    updated_at «ts» not null «def_now»,
    «uq_ns_name»
);
create index idx_site_template_site on site_template(site_id);

create table site_container (
    id «uuid» primary key,
    site_id «uuid» not null references site(id),
    name varchar(200) not null,
    allowed_component_types «txt» not null «def_arr»,
    config «txt» not null «def_obj»,
    created_at «ts» not null «def_now»,
    updated_at «ts» not null «def_now»,
    «uq_ns_name»
);
create index idx_site_container_site on site_container(site_id);

create table site_drop_zone (
    id «uuid» primary key,
    template_id «uuid» not null references site_template(id),
    zone_key varchar(200) not null,
    container_id «uuid» references site_container(id),
    sort_order integer not null default 0,
    config «txt» not null «def_obj»,
    «uq_v12_zone»
);
create index idx_site_drop_zone_template on site_drop_zone(template_id);