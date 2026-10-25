package com.powercms.cms.platform.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 测试内直跑 Flyway 原始迁移脚本（不经 Flyway）时的占位符渲染器。
 *
 * <p>多库适配后迁移脚本统一写 {@code «token»}（见主配置 spring.flyway.placeholder-prefix），
 * 由 Flyway 在执行前按各库 profile 的 yml 取值替换。但部分验收用例（如
 * {@code OrganizationOrphanCleanupMigrationTest}、{@code NotificationTemplateTaskDeeplinkMigrationTest}）
 * 为跑「真 SQL、真外键」而用 {@code ScriptUtils} 直接执行原始脚本 —— 这条路径绕过了 Flyway，
 * 必须在此自行渲染，否则 {@code «uuid»} 会原样进库报语法错。</p>
 *
 * <p>取值与主配置同源，按当前方言组选 yml（db-mysql → mysql、db-dameng → dm，
 * 其余 H2/PostgreSQL/金仓 → ansi，见 {@link #ymlFor(java.sql.Connection)}）。渲染后仍残留占位符即抛错，
 * 防止脚本新增 token 而 yml 漏配时静默把 {@code «} 语法送进数据库。</p>
 */
public final class MigrationPlaceholderRenderer {

    private static final char OPEN = '\u00ab';   // «
    private static final char CLOSE = '\u00bb';  // »

    /** 各方言组 yml 的占位符取值缓存（key = yml 文件名，首次渲染时按需加载）。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, Map<String, String>> CACHE =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 按<b>实际连接</b>的数据库产品选组——而非激活 profile：org/notif 回放用例固定跑
     * H2 内存库，profile=db-mysql 时按 profile 选组曾把 auto_increment 渲进 H2 报语法错。
     * MySQL → mysql 组，DM DBMS → dm 组，H2/PostgreSQL/KingbaseES → ansi 组。
     */
    private static String ymlFor(java.sql.Connection connection) {
        try {
            java.sql.DatabaseMetaData md = connection.getMetaData();
            String product = md.getDatabaseProductName();
            if ("MySQL".equalsIgnoreCase(product)) {
                // 按服务器主版本选组：≥8.0 → mysql 组（datetime(6)、括号 TEXT 默认、3072 键），
                // <8.0（5.5/5.6/5.7）→ mysql55 组（datetime、无 TEXT 默认、767 键前缀 191）。
                // 与 profile 无关：同为 db-mysql55 profile 下跑 H2 内存库的用例仍落 ansi 组。
                int major = md.getDatabaseMajorVersion();
                return major >= 8
                    ? "powercms-db-placeholders-mysql.sql.yml"
                    : "powercms-db-placeholders-mysql55.sql.yml";
            }
            if (product != null && product.toUpperCase(java.util.Locale.ROOT).startsWith("DM")) {
                return "powercms-db-placeholders-dm.sql.yml";
            }
            return "powercms-db-placeholders-ansi.sql.yml";
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("读取数据库产品名失败（选占位符组）", e);
        }
    }

    private MigrationPlaceholderRenderer() {
    }

    private static Map<String, String> load(String yml) {
        try (var in = new ClassPathResource(yml).getInputStream()) {
            JsonNode placeholders = new ObjectMapper(new YAMLFactory())
                .readTree(in)
                .path("spring").path("flyway").path("placeholders");
            if (!placeholders.isObject() || placeholders.isEmpty()) {
                throw new IllegalStateException(
                    yml + " 缺少 spring.flyway.placeholders 节点");
            }
            Map<String, String> map = new LinkedHashMap<>();
            placeholders.fields().forEachRemaining(
                entry -> map.put(entry.getKey(), entry.getValue().asText()));
            return Collections.unmodifiableMap(map);
        } catch (IOException e) {
            throw new UncheckedIOException("加载占位符取值失败: " + yml, e);
        }
    }

    /**
     * 按连接所属方言组取值渲染脚本中的全部 {@code «token»}（组选择见 {@link #ymlFor}）。
     *
     * @throws IllegalStateException 渲染后仍有残留占位符（yml 缺 key）时抛出
     */
    public static String render(String sql, java.sql.Connection connection) {
        Map<String, String> placeholders =
            CACHE.computeIfAbsent(ymlFor(connection), MigrationPlaceholderRenderer::load);
        String out = sql;
        for (var entry : placeholders.entrySet()) {
            out = out.replace(OPEN + entry.getKey() + CLOSE, entry.getValue());
        }
        int idx = out.indexOf(OPEN);
        if (idx >= 0) {
            int end = Math.min(out.length(), idx + 48);
            throw new IllegalStateException(
                "未渲染的占位符（yml 缺 key 或 token 拼写不符）：" + out.substring(idx, end));
        }
        return out;
    }
}
