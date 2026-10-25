-- FR-ACCESS-003/004/005：条件维度。NULL 表示沿对象链继承该维度；显式空列表表示不限制。
CREATE TABLE program_access_policy (
    id               «uuid» PRIMARY KEY,
    site_id          «uuid» NOT NULL,
    object_type      VARCHAR(30) NOT NULL,
    object_id        «uuid» NOT NULL,
    ip_allowlist     «txt»,
    geo_allowlist    «txt»,
    time_windows     «txt»,
    download_enabled «bool»,
    created_by       «uuid»,
    created_at       «ts» NOT NULL,
    updated_at       «ts» NOT NULL,
    CONSTRAINT uq_access_policy UNIQUE (object_type, object_id)
);
CREATE INDEX idx_access_policy_site ON program_access_policy (site_id);
