-- FR-AI-SRC-003 来源域类别（DEF-MSRC-06）：
-- 同步打标的来源域此前恒为 'PIM:<数据源名>'，DAM 源（如 AssetHub）同步出的内容因此被标成 PIM 域，
-- 跨源分组的来源域类型判定（CrossSourceRelationService.sourceType）随之串类。
-- 本迁移引入显式 source_category 并改写存量数据，使来源域与其来源事实锚点一致。
--
-- 三件事必须同批做，否则下一次同步会因为锚点作用域（content_source_fact.source_system）失配
-- 而把每条外部记录重新建成新副本（一次性但有界，见报告 §4.3 的耦合说明）：
--   1) 数据源补列（默认 PIM，保持旧行为）；
--   2) 落点为 MEDIA 型内容模型的数据源回填 'DAM'（唯一的可判定依据，作为迁移默认值）；
--   3) 把这些源的存量来源事实锚点与落点内容的 source_domain 从 'PIM:<名>' 改写为 'DAM:<名>'。
ALTER TABLE datasource_registration
    ADD COLUMN source_category VARCHAR(20) NOT NULL DEFAULT 'PIM';

«cm_V128»

-- 2) 落点为 MEDIA 型内容模型的源按 DAM 回填（内容模型标识是当时唯一可用的判据）
UPDATE datasource_registration
   SET source_category = 'DAM'
 WHERE EXISTS (SELECT 1
                 FROM content_type ct
                WHERE ct.id = datasource_registration.target_content_type_id
                  AND ct.object_type = 'MEDIA');

-- 3a) 存量来源事实锚点：作用域随来源域改写，否则锚点查询失配
UPDATE content_source_fact
   SET source_system = concat('DAM:', (SELECT d.name
                                    FROM datasource_registration d
                                   WHERE d.site_id = content_source_fact.site_id
                                     AND concat('PIM:', d.name) = content_source_fact.source_system
                                     AND d.source_category = 'DAM'))
 WHERE EXISTS (SELECT 1
                 FROM datasource_registration d
                WHERE d.site_id = content_source_fact.site_id
                  AND concat('PIM:', d.name) = content_source_fact.source_system
                  AND d.source_category = 'DAM');

-- 3b) 存量的落点内容打标：markSourceDomain 只在当前值为 CMS 时写入，不回头改已打标内容，
--     故必须在此一次性纠正（否则这批内容会永远挂着 PIM 前缀）
UPDATE content_item
   SET source_domain = concat('DAM:', (SELECT d.name
                                    FROM datasource_registration d
                                   WHERE d.site_id = content_item.site_id
                                     AND concat('PIM:', d.name) = content_item.source_domain
                                     AND d.source_category = 'DAM'))
 WHERE EXISTS (SELECT 1
                 FROM datasource_registration d
                WHERE d.site_id = content_item.site_id
                  AND concat('PIM:', d.name) = content_item.source_domain
                  AND d.source_category = 'DAM');
