-- P3 互动：广告位/素材/曝光点击统计（H2/PG 兼容）
create table interaction_ad_slot (
    id «uuid» primary key,
    site_id «uuid» not null,
    code varchar(50) not null,
    name varchar(100) not null,
    ad_type varchar(20) not null default 'IMAGE',
    width integer,
    height integer,
    max_items integer not null default 5,
    status varchar(20) not null default 'DRAFT',
    created_at «ts» not null «def_now»,
    updated_at «ts» not null «def_now»,
    unique (site_id, code)
);
create index idx_ad_slot_site on interaction_ad_slot (site_id);

create table interaction_ad_item (
    id «uuid» primary key,
    site_id «uuid» not null,
    slot_id «uuid» not null,
    title varchar(200) not null,
    media_url varchar(1000),
    html_body «txt»,
    target_url varchar(1000),
    weight integer not null default 1,
    start_at «ts»,
    end_at «ts»,
    daily_cap integer,
    click_url_params varchar(1000),
    status varchar(20) not null default 'DRAFT',
    content_item_id «uuid»,
    created_at «ts» not null «def_now»,
    updated_at «ts» not null «def_now»
);
create index idx_ad_item_slot on interaction_ad_item (slot_id, status, start_at, end_at);

create table interaction_ad_stat (
    id bigint «idgen» primary key,
    ad_item_id «uuid» not null,
    stat_date date not null,
    impression bigint not null default 0,
    click bigint not null default 0,
    unique (ad_item_id, stat_date)
);
create index idx_ad_stat_date on interaction_ad_stat (stat_date);
