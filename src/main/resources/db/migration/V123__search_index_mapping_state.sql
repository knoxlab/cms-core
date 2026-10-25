-- FR-MODEL-006 / AC-CM-009 / TC-M66-023：字段级索引映射的「已应用基线」。
--
-- 背景：`sortable` / `filterable` / `highlight` 决定了 OpenSearch mapping 里是否有
-- `f_<code>` 独立键。此前属性只写进 `field_definition`，对索引**完全无影响**：
-- 勾选「可作为排序条件」后前台排序要么 400、要么静默无效。
--
-- 落地后字段级键进入 mapping，但 OpenSearch 的 mapping **几乎不可变**（已建索引无法把
-- keyword 改成 long、也无法新增子字段之外的分词器），所以「模型属性变更」与「索引实际形态」
-- 之间必须有一个可比对的基线：本表存**最近一次成功重建时**的 schema 指纹。
--
--   指纹（desired）≠ 已应用（applied） → 索引需重建（模型属性已变更）
--   applied IS NULL                      → 从未记录基线（尚未重建过，提示但不告警）
--
-- 指纹只含「映射形态」（生效键、类型、角色、冲突项），不含内容数据，故重建一次即稳定 ——
-- 不会因为发布内容而反复提示重建。
CREATE TABLE search_index_mapping_state (
    id                   «uuid»        NOT NULL PRIMARY KEY,
    site_id              «uuid»        NOT NULL,
    target               VARCHAR(16) NOT NULL,
    applied_fingerprint  VARCHAR(64),
    desired_fingerprint  VARCHAR(64),
    applied_at           «ts»,
    updated_at           «ts»   NOT NULL
);

-- 每站点每目标索引一行（管理/访客）
CREATE UNIQUE INDEX uq_search_index_mapping_state_site_target
    ON search_index_mapping_state (site_id, target);

«cm_V123»


