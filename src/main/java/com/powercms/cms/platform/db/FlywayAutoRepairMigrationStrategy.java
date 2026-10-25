package com.powercms.cms.platform.db;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.stereotype.Component;

/**
 * Flyway 启动迁移策略：校验因 <b>checksum 漂移</b>失败时自动 repair 后重试一次。
 *
 * <p>背景：迁移脚本占位符化（«token» 方言切换）改变了已入库脚本内容，
 * 既有库 flyway_schema_history 中的 checksum 与新脚本不一致，启动必然
 * 「Migration checksum mismatch for migration version X」。逐库手工
 * {@code flyway repair} 不可接受（违背「按配置自动切换、运维零改动」目标），
 * 故在启动迁移路径内置自动修复。</p>
 *
 * <p>触发条件（三者同时满足才 repair，其余异常原样抛出）：</p>
 * <ol>
 *   <li>{@code powercms.flyway.repair-on-migrate=true}（application.yml 基座默认 true）；</li>
 *   <li>首次 {@code migrate()} 抛 {@link FlywayException}；</li>
 *   <li>异常消息含 "checksum"（Flyway 校验类失败的稳定标识）。</li>
 * </ol>
 *
 * <p>repair 只重写 schema history 的 checksum/类型元数据，<b>不改动任何业务数据</b>，
 * 对空库（测试/新装）无副作用；设为 false 可恢复严格校验（checksum 漂移即启动失败）。</p>
 *
 * <p>实现方式：注册为唯一的 {@link FlywayMigrationStrategy} Bean，
 * Spring Boot Flyway 自动配置将用本策略替代默认的 {@code flyway.migrate()} 调用。</p>
 */
@Component
public class FlywayAutoRepairMigrationStrategy implements FlywayMigrationStrategy {

    private static final Logger log = LoggerFactory.getLogger(FlywayAutoRepairMigrationStrategy.class);

    private final boolean repairOnMigrate;

    public FlywayAutoRepairMigrationStrategy(
            @Value("${powercms.flyway.repair-on-migrate:true}") boolean repairOnMigrate) {
        this.repairOnMigrate = repairOnMigrate;
    }

    @Override
    public void migrate(Flyway flyway) {
        try {
            flyway.migrate();
            return;
        } catch (FlywayException e) {
            if (!repairOnMigrate || !isChecksumMismatch(e)) {
                throw e;
            }
            log.warn("Flyway checksum mismatch（占位符化后旧库预期现象）→ 自动 repair 后重试一次。原因: {}",
                e.getMessage());
        }
        flyway.repair();
        flyway.migrate();
    }

    /** 仅识别 checksum 类校验失败（版本缺失/待迁移/结构漂移等其余错误不 repair、原样抛出）。 */
    static boolean isChecksumMismatch(FlywayException e) {
        String msg = e.getMessage();
        return msg != null && msg.toLowerCase(java.util.Locale.ROOT).contains("checksum");
    }
}
