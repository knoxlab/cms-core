-- FR-AI-SRC-004 统一来源事实元数据模型（验收缺口：来源系统/版本号/审批状态/有效期/可信等级
-- 未形成统一元数据模型；有效期未纳入通用引用闸门）。
--
-- 设计：外部来源（DAM/PIM/第三方）同步投影为 content_item 时，同步写入一条来源事实，
-- 统一承载来源侧元数据；CMS 原生内容无本行。引用闸门（AiTrustGuard）在本行存在时，
-- 额外执行来源审批状态与有效期判定，与既有 trust_level / 内容状态闸门合并为单一裁决。
--
-- 兼容：无本行 = 无外部来源事实 = 不额外限制（CMS 原生内容行为不变）。
CREATE TABLE content_source_fact (
    content_item_id «uuid» PRIMARY KEY REFERENCES content_item (id) ON DELETE CASCADE,
    site_id         «uuid» NOT NULL,
    source_system   VARCHAR(100) NOT NULL,
    source_object_id VARCHAR(500),
    source_version  VARCHAR(100),
    approval_status VARCHAR(20) NOT NULL DEFAULT 'APPROVED',
    valid_from      «tstz»,
    valid_until     «tstz»,
    trust_level     INT NOT NULL DEFAULT 2,
    copyright       VARCHAR(1000),
    sync_cursor     VARCHAR(500),
    synced_at       «tstz» NOT NULL,
    updated_at      «tstz» NOT NULL
);

create «idx» idx_content_source_fact_site
    ON content_source_fact (site_id);
create «idx» idx_content_source_fact_valid_until
    ON content_source_fact (valid_until);

«cm_V111»


