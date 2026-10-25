-- V34 种子为规避 Flyway 占位符写成了两个美元符号；此处还原为 FreeMarker ${var}。
-- 用 concat() 构造美元符号（H2/PostgreSQL/MySQL/金仓/达梦五库通用；达梦 CONCAT 仅收两参，故嵌套），
-- 同时避免脚本中出现字面量 ${ 再被 Flyway 解析。
update notification_template
set subject = replace(subject, concat(concat('$', '$'), '{'), concat('$', '{')),
    body_html = replace(body_html, concat(concat('$', '$'), '{'), concat('$', '{')),
    body_text = replace(body_text, concat(concat('$', '$'), '{'), concat('$', '{'));
