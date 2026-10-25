package com.powercms.cms.platform.db;

import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.FunctionContributor;
import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.MySQLDialect;
import org.hibernate.dialect.OracleDialect;
import org.hibernate.query.sqm.function.SqmFunctionRegistry;

/**
 * Hibernate 方言函数注册（多数据库适配）：为缺少 {@code date_trunc} 的方言补齐 HQL 函数实现。
 *
 * <p>系统日志按小时聚合（RuntimeLogRepository/AccessLogRepository 的 countByHour）在 HQL 中使用
 * {@code date_trunc('hour', occurredAt)}：</p>
 * <ul>
 *   <li>H2（MODE=PostgreSQL）/ PostgreSQL / 金仓：内建 date_trunc，未注册 → 保持 Hibernate
 *       既有透传行为（现状已验证可用），<b>不覆盖</b>；</li>
 *   <li>MySQL：无 date_trunc → 注册模板实现
 *       {@code cast(date_format(ts, '%Y-%m-%d %H:00:00') as datetime)}（小时截断）；</li>
 *   <li>达梦（走 org.hibernate.dialect.OracleDialect）：无 date_trunc → 注册
 *       {@code trunc(cast(ts as timestamp), 'hh24')}（Oracle 日期截断语义）。</li>
 * </ul>
 *
 * <p><b>范围约定：</b>当前唯一用法是单位 'hour'——MySQL 模板固定按小时截断、忽略单位参数；
 * 若未来引入 'day'/'minute' 等单位，须将本类改为按参数分派的 SqmFunctionDescriptor。</p>
 *
 * <p>经 {@code META-INF/services/org.hibernate.boot.model.FunctionContributor} SPI 装载，
 * Hibernate 启动时按当前 Dialect 执行一次。</p>
 */
public class PowerCmsFunctionContributor implements FunctionContributor {

    @Override
    public void contributeFunctions(FunctionContributions contributions) {
        Dialect dialect = contributions.getDialect();
        SqmFunctionRegistry registry = contributions.getFunctionRegistry();
        if (dialect instanceof MySQLDialect) {
            registry.registerPattern("date_trunc",
                "cast(date_format(?2, '%Y-%m-%d %H:00:00') as datetime)");
        } else if (dialect instanceof OracleDialect) {
            // 达梦 DM8（Oracle 兼容）：TRUNC(时间戳, 'hh24') 按小时截断
            registry.registerPattern("date_trunc",
                "trunc(cast(?2 as timestamp), 'hh24')");
        }
        // H2 / PostgreSQL / 金仓：内建 date_trunc，不注册（保持默认解析）
    }
}
