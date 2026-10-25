-- DEF-CFG-01 存量修复：清理并发竞态留下的「孤儿组织」。
--
-- 现象：新装/首启时两个创建者会**并发**进入 ensureOrganization 的空表分支 ——
-- IdentityBootstrap（ApplicationReadyEvent）与 OrganizationContextFilter（@Order(1)，服务首个请求）——
-- 各插一行同名「默认组织」（域 11 实测两份 createdAt 相差 8ms）。此后只要写入侧与读取侧
-- 「取第一行」的顺序口径不同，组织设置（loginIdentifier / 登录页品牌 / 组织默认语言）就会
-- 写在一行、从另一行读走，表现为「保存成功但完全不生效」的静默失效。
--
-- 代码侧已改为双重检查 + 临界区（IdentityServiceImpl.currentOrganizationId）+ 唯一口径
-- （OrganizationRepository.findAllIds 按 createdAt, id 排序）。本脚本只负责**存量**清理：
-- 删掉既无用户、又无站点、也无组织设置行的组织 —— 即竞态留下的空壳，不含任何业务数据。
--
-- 安全性：任一引用行存在都会让该组织保留；全新库 organization 为空，等价空操作。
--
-- 删除顺序（关键）：引用 organization 的表有 organization_settings / site / sys_role / sys_user
-- （后者由「无用户无站点无设置」谓词保证为空），而**引用 sys_role 的表有三张** ——
-- sys_user_role.role_id、sys_role_layout.role_id、workflow_transition.allowed_role_id。
-- 孤儿组织同样会持有内置角色（ensureOrganization 为任意组织补齐角色），故必须先清空这三张
-- 引用表，再删角色，最后删组织。漏一张即触发外键冲突（域 11 实测 sys_role_layout 正是漏网的一张）。
-- 下表由 information_schema 外键闭包枚举得出，并由 OrganizationOrphanCleanupMigrationTest
-- 在**真实全量迁移建出的 schema** 上执行校验。

-- 1) 清掉指向「孤儿组织角色」的三张引用表
delete from sys_user_role
 where role_id in (select r.id from sys_role r
                    where not exists (select 1 from sys_user u where u.organization_id = r.organization_id)
                      and not exists (select 1 from site s where s.organization_id = r.organization_id)
                      and not exists (select 1 from organization_settings o where o.id = r.organization_id));

delete from sys_role_layout
 where role_id in (select r.id from sys_role r
                    where not exists (select 1 from sys_user u where u.organization_id = r.organization_id)
                      and not exists (select 1 from site s where s.organization_id = r.organization_id)
                      and not exists (select 1 from organization_settings o where o.id = r.organization_id));

delete from workflow_transition
 where allowed_role_id in (select r.id from sys_role r
                            where not exists (select 1 from sys_user u where u.organization_id = r.organization_id)
                              and not exists (select 1 from site s where s.organization_id = r.organization_id)
                              and not exists (select 1 from organization_settings o where o.id = r.organization_id));

-- 2) 孤儿组织的内置角色
delete from sys_role
 where not exists (select 1 from sys_user u where u.organization_id = sys_role.organization_id)
   and not exists (select 1 from site s where s.organization_id = sys_role.organization_id)
   and not exists (select 1 from organization_settings o where o.id = sys_role.organization_id);

-- 3) 孤儿组织本身
delete from organization
 where not exists (select 1 from sys_user u where u.organization_id = organization.id)
   and not exists (select 1 from site s where s.organization_id = organization.id)
   and not exists (select 1 from organization_settings o where o.id = organization.id);
