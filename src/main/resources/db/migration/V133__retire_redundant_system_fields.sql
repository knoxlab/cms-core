-- 2026-09-22 系统字段瘦身（FR-CM-SYSFIELD-001 治理）：
-- url_s / template_s / column_id_s / storage_path_s 与条目级实体列（route_path / template_path /
-- folder_id）语义重复且全仓无平台消费方，退役并从所有存量内容类型中移除（布局行 + 字段定义），
-- 消除「添加页面」等表单中系统字段输入项与页面属性输入项的重复。
-- title_s 与 MEDIA 元数据系统字段保留（媒体管道 / AI 建模消费）。
-- 历史版本快照 fieldValues 中残留的对应键由 ContentService 版本兼容校验按 legacy 清单豁免。

-- 布局行经 element_id 关联字段定义（saveNodes 约定：FIELD 行 element_id = 字段 UUID）
DELETE FROM form_layout
 WHERE element_type = 'FIELD'
   AND element_id IN (
       SELECT id FROM field_definition
        WHERE code IN ('url_s', 'template_s', 'column_id_s', 'storage_path_s'));

DELETE FROM field_definition
 WHERE code IN ('url_s', 'template_s', 'column_id_s', 'storage_path_s');
