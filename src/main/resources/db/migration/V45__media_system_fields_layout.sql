-- 修复 MEDIA 内容类型系统字段未挂载到 form_layout 的问题（FR-CM-MEDIA-003）
-- 背景：早期 seedSystemFields 只写 field_definition 而遗漏 form_layout，
-- 导致设计器按布局渲染时仅显示空分区（预置五类媒体类型均受影响）。
-- 本迁移把缺失布局的系统字段挂载到各类型根 FORM_SECTION（无分区则挂根层级），幂等：
-- 已挂载的不重复插入；后续新建类型由 ContentTypeService.seedSystemFields 直接写入。

-- 1) 无根分区的 MEDIA 类型先补一个根分区（理论上不出现，防御）
insert into form_layout (id, content_type_id, element_type, element_id, parent_id, sort_order, created_at)
select «fn_uuid», ct.id, 'FORM_SECTION', «fn_uuid», null, 10, current_timestamp
from content_type ct
where ct.object_type = 'MEDIA'
  and not exists (
    select 1 from form_layout fl
    where fl.content_type_id = ct.id and fl.element_type = 'FORM_SECTION' and fl.parent_id is null
  );

-- 2) 为 MEDIA 类型中缺失布局的系统字段补挂载到根分区
insert into form_layout (id, content_type_id, element_type, element_id, parent_id, sort_order, created_at)
select «fn_uuid», fd.content_type_id, 'FIELD', fd.id,
       (
         select fl.element_id from form_layout fl
         where fl.content_type_id = fd.content_type_id
           and fl.element_type = 'FORM_SECTION' and fl.parent_id is null
         order by fl.sort_order limit 1
       ),
       fd.sort_order, current_timestamp
from field_definition fd
where fd.is_system = «lit_t»
  and exists (select 1 from content_type ct where ct.id = fd.content_type_id and ct.object_type = 'MEDIA')
  and not exists (
    select 1 from form_layout fl
    where fl.content_type_id = fd.content_type_id and fl.element_id = fd.id
  );
