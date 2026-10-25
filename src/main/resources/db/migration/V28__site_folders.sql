create table site_folder (
    id «uuid» primary key,
    site_id «uuid» not null references site(id),
    parent_id «uuid» references site_folder(id),
    title varchar(200) not null,
    name varchar(120) not null,
    path varchar(1000) not null,
    sort_order integer not null default 0,
    show_on_menu «bool» not null default «lit_t»,
    allowed_file_extensions varchar(1000),
    default_file_asset_type varchar(50) not null default 'FILE',
    created_at «ts» not null «def_now»,
    updated_at «ts» not null «def_now»,
    «uq_v28»
);
create index idx_site_folder_site_parent_sort on site_folder(site_id, parent_id, sort_order);
