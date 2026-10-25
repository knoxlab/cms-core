-- FR-AI-SRC-001：外部数据源接入（PIM 首接入，通用适配器 REST 轮询 + CSV 导入）。
-- 三张表：数据源注册表 / 字段映射配置 / 同步运行审计。

-- 数据源注册表：一个站点可注册多个外部源（PIM/DAM/任意 REST JSON 端点）。
CREATE TABLE datasource_registration (
    id «uuid» NOT NULL,
    site_id «uuid» NOT NULL,
    name VARCHAR(200) NOT NULL,
    source_type VARCHAR(20) NOT NULL,            -- REST / CSV
    endpoint_url VARCHAR(1000),                  -- REST 通道：数据端点
    auth_type VARCHAR(20) NOT NULL DEFAULT 'NONE', -- NONE / BEARER / BASIC
    auth_secret_enc VARCHAR(1000),               -- BEARER token / BASIC 密码（AES 加密）
    auth_username VARCHAR(200),                  -- BASIC 认证用户名（密码在 auth_secret_enc）
    target_content_type_id «uuid» NOT NULL,        -- 映射目标内容模型
    target_category_id «uuid»,                     -- 新条目归属分类（可空）
    target_folder_id «uuid»,                       -- 新条目归属文件夹（可空）
    external_key_field VARCHAR(100) NOT NULL,    -- 外部唯一键字段名（幂等 upsert 依据）
    title_field VARCHAR(100) NOT NULL,           -- 外部标题字段名
    sync_mode VARCHAR(20) NOT NULL DEFAULT 'MANUAL', -- MANUAL / SCHEDULED
    interval_minutes INT NOT NULL DEFAULT 0,     -- SCHEDULED 同步间隔
    cursor_field VARCHAR(100),                   -- 增量游标字段（更新时间戳/序号）
    cursor_state VARCHAR(100),                   -- 当前游标值（null = 从未同步）
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE / PAUSED / DISABLED
    last_sync_at «tstz»,
    last_sync_status VARCHAR(20),                -- SUCCESS / PARTIAL / FAILED
    last_error VARCHAR(2000),
    created_by VARCHAR(100),
    created_at «tstz» NOT NULL,
    updated_at «tstz» NOT NULL,
    CONSTRAINT pk_datasource_registration PRIMARY KEY (id),
    «uq_v88_site»
);
CREATE INDEX idx_datasource_site ON datasource_registration (site_id);
CREATE INDEX idx_datasource_schedule ON datasource_registration (sync_mode, status);

-- 字段映射配置：外部字段 → 内容模型字段（含类型转换与单位声明）。
CREATE TABLE datasource_field_mapping (
    id «uuid» NOT NULL,
    datasource_id «uuid» NOT NULL,
    external_field VARCHAR(200) NOT NULL,        -- 外部字段路径（支持 a.b.c 点号嵌套）
    target_field VARCHAR(100) NOT NULL,          -- 内容模型字段 code
    target_type VARCHAR(30),                     -- 冗余记录内容字段类型（STRING/INTEGER/DECIMAL/BOOLEAN/DATE_TIME）
    transform VARCHAR(20) NOT NULL DEFAULT 'NONE', -- NONE / TO_NUMBER / TO_BOOLEAN / TO_DATETIME / TRIM
    unit VARCHAR(50),                            -- 单位声明（如 mm/kg，随值入 fieldValues 说明）
    required «bool» NOT NULL DEFAULT «lit_f»,     -- 必填校验：外部缺失该字段行进隔离区
    sort_order INT NOT NULL DEFAULT 0,
    created_at «tstz» NOT NULL,
    updated_at «tstz» NOT NULL,
    CONSTRAINT pk_datasource_field_mapping PRIMARY KEY (id),
    «uq_v88_map»
);
CREATE INDEX idx_mapping_ds ON datasource_field_mapping (datasource_id);

-- 同步运行审计：每次全量/增量同步一行（成功/部分失败/失败），脏数据明细入 JSON。
CREATE TABLE datasource_sync_log (
    id «uuid» NOT NULL,
    datasource_id «uuid» NOT NULL,
    site_id «uuid» NOT NULL,
    trigger_type VARCHAR(20) NOT NULL,           -- MANUAL / SCHEDULED
    mode VARCHAR(10) NOT NULL,                   -- FULL / INCREMENTAL
    status VARCHAR(20) NOT NULL,                 -- RUNNING / SUCCESS / PARTIAL / FAILED
    total_count INT NOT NULL DEFAULT 0,
    created_count INT NOT NULL DEFAULT 0,
    updated_count INT NOT NULL DEFAULT 0,
    skipped_count INT NOT NULL DEFAULT 0,        -- 幂等跳过（无变化）
    quarantined_count INT NOT NULL DEFAULT 0,    -- 脏数据隔离（校验失败）
    quarantined_json «txt»,                       -- 隔离明细：[{key, field, reason}]
    error_message VARCHAR(2000),
    started_at «tstz» NOT NULL,
    finished_at «tstz»,
    CONSTRAINT pk_datasource_sync_log PRIMARY KEY (id)
);
CREATE INDEX idx_sync_log_ds ON datasource_sync_log (datasource_id, started_at);
CREATE INDEX idx_sync_log_site ON datasource_sync_log (site_id, started_at);
