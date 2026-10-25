package com.powercms.cms.stats;

import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.Locale;

/**
 * 统计模块五库方言探测（多数据库适配：H2 / PostgreSQL / MySQL / 金仓 KingbaseES / 达梦 DM8）。
 *
 * <p>以 JDBC {@link DatabaseMetaData}（产品名/驱动名）识别，不在 SQL 里探测（任何库都可执行）；
 * 识别结果映射为<b>能力位</b>而非厂商布尔——调用方只依赖能力，不关心品牌：</p>
 * <ul>
 *   <li>{@link #supportsOnConflict()}：原子 upsert（ON CONFLICT）——PG 系（PostgreSQL/金仓）；</li>
 *   <li>{@link #supportsAdvisoryLocks()}：会话级咨询锁（pg_try_advisory_lock）——PG 系，
 *       执行期异常自动退化本地锁（见 AnalyticsJobLockService）；</li>
 *   <li>{@link #notExistsFromClause()}：「insert ... select ... where not exists」的 FROM 缺省值：
 *       H2/PG 系 {@code from (values (1)) marker}，达梦/MySQL {@code from dual}；</li>
 *   <li>{@link #castUuid(String)} / {@link #castVarchar(String, int)}：
 *       native SQL 的 uuid / varchar 转型片段（MySQL 无 uuid 与 varchar 的 CAST 目标类型）；</li>
 *   <li>{@link #nativeUuidColumn()}：uuid 列在本库是原生 uuid 类型（H2/PG/金仓）还是 varchar(36)；</li>
 *   <li>{@link #positionOf(String, String)}：instr/strpos（PG 无 instr）；</li>
 *   <li>{@link #visitGapMinutes(String, String, int)}：时间差谓词（MySQL timestampdiff、
 *       达梦 date 差、H2/PG/金仓 interval）；</li>
 *   <li>{@link #epochMinutes(String, String)}：时间差分钟数表达式（MySQL timestampdiff、
 *       达梦 DATE 差、H2/PG/金仓 extract(epoch ...)）；</li>
 *   <li>{@link #castBigint(String)}：聚合转 bigint 的 CAST 片段（MySQL CAST 目标类型无 bigint）；</li>
 *   <li>{@link #supportsWindowFunctions()}：窗口函数（row_number/lag/max over partition）：
 *       MySQL 8.0 才引入，5.5/5.6/5.7 实测 1064，不支持时调用方走无窗口等价重写
 *       （group by + 相关子查询）；H2/PostgreSQL/金仓/达梦均支持。</li>
 * </ul>
 *
 * <p>H2 2.x 不支持 INSERT ... ON CONFLICT 子句（实验验证 2.3.232 全模式失败）；
 * MySQL/达梦同理走可移植 SQL。未知库按最保守（可移植）分支处理。</p>
 */
@Component
public class AnalyticsDialect {

    private final boolean postgresFamily;   // PostgreSQL / 金仓（ON CONFLICT、咨询锁）
    private final boolean mysql;            // MySQL（无 FROM 的 select、cast as char、timestampdiff）
    private final boolean dameng;           // 达梦（from dual、varchar2 cast、无 uuid 类型）
    private final boolean nativeUuidColumn; // 列类型为原生 uuid
    private final int majorVersion;         // 数据库主版本（MySQL 8.0 前无窗口函数）

    public AnalyticsDialect(DataSource dataSource) {
        boolean pg = false;
        boolean my = false;
        boolean dm = false;
        boolean h2 = false;
        String product = "";
        String driver = "";
        int major = -1;
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData md = conn.getMetaData();
            product = md.getDatabaseProductName() == null ? "" : md.getDatabaseProductName();
            driver = md.getDriverName() == null ? "" : md.getDriverName();
            major = md.getDatabaseMajorVersion();
        } catch (Exception e) {
            // 元数据不可得 → 全部按可移植分支（保守但可用）
        }
        String p = (product + " " + driver).toLowerCase(Locale.ROOT);
        pg = p.contains("postgres") || p.contains("kingbase");
        my = !pg && p.contains("mysql");
        h2 = !pg && !my && (p.contains("h2") || product.equalsIgnoreCase("H2"));
        dm = !pg && !my && !h2
            && (p.contains("dm dbms") || p.contains("dameng") || p.contains("dmjdbc") || p.contains("dm driver"));
        this.postgresFamily = pg;
        this.mysql = my;
        this.dameng = dm;
        // H2（PostgreSQL 兼容模式）与 PG 系同为原生 uuid；MySQL/达梦 «uuid» → varchar(36)
        this.nativeUuidColumn = h2 || pg;
        this.majorVersion = major;
    }

    // ---------- 能力位 ----------

    /**
     * 窗口函数（row_number()/lag()/max() over (partition by ...)）：MySQL 8.0 才引入，
     * 5.5/5.6/5.7 实测 1064（realtime/watching、entry/exit、frequency 聚合三处）；
     * 不支持时调用方改写为 group by + 相关子查询的等价 SQL。H2/PostgreSQL/金仓/达梦
     * 均支持，仍走窗口重写（达梦不支持 PG 系 distinct on，窗口版为五库通用基线）。
     * 元数据不可得（major=-1）时按支持处理：与旧行为一致，仅 MySQL 5.x 落不支持分支。
     */
    public boolean supportsWindowFunctions() {
        return !mysql || majorVersion >= 8;
    }

    /** 原子 upsert（INSERT ... ON CONFLICT）：仅 PG 系（PostgreSQL / 金仓）。 */
    public boolean supportsOnConflict() {
        return postgresFamily;
    }

    /** 会话级咨询锁（跨节点互斥）：PG 系；执行期函数缺失由调用方捕获后退化本地锁。 */
    public boolean supportsAdvisoryLocks() {
        return postgresFamily;
    }

    /** uuid 列在目标库为原生 uuid 类型（H2/PostgreSQL/金仓）；MySQL/达梦为 varchar(36)。 */
    public boolean nativeUuidColumn() {
        return nativeUuidColumn;
    }

    public boolean isMysql() {
        return mysql;
    }

    public boolean isDameng() {
        return dameng;
    }

    // ---------- native SQL 片段 ----------

    /**
     * 「insert ... select ... where not exists」缺省 FROM 子句（select 列表之后拼接）：
     * MySQL：{@code from dual}（select 带 where 必须有 FROM，8.0 支持 DUAL）；
     * 达梦：{@code from dual}（Oracle 兼容）；H2/PG：{@code from (values (1)) marker}。
     * 返回值一律以换行结尾，便于与后续 where 子句拼接。
     */
    public String notExistsFromClause() {
        if (dameng || mysql) return "from dual\n";
        return "from (values (1)) marker\n";
    }

    /**
     * uuid 参数/表达式转型片段：{@code cast(expr as uuid)}（H2/PG/金仓）、
     * {@code cast(expr as char)}（MySQL，CAST 目标无 varchar）、{@code cast(expr as varchar2(36))}（达梦）。
     */
    public String castUuid(String expr) {
        if (mysql) return "cast(" + expr + " as char)";
        if (dameng) return "cast(" + expr + " as varchar2(36))";
        return "cast(" + expr + " as uuid)";
    }

    /**
     * varchar 转型片段（截断语义）：MySQL CAST 目标类型只能是 char。
     * 例：castVarchar("search_keyword", 200)。
     */
    public String castVarchar(String expr, int length) {
        if (mysql) return "cast(" + expr + " as char(" + length + "))";
        return "cast(" + expr + " as varchar(" + length + "))";
    }

    /**
     * 「内容类型 id 取最大」聚合片段（analytics_content_daily）：
     * 原生 uuid 列上 PG 无 max(uuid)，先按文本取最大再转回 uuid；
     * MySQL/达梦列本身即 varchar(36)，直接 max（uuid 标准小写文本序与 uuid 比较序一致）。
     */
    public String maxContentTypeId() {
        if (nativeUuidColumn) {
            return "cast(max(cast(content_type_id as varchar(36))) as uuid)";
        }
        return "max(content_type_id)";
    }

    /** 子串位置函数：PG 系无 instr 用 strpos；其余用 instr（H2/MySQL/达梦）。 */
    public String positionOf(String column, String needle) {
        return postgresFamily ? "strpos(" + column + ", " + needle + ")"
                              : "instr(" + column + ", " + needle + ")";
    }

    /**
     * 访问切分时间差谓词（FR-STAT-060 频度聚合）：a - b 是否超过 n 分钟。
     * H2/PG/金仓：{@code a - b > interval 'n' minute}（TIMESTAMP 相减得 interval）；
     * MySQL：DATETIME 相减得数值（日期数字口径），须用 timestampdiff；
     * 达梦：timestamp 相减与 interval 字面量比较报 Data type mismatch
     * （mvntest7 实测 analytics_frequency_daily），改用已验证的 date 差口径
     * {@code (cast(a as date) - cast(b as date)) * 1440}（与 {@link #epochMinutes} 同式）。
     */
    public String visitGapMinutes(String a, String b, int minutes) {
        if (mysql) return "timestampdiff(minute, " + b + ", " + a + ") > " + minutes;
        if (dameng) return "((cast(" + a + " as date) - cast(" + b + " as date)) * 1440) > " + minutes;
        return a + " - " + b + " > interval '" + minutes + "' minute";
    }

    /**
     * 两时间戳相差分钟数表达式（native SQL，later − earlier，可为小数）：
     * H2/PG/金仓 {@code extract(epoch from (a - b)) / 60}；
     * MySQL {@code timestampdiff(minute, earlier, later)}（DATETIME 相减非 interval，整数分钟粒度）；
     * 达梦 {@code (cast(a as date) - cast(b as date)) * 1440}（DATE 差为天数数值，Oracle 口径）。
     */
    public String epochMinutes(String later, String earlier) {
        if (mysql) return "timestampdiff(minute, " + earlier + ", " + later + ")";
        if (dameng) return "((cast(" + later + " as date) - cast(" + earlier + " as date)) * 1440)";
        return "(extract(epoch from (" + later + " - " + earlier + ")) / 60)";
    }

    /**
     * 聚合值转 bigint 的 CAST 片段（native SQL）：MySQL CAST 目标类型清单无 bigint，须用 signed；
     * 其余四库（含达梦 BIGINT）为 {@code cast(expr as bigint)}。结果为 Number，调用方按数值映射。
     */
    public String castBigint(String expr) {
        return mysql ? "cast(" + expr + " as signed)" : "cast(" + expr + " as bigint)";
    }

    /**
     * 表名按方言加引号（裸 JDBC/JdbcTemplate 手写 SQL 用，不经 Hibernate）。
     * release 为 MySQL 保留字（实测 create table release → 1064）；迁移脚本侧由
     * 占位符 tbl_release 渲染、Hibernate 生成 SQL 由 db-mysql 的
     * globally_quoted_identifiers 自动反引号，二者覆盖不到的手写 SQL 走本方法。
     * 其余四库（H2/PG/金仓/达梦）release 实测裸名可用，原样返回、语义不变。
     */
    public String quoteTable(String table) {
        return mysql ? "`" + table + "`" : table;
    }
}
