package com.powercms.cms.identity;

import com.powercms.cms.platform.db.MigrationPlaceholderRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DEF-CFG-01 的**存量修复**验收（V130，跑真 SQL、真外键、真方言）。
 *
 * <p>为什么要有这一项：修好创建逻辑只防「将来再产生」孤儿组织，已经落库的两行「默认组织」还在
 * —— 而只要两行都在，读写两侧取第一行的口径就可能再次分叉。V130 必须把既无用户、又无站点、
 * 也无组织设置行的空壳删掉，且**不能碰真实组织**。
 *
 * <p>判别力只能由真 SQL 体现，三点：
 * <ol>
 *   <li>语句在真实库上可执行（列名 / 方言 / H2 与 PostgreSQL 通用的相关子查询）；</li>
 *   <li>**删除顺序覆盖完整外键闭包** —— 引用 {@code sys_role} 的表有三张
 *       （{@code sys_user_role.role_id}、{@code sys_role_layout.role_id}、
 *       {@code workflow_transition.allowed_role_id}），而孤儿组织同样持有内置角色，
 *       漏一张即外键冲突。这正是「测试只建 schema 子集」会漏掉的类别 ——
 *       域 11 实测第一版漏的 {@code sys_role_layout} 就是这么暴露的；</li>
 *   <li>作用域正确 —— 有用户、有站点、有设置行的组织必须原样保留。</li>
 * </ol>
 *
 * <p>因此本用例**用全部真实迁移（V1 ~ V129）在一个独立 H2 内存库上建出完整 schema**，
 * 再灌入复现场景、执行 V130。独立库 ⇒ 不碰共享测试库中其他用例建立的组织，零副作用；
 * 全量迁移 ⇒ 将来新增任何引用 {@code sys_role} 的表，本用例都会立刻失败。
 *
 * <p>（{@code V11} 带 UTF-8 BOM，{@link ScriptUtils} 不会像 Flyway 那样剥离，见 {@code runScript}。）
 */
class OrganizationOrphanCleanupMigrationTest {

    private static final int TARGET_VERSION = 130;
    private static final String MIGRATION_DIR = "db/migration";

    private Connection connection;
    private UUID realOrg;
    private UUID orphanOrg;
    private UUID realRole;
    private UUID orphanRole;
    private UUID realUserId;
    private UUID orphanRoleLayoutId;
    private UUID orphanTransitionId;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection(
            "jdbc:h2:mem:org-cleanup-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
            "sa", "");
        for (String migration : migrationsBefore(TARGET_VERSION)) {
            runScript(migration);
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (connection != null) connection.close();
    }

    @Test
    void v130RemovesEmptyDuplicateOrganizationAndKeepsTheRealOne() throws Exception {
        seedDuplicateDefaultOrganizations();
        assertThat(organizationNames()).as("前置：库内确有两份「默认组织」").containsExactly("默认组织", "默认组织");

        runMigration();

        assertThat(idsOf("select id from organization"))
            .as("只应留下真实组织（有用户/站点/设置行的那一行）")
            .containsExactly(realOrg);
        assertThat(idsOf("select id from sys_role"))
            .as("孤儿组织的内置角色行必须一并清除，否则外键删不掉组织")
            .containsExactly(realRole);
        assertThat(idsOf("select id from sys_user_role"))
            .as("指向孤儿组织角色的角色分配也必须清除")
            .isEmpty();
        assertThat(idsOf("select id from sys_role_layout where id = '" + orphanRoleLayoutId + "'"))
            .as("sys_role_layout 也是 sys_role 的子表 —— 漏了它真库上直接外键冲突")
            .isEmpty();
        assertThat(idsOf("select id from workflow_transition where id = '" + orphanTransitionId + "'"))
            .as("workflow_transition.allowed_role_id 同样引用 sys_role")
            .isEmpty();
        // 真实组织的业务数据必须原样保留
        assertThat(idsOf("select id from sys_user")).containsExactly(realUserId);
        assertThat(idsOf("select id from site")).hasSize(1);
        assertThat(idsOf("select id from organization_settings"))
            .as("真实组织的组织设置行必须保留（组织设置正挂在这一行上）")
            .containsExactly(realOrg);
    }

    @Test
    void v130KeepsOrganizationThatOnlyHasUsers() throws Exception {
        seedDuplicateDefaultOrganizations();
        // 另一行组织：没有任何站点与设置，但有用户 —— 属于「有业务数据」，不得删除
        UUID usersOnlyOrg = UUID.randomUUID();
        execute("insert into organization (id, name, created_at, updated_at) values ('"
            + usersOnlyOrg + "', '只有用户的组织', timestamp '2026-09-20 21:03:33', timestamp '2026-09-20 21:03:33')");
        execute("insert into sys_user (id, organization_id, username, password_hash, display_name) values ('"
            + UUID.randomUUID() + "', '" + usersOnlyOrg + "', 'only-user', '{bcrypt}hash', 'only-user')");

        runMigration();

        assertThat(idsOf("select id from organization"))
            .as("判定「孤儿」的依据是没有任何业务数据，而不是「不是最早那个」")
            .containsExactlyInAnyOrder(realOrg, usersOnlyOrg);
    }

    @Test
    void v130IsNoOpOnEmptyDatabase() throws Exception {
        runMigration();

        assertThat(count("select count(*) from organization")).as("全新库等价空操作").isZero();
    }

    private void seedDuplicateDefaultOrganizations() throws SQLException {
        realOrg = UUID.randomUUID();
        orphanOrg = UUID.randomUUID();
        realRole = UUID.randomUUID();
        orphanRole = UUID.randomUUID();
        realUserId = UUID.randomUUID();
        orphanRoleLayoutId = UUID.randomUUID();
        orphanTransitionId = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();

        // 真实组织更晚创建：域 11 现场正是「孤儿更早、真实更晚」，因此绝不能靠 created_at 区分真伪，
        // 只能靠「有没有业务数据」判定。
        execute("insert into organization (id, name, created_at, updated_at) values ('"
            + orphanOrg + "', '默认组织', timestamp '2026-09-20 21:03:34.993767', timestamp '2026-09-20 21:03:34.993767')");
        execute("insert into organization (id, name, created_at, updated_at) values ('"
            + realOrg + "', '默认组织', timestamp '2026-09-20 21:03:35.001771', timestamp '2026-09-20 21:03:35.001771')");

        execute("insert into sys_role (id, organization_id, code, name, built_in) values ('"
            + realRole + "', '" + realOrg + "', 'system-admin', '系统管理员', true)");
        // 孤儿组织同样持有内置角色（ensureOrganization 会给任意组织补齐角色），故角色不能作为「有数据」的依据
        execute("insert into sys_role (id, organization_id, code, name, built_in) values ('"
            + orphanRole + "', '" + orphanOrg + "', 'system-admin', '系统管理员', true)");

        execute("insert into sys_user (id, organization_id, username, password_hash, display_name) values ('"
            + realUserId + "', '" + realOrg + "', 'admin', '{bcrypt}hash', 'admin')");
        execute("insert into sys_user_role (id, user_id, role_id) values ('"
            + UUID.randomUUID() + "', '" + realUserId + "', '" + orphanRole + "')");
        // sys_role 的另外两张子表：域 11 实测 V130 第一版漏了 sys_role_layout，真库上直接外键冲突
        execute("insert into sys_role_layout (id, role_id, layout_id) values ('"
            + orphanRoleLayoutId + "', '" + orphanRole + "', (select id from sys_layout limit 1))");

        execute("insert into site (id, organization_id, site_key, name) values ('"
            + siteId + "', '" + realOrg + "', 'site-s1', '测试站 S1')");
        execute("insert into workflow_definition (id, site_id, name) values ('"
            + workflowId + "', '" + siteId + "', '默认工作流')");
        execute("insert into workflow_transition (id, workflow_definition_id, source_status, target_status,"
            + " allowed_role_id) values ('"
            + orphanTransitionId + "', '" + workflowId + "', 'DRAFT', 'REVIEW', '" + orphanRole + "')");

        execute("insert into organization_settings (id, login_identifier, updated_at) values ('"
            + realOrg + "', 'EMAIL', timestamp '2026-09-21 05:27:39')");
    }

    private void runMigration() throws IOException {
        runScript(MIGRATION_DIR + "/V" + TARGET_VERSION + "__dedupe_default_organization.sql");
    }

    /**
     * 执行一个迁移脚本。{@code V11} 带 UTF-8 BOM，Flyway 会自行剥离而
     * {@link ScriptUtils} 不会（BOM 会被当成语句第一个字符 → 语法错误），故此处统一去掉；
     * 多库占位符 {@code «token»} 同理由 Flyway 注入，本路径须经
     * {@link MigrationPlaceholderRenderer} 按 ANSI（H2）取值自行渲染。
     */
    private void runScript(String classpathName) throws IOException {
        byte[] bytes = new ClassPathResource(classpathName).getContentAsByteArray();
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
            && (bytes[2] & 0xFF) == 0xBF) {
            bytes = Arrays.copyOfRange(bytes, 3, bytes.length);
        }
        String sql = MigrationPlaceholderRenderer.render(new String(bytes, StandardCharsets.UTF_8), connection);
        ScriptUtils.executeSqlScript(connection,
            new EncodedResource(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8));
    }

    /** 按版本升序取出目标版本之前的全部真实迁移，使被测脚本跑在完整 schema 上。 */
    private List<String> migrationsBefore(int version) throws IOException {
        File folder = new ClassPathResource(MIGRATION_DIR).getFile();
        try (var paths = Files.list(folder.toPath())) {
            return paths
                .map(path -> path.getFileName().toString())
                .filter(name -> name.matches("V\\d+__.+\\.sql"))
                .filter(name -> versionOf(name) < version)
                .sorted(Comparator.comparingInt(OrganizationOrphanCleanupMigrationTest::versionOf))
                .map(name -> MIGRATION_DIR + "/" + name)
                .toList();
        }
    }

    private static int versionOf(String fileName) {
        return Integer.parseInt(fileName.substring(1, fileName.indexOf("__")));
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private int count(String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private List<String> organizationNames() throws SQLException {
        List<String> names = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("select name from organization order by created_at, id")) {
            while (rs.next()) names.add(rs.getString(1));
        }
        return names;
    }

    private List<UUID> idsOf(String sql) throws SQLException {
        List<UUID> ids = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) ids.add((UUID) rs.getObject(1));
        }
        return ids;
    }
}
