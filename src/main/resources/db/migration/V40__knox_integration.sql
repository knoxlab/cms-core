-- P1 媒体管理：Knox-Media 对接（FR-KNX-001/002/004/012/021，规格 docs/media-knox-integration-specification.md）
-- 端点注册表保存 OAuth client 凭据：client_secret 为 AES-GCM 密文（SecretCryptor），回调密钥为明文 HMAC 出签需要还原原文。
-- knox_media_ref 即资产映射账本：(endpoint_id, knox_media_id) 唯一保证同一 Knox 资产只映射一次；owner_site_id 是共享库写边界判定依据。

create table knox_endpoint (
    id              «uuid» primary key,
    name            varchar(100) not null,
    base_url        varchar(1000) not null,
    client_id       varchar(100) not null,
    client_secret   varchar(500) not null,
    callback_secret varchar(200),
    purpose         varchar(100) not null default 'PRIMARY_UPLOAD',
    status          varchar(20) not null default 'ACTIVE',
    health_state    varchar(20) not null default 'UNKNOWN',
    last_check_at   «ts»,
    last_error      varchar(1000),
    created_at      «ts» not null «def_now»,
    updated_at      «ts» not null
);

create table knox_site_binding (
    id             «uuid» primary key,
    site_id        «uuid» not null,
    endpoint_id    «uuid» not null,
    knox_library_id varchar(64),
    mode           varchar(20) not null default 'EXCLUSIVE',
    visibility     varchar(20) not null default 'ALL',
    fallback_endpoint_id «uuid»,
    created_at     «ts» not null «def_now»,
    updated_at     «ts» not null,
    constraint uq_knox_binding_site unique (site_id)
);

create index idx_knox_binding_endpoint on knox_site_binding (endpoint_id);

create table knox_media_ref (
    media_asset_id  «uuid» primary key,
    endpoint_id     «uuid» not null,
    knox_media_id   varchar(64) not null,
    knox_library_id varchar(64),
    owner_site_id   «uuid» not null,
    site_tag        varchar(100),
    status          varchar(20) not null default 'PENDING',
    poster_url      varchar(1000),
    duration_ms     int,
    width           int,
    height          int,
    play_plan       «txt»,
    processing_steps varchar(500),
    last_event_at   «ts»,
    last_error      varchar(1000),
    next_check_at   «ts»,
    check_attempts  int not null default 0,
    created_at      «ts» not null «def_now»,
    updated_at      «ts» not null,
    constraint uq_knox_ref_media unique (endpoint_id, knox_media_id)
);

create index idx_knox_ref_pending on knox_media_ref (status, next_check_at);
create index idx_knox_ref_owner on knox_media_ref (owner_site_id);

create table knox_callback_log (
    id              «uuid» primary key,
    endpoint_id     «uuid»,
    event_id        varchar(100) not null,
    event_type      varchar(100) not null,
    payload         «txt» not null,
    signature_valid «bool» not null,
    handled_result  varchar(20) not null,
    received_at     «ts» not null «def_now»,
    replayed_at     «ts»,
    constraint uq_knox_callback_event unique (event_id)
);
