package com.powercms.cms.testing;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

/**
 * 达梦回归测试专用：会话级 schema 隔离 + UTC 时区数据源。
 *
 * <p>背景一（powercms-dm-mvntest1.log 实测根因链）：达梦单实例无法像 PG/金仓那样建独立测试库，
 * 隔离只能靠 {@code alter session set current_schema = powercms_12_test}。若走
 * {@code spring.datasource.hikari.connection-init-sql} 属性，Boot 3.5 对 @Bean 数据源的
 * {@code @ConfigurationProperties(prefix="spring.datasource.hikari")} 绑定发生在
 * BeanPostBeforeInitialization 阶段——而 {@link com.powercms.cms.stats.AnalyticsDialect}
 * 等先创建的组件已在构造期 getConnection() 启动了池（HikariPool sealed），绑定报
 * "The configuration of the pool is sealed once started"，108 个 @SpringBootTest 上下文级联失败。</p>
 *
 * <p>背景二（DmProbe44/45 实测）：Hibernate 实体 Instant 走 OffsetDateTime（UTC 墙钟）写入
 * naive timestamp，读取却按会话时区解释——JVM 与 DM 会话默认 +08 时，
 * <b>全部实体 Instant 回读早 8 小时</b>（expires_at 刚写入即被判过期 → 高危确认单 409）。
 * 且单条 execute 不允许多 ALTER（DmProbe46）、逗号合并 ALTER 不支持（DmProbe48）、
 * PL/SQL 包内的 ALTER 只改服务端不刷新驱动本地时区缓存（DmProbe47 仍 -8h）、
 * URL sessionTimeZone 参数被忽略（DmProbe47 Q2）——唯一可行路径是
 * <b>每连接依次执行两条直接 ALTER</b>（直接语句驱动才更新缓存，DmProbe45 Q2 零偏实证）：
 * 先 {@code alter session set time_zone = '+00:00'} 再 {@code alter session set current_schema = ...}。
 * UTC 会话同时让 {@code current_timestamp} 列默认值与 Hibernate 的 UTC 墙钟写入对齐。</p>
 *
 * <p>本包装器在每次 getConnection 时对新物理连接执行上述两条直接 ALTER（连接归还复用时不重复，
 * Hikari 池化语句开销可忽略），彻底绕开属性绑定时序与单语句限制。</p>
 *
 * <p>开关：{@code powercms.test.schema-isolation=<schema>}（仅达梦回归通过环境变量
 * {@code POWERCMS_TEST_SCHEMA_ISOLATION} 启用）。未设置时本配置不生效，
 * H2/PG/MySQL/金仓的默认测试运行完全不受影响。</p>
 */
@AutoConfiguration(before = DataSourceAutoConfiguration.class)
@ConditionalOnClass(HikariDataSource.class)
@ConditionalOnMissingBean(DataSource.class)
@ConditionalOnProperty(prefix = "powercms.test", name = "schema-isolation")
// migration-toolkit 测试排除了 DataSourceAutoConfiguration（其 @EnableConfigurationProperties
// 随之失效），这里幂等补齐 DataSourceProperties，避免工厂参数无法解析。
@EnableConfigurationProperties(DataSourceProperties.class)
public class TestSchemaIsolationDataSourceAutoConfiguration {

    @Bean
    DataSource dataSource(DataSourceProperties properties,
                          @Value("${powercms.test.schema-isolation}") String schema) {
        HikariDataSource pool = properties.initializeDataSourceBuilder()
            .type(HikariDataSource.class)
            .build();
        return new SessionInitDataSource(pool, schema);
    }

    /**
     * 每连接会话初始化包装器：借出连接后依次执行两条<b>直接</b> ALTER
     * （时区先行刷新驱动缓存，schema 隔离随后），失败即关闭连接上抛。
     * unwrap/isWrapperFor 透传至底层池（Hikari 指标等不受影响）。
     */
    static final class SessionInitDataSource implements DataSource {
        private final DataSource target;
        private final String schema;

        SessionInitDataSource(DataSource target, String schema) {
            this.target = target;
            this.schema = schema;
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection conn = target.getConnection();
            try (Statement s = conn.createStatement()) {
                s.execute("alter session set time_zone = '+00:00'");
                s.execute("alter session set current_schema = " + schema);
            } catch (SQLException e) {
                try {
                    conn.close();
                } catch (SQLException ignore) {
                    // 保留原始异常
                }
                throw e;
            }
            return conn;
        }

        @Override
        public Connection getConnection(String username, String password) {
            throw new UnsupportedOperationException("SessionInitDataSource 不支持按用户借出连接");
        }

        @Override
        public java.io.PrintWriter getLogWriter() throws SQLException {
            return target.getLogWriter();
        }

        @Override
        public void setLogWriter(java.io.PrintWriter out) throws SQLException {
            target.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            target.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return target.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getLogger("com.powercms.cms.testing");
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) {
                return iface.cast(this);
            }
            return target.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return iface.isInstance(this) || target.isWrapperFor(iface);
        }
    }
}
