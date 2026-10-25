-- 前台会员在某个站点下的收藏与浏览记录。同一资源只保留一条，再次浏览时更新时间。
create table member_library_entry (
    id           «uuid» not null,
    site_id      «uuid» not null,
    member_id    «uuid» not null,
    content_id   «uuid» not null,
    entry_type   varchar(20) not null,
    updated_at   «ts» not null «def_now»,
    primary key (id),
    constraint uk_member_library unique (site_id, member_id, content_id, entry_type)
);
