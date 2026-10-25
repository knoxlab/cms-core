-- 分片上传会话（FR-KNX-010 大文件通道）：浏览器 → PowerCMS（逐分片落盘，断点续传）→ Knox chunk/merge。
-- 会话由前端 init 创建；chunk 逐个推进 uploaded_chunks；complete 校验整体 SHA-256 后转发 Knox merge。
-- 过期会话由清理任务删除临时目录并置 EXPIRED。

create table knox_chunk_session (
    id              «uuid» primary key,
    site_id         «uuid» not null,
    endpoint_id     «uuid» not null,
    filename        varchar(300) not null,
    content_type    varchar(150),
    total_size      bigint not null,
    chunk_size      int not null,
    total_chunks    int not null,
    upload_id       varchar(64) not null,
    status          varchar(20) not null default 'UPLOADING',
    uploaded_chunks int not null default 0,
    sha256          varchar(64),
    error_message   varchar(500),
    created_at      «ts» not null «def_now»,
    updated_at      «ts» not null
);

create unique index uq_knox_chunk_upload_id on knox_chunk_session (endpoint_id, upload_id);
create index idx_knox_chunk_site on knox_chunk_session (site_id, status);
