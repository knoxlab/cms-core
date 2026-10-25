-- 2026-09-22 页面属性绑定控件（需求：所属栏目/URL/页面模板/可视化模板 四项各自做成可拖放绑定控件）：
-- 为存量 PAGE 内容类型播种 4 个绑定字段（与 title_s 同机制的 🔒 系统字段，可拖放排版）并挂载布局，
-- 页面管理新建/编辑对话框的内置输入项随之退场（输入项唯一、位置由模型设计器布局决定）。
--
-- 绑定语义：值映射条目级实体列（folder_id / route_path / template_path / 页面组成绑定），
-- 不写 fieldValues（title_s 为契约码例外，两处均写）。新建类型由 ContentTypeService.seedSystemFields
-- 直接铸造（ContentModelValidator.systemFields(PAGE)）；本迁移只补存量，幂等。

-- 1) 为存量 PAGE 类型补铸 4 个绑定字段（各字段独立语句，sort_order 顺序累加到既有最大值之后）
insert into field_definition (id, content_type_id, code, name, field_type, required, sort_order,
                              group_name, i18n, admin_index, public_index, sortable, filterable,
                              highlight, read_only, is_system, system_field_type, unique_field)
select «fn_uuid», ct.id, 'folder_id_s', '所属栏目', 'STRING', «lit_f»,
       coalesce((select max(fd2.sort_order) from field_definition fd2 where fd2.content_type_id = ct.id), 0) + 10,
       'system', «lit_f», «lit_t», «lit_f», «lit_f», «lit_f», «lit_f», «lit_f», «lit_t», 'folder', «lit_f»
from content_type ct
where ct.object_type = 'PAGE'
  and not exists (select 1 from field_definition fd
                   where fd.content_type_id = ct.id and fd.code = 'folder_id_s');

insert into field_definition (id, content_type_id, code, name, field_type, required, sort_order,
                              group_name, i18n, admin_index, public_index, sortable, filterable,
                              highlight, read_only, is_system, system_field_type, unique_field)
select «fn_uuid», ct.id, 'route_path_s', 'URL', 'STRING', «lit_f»,
       coalesce((select max(fd2.sort_order) from field_definition fd2 where fd2.content_type_id = ct.id), 0) + 10,
       'system', «lit_f», «lit_t», «lit_f», «lit_f», «lit_f», «lit_f», «lit_f», «lit_t», 'route', «lit_f»
from content_type ct
where ct.object_type = 'PAGE'
  and not exists (select 1 from field_definition fd
                   where fd.content_type_id = ct.id and fd.code = 'route_path_s');

insert into field_definition (id, content_type_id, code, name, field_type, required, sort_order,
                              group_name, i18n, admin_index, public_index, sortable, filterable,
                              highlight, read_only, is_system, system_field_type, unique_field)
select «fn_uuid», ct.id, 'template_path_s', '页面模板', 'STRING', «lit_f»,
       coalesce((select max(fd2.sort_order) from field_definition fd2 where fd2.content_type_id = ct.id), 0) + 10,
       'system', «lit_f», «lit_t», «lit_f», «lit_f», «lit_f», «lit_f», «lit_f», «lit_t», 'template', «lit_f»
from content_type ct
where ct.object_type = 'PAGE'
  and not exists (select 1 from field_definition fd
                   where fd.content_type_id = ct.id and fd.code = 'template_path_s');

insert into field_definition (id, content_type_id, code, name, field_type, required, sort_order,
                              group_name, i18n, admin_index, public_index, sortable, filterable,
                              highlight, read_only, is_system, system_field_type, unique_field)
select «fn_uuid», ct.id, 'visual_template_s', '可视化模板', 'STRING', «lit_f»,
       coalesce((select max(fd2.sort_order) from field_definition fd2 where fd2.content_type_id = ct.id), 0) + 10,
       'system', «lit_f», «lit_t», «lit_f», «lit_f», «lit_f», «lit_f», «lit_f», «lit_t», 'visualTemplate', «lit_f»
from content_type ct
where ct.object_type = 'PAGE'
  and not exists (select 1 from field_definition fd
                   where fd.content_type_id = ct.id and fd.code = 'visual_template_s');

-- 2) 挂载布局（V45 模式：FIELD 行 element_id = 字段 UUID；有根 FORM_SECTION 挂其下，否则挂根层级）
insert into form_layout (id, content_type_id, element_type, element_id, parent_id, sort_order, created_at)
select «fn_uuid», fd.content_type_id, 'FIELD', fd.id,
       (select fl.element_id from form_layout fl
         where fl.content_type_id = fd.content_type_id
           and fl.element_type = 'FORM_SECTION' and fl.parent_id is null
         order by fl.sort_order limit 1),
       fd.sort_order, current_timestamp
from field_definition fd
where fd.is_system = «lit_t»
  and fd.code in ('folder_id_s', 'route_path_s', 'template_path_s', 'visual_template_s')
  and not exists (select 1 from form_layout fl
                   where fl.content_type_id = fd.content_type_id and fl.element_id = fd.id);
