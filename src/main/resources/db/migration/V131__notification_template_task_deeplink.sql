-- OBS-CFG-02：出厂（全局）通知模板补上「系统自引用 URL」深链行
--
-- 背景：DEF-CFG-03 已把组织设置的 portalUrl 接进发信上下文（EmailNotificationChannel.eventModel
-- 恒注入 portalUrl / taskUrl），但 V34 种下的 7 条出厂模板正文里**没有任何深链变量**，
-- 于是开箱状态下收件人拿不到可点击/可复制的任务链接 —— 同一份配置「能存、能读、注入到位，
-- 却看不见」，与 DEF-CFG-03 报告的现象同形，根因已从「未接线」变成「出厂内容不引用」。
--
-- 边界（刻意保守，见报告 §3.5 的「尊重运维既有自定义」约定）：
--   1. 只改 site_id is null 的出厂模板，绝不触碰站点覆盖行（那是运维按站点口径写过的文案）；
--   2. 正文里已自行引用 taskUrl 的行不再追加：既不重复插入，也不改变自定义的插入位置；
--   3. body_text 允许为空，故先 coalesce 再拼接（本方言下 null 会吞掉整条拼接结果）。
--
-- 追加内容用 <#if taskUrl?has_content> 包住：未配置 portalUrl 时 taskUrl 为空串
-- （见 EmailNotificationChannel.eventModel 的注释——变量恒存在、值为空串而非缺键），
-- 整行省略，不会渲染出「查看任务：」这类空链接。HTML 与纯文本双双补齐：
-- 真实发信走纯文本（SimpleMailMessage.setText），HTML 供模板管理界面预览。
--
-- Flyway 占位符前后缀已改为尖括号对（application.yml 的 placeholder-prefix/suffix），
-- 字面量 ${taskUrl} 不在替换范围（H2/PostgreSQL/MySQL/金仓/达梦五库通用）；V34 的种子脚本同此约定。
update notification_template
set body_text = concat(concat(coalesce(body_text, ''), «fn_lf»), '<#if taskUrl?has_content>查看任务：${taskUrl}</#if>'),
    body_html = concat(coalesce(body_html, ''), '<p><#if taskUrl?has_content>查看任务：<a href="${taskUrl}">${taskUrl}</a></#if></p>'),
    updated_by = 'system',
    updated_at = current_timestamp
where site_id is null
  and template_key in ('task_submitted', 'task_approved', 'task_rejected', 'publish_succeeded',
                       'build_failed', 'task_timeout', 'task_delegated')
  and coalesce(body_text, '') not like '%taskUrl%'
  and coalesce(body_html, '') not like '%taskUrl%';
