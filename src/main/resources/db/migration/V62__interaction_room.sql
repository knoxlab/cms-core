-- P4 互动：聊天室/房间成员/敏感词库（H2/PG 兼容）
create table interaction_chat_room (
    id «uuid» primary key,
    site_id «uuid» not null,
    content_item_id «uuid» not null,
    member_only «bool» not null default «lit_f»,
    slow_mode_sec integer not null default 0,
    muted_user_ids «txt»,
    history_retain_days integer not null default 90,
    status varchar(20) not null default 'OPEN',
    created_at «ts» not null «def_now»,
    updated_at «ts» not null «def_now»,
    unique (content_item_id)
);
create index idx_room_site on interaction_chat_room (site_id);

create table interaction_chat_member (
    room_id «uuid» not null,
    user_id «uuid» not null,
    joined_at «ts» not null «def_now»,
    last_read_seq bigint not null default 0,
    primary key (room_id, user_id)
);

create table interaction_sensitive_word (
    id «uuid» primary key,
    site_id «uuid» not null,
    word varchar(100) not null,
    level varchar(20) not null default 'BLOCK',
    created_at «ts» not null «def_now»,
    unique (site_id, word)
);
create index idx_sensitive_site on interaction_sensitive_word (site_id);
