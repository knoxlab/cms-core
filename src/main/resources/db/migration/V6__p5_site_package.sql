-- ============================================================
-- P5：站点包 — 包缓存、导入记录
-- H2 / PostgreSQL 兼容
-- ============================================================

-- 站点包缓存（FR-PACKAGE-007）：导入/下载的 ZIP 及其元数据
create table site_package (
    id              «uuid» primary key,
    package_id      varchar(200) not null, -- 反向域名标识
    name            varchar(200) not null,
    version         varchar(50)  not null,
    schema_version  varchar(20)  not null default '1.0',
    vendor          «txt»,        -- JSON: {name, email, url}
    license_spdx    varchar(100),
    cms_version_range varchar(200),
    template_runtime «txt»,       -- JSON: {type, nodeVersion, buildImageRange}
    entrypoints     «txt»,        -- JSON: {preview, build, static}
    required_capabilities «txt»,  -- JSON: strings array
    manifest_checksums «txt»,     -- JSON: {filePath: sha256}
    signature       «txt»,        -- JSON: {algorithm, certRef, value}
    package_digest  varchar(128), -- 整包 SHA-256
    file_size       bigint       not null default 0,
    file_count      int          not null default 0,
    storage_key     varchar(500), -- 对象存储 key
    imported_site_id «uuid»,       -- 导入后创建的站点
    imported_at     «ts»,
    created_at      «ts» not null «def_now»,
    «uq_v6_pkg»
);
