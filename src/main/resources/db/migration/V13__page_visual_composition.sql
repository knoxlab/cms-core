create table page_visual_composition (
    id «uuid» primary key,
    page_id «uuid» not null references content_item(id),
    template_id «uuid» not null references site_template(id),
    zones_json «txt» not null «def_obj»,
    version integer not null default 1,
    updated_by varchar(100),
    created_at «ts» not null «def_now»,
    updated_at «ts» not null «def_now»,
    unique(page_id)
);
create index idx_page_composition_template on page_visual_composition(template_id);