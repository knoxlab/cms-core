package com.powercms.cms.workflow;

import com.powercms.cms.platform.db.MigrationPlaceholderRenderer;
import com.powercms.cms.workflow.entity.NotificationTemplateEntity;
import com.powercms.cms.workflow.repo.NotificationTemplateRepository;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OBS-CFG-02 的存量修复验收（V131，跑真 SQL、真 FreeMarker 渲染）。
 *
 * <p>为什么要有这一项：{@code DEF-CFG-03} 把组织设置的 {@code portalUrl} 接进了发信上下文
 *（{@code EmailNotificationChannel.eventModel} 恒注入 {@code portalUrl} / {@code taskUrl}），
 * 但出厂模板由 {@code V34} 种下、正文里不含任何深链变量，而发信时
 * {@code NotificationTemplateService.render()} **优先取数据库行**（站点覆盖 → 全局行）。
 * 结果：代码兜底模板里那行 {@code <#if taskUrl?has_content>查看任务：${taskUrl}</#if>}
 * 在真实安装上永远不会被用到 —— 「能存、能读、注入到位，却看不见」。</p>
 *
 * <p>判别力由三段证据构成，缺一段都可能是假绿：
 * <ol>
 *   <li><b>真 SQL</b>：V131 在完整 schema（V1~V130 全量迁移）上可执行，且 7 条出厂模板全部被补上
 *       深链行；</li>
 *   <li><b>作用域正确</b>：站点覆盖行（{@code site_id} 非空）与已自行引用 {@code taskUrl} 的行
 *       必须原样保留 —— 模板正文是可被运维接管的业务内容，迁移无权覆盖；</li>
 *   <li><b>真渲染</b>：把迁移后的 {@code body_text} 灌回真实的
 *       {@link NotificationTemplateService} + FreeMarker，注入 {@code taskUrl} 时产出绝对链接、
 *       未配置 {@code portalUrl}（{@code taskUrl} 为空串）时整行消失且不误落到兜底文案。</li>
 * </ol>
 *
 * <p>用独立 H2 内存库跑全部真实迁移，不碰共享测试库，零副作用（与
 * {@code OrganizationOrphanCleanupMigrationTest} 同法）。</p>
 */
class NotificationTemplateTaskDeeplinkMigrationTest {

    private static final int TARGET_VERSION = 131;
    private static final String MIGRATION_DIR = "db/migration";
    private static final String MIGRATION = "V131__notification_template_task_deeplink.sql";

    /** V34 种下的出厂模板键，与 {@code NotificationTemplateService.KEYS} 一致。 */
    private static final List<String> FACTORY_KEYS = List.of(
        "task_submitted", "task_approved", "task_rejected", "publish_succeeded",
        "build_failed", "task_timeout", "task_delegated");

    private static final String GUARD = "<#if taskUrl?has_content>查看任务：${taskUrl}</#if>";

    /**
     * 本用例刻意挑一条出厂模板，把它先改成「已自行引用 {@code taskUrl}」的形态，
     * 以覆盖 V131 的「不二次追加」分支（运维可能已按自己的版式在全局模板里引用过深链）。
     * 因此「7 条全部被追加」的断言必须把它排除，由
     * {@link #v131DoesNotAppendAgainWhenTemplateAlreadyReferencesTaskUrl()} 单独负责。
     */
    private static final String PRE_LINKED_KEY = "task_timeout";

    private Connection connection;
    private UUID overlaySiteId;
    private String overlayBodyBefore;
    private String alreadyLinkedBodyBefore;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection(
            "jdbc:h2:mem:notif-deeplink-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
            "sa", "");
        for (String migration : migrationsBefore(TARGET_VERSION)) {
            runScript(migration);
        }
        seedOverlayAndPreLinkedTemplate();
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (connection != null) connection.close();
    }

    @Test
    void v131AppendsDeeplinkLineToEveryFactoryTemplate() throws Exception {
        runMigration();

        // PRE_LINKED_KEY 已被刻意改成「已引用」形态，由下一条用例单独断言「不追加」。
        for (String key : FACTORY_KEYS) {
            if (PRE_LINKED_KEY.equals(key)) continue;
            assertThat(textBody(key))
                .as("出厂模板 %s 的纯文本正文必须补上深链行（真实发信走 body_text）", key)
                .contains(GUARD);
            assertThat(htmlBody(key))
                .as("出厂模板 %s 的 HTML 正文（模板管理界面预览用）同样要带深链行", key)
                .contains("查看任务：<a href=\"${taskUrl}\">${taskUrl}</a>");
            assertThat(updatedBy(key))
                .as("模板内容被迁移改写，留痕方为 system，便于运维区分「出厂更新」与「人工编辑」", key)
                .isEqualTo("system");
        }
    }

    @Test
    void v131KeepsSiteOverlayTemplateUntouched() throws Exception {
        runMigration();

        assertThat(bodyTextOfSite(overlaySiteId))
            .as("站点覆盖行是运维按站点口径写过的文案，迁移不得追加或改写")
            .isEqualTo(overlayBodyBefore);
    }

    @Test
    void v131DoesNotAppendAgainWhenTemplateAlreadyReferencesTaskUrl() throws Exception {
        runMigration();

        String after = textBody(PRE_LINKED_KEY);
        assertThat(after)
            .as("已自行引用深链变量的出厂模板不得被二次追加")
            .isEqualTo(alreadyLinkedBodyBefore);
        assertThat(countOccurrences(after, "taskUrl"))
            .as("恰恰只保留运维写下的那一处引用")
            .isEqualTo(countOccurrences(alreadyLinkedBodyBefore, "taskUrl"));
        assertThat(updatedBy(PRE_LINKED_KEY))
            .as("整行都不得被触碰：留痕仍是运维，而不是迁移的 system")
            .isEqualTo("ops");
    }

    @Test
    void migratedFactoryTemplateRendersAbsoluteDeeplinkThroughRealRenderer() throws Exception {
        runMigration();

        UUID taskId = UUID.randomUUID();
        var rendered = renderFactoryTemplate("task_submitted", taskId);

        assertThat(rendered.text())
            .as("迁移后的正文必须真正渲染出绝对深链")
            .contains("查看任务：https://portal.example.com/tasks/" + taskId);
        assertThat(rendered.text())
            .as("渲染走的是模板而不是异常兜底 —— 兜底文案里没有这句")
            .contains("内容《示例文章》等待审核");
    }

    @Test
    void migratedFactoryTemplateOmitsLinkLineWhenPortalUrlNotConfigured() throws Exception {
        runMigration();

        var rendered = renderFactoryTemplate("task_submitted", null);

        assertThat(rendered.text())
            .as("未配置 portalUrl 时 taskUrl 为空串，整行必须消失（不能渲染出「查看任务：」半截）")
            .doesNotContain("查看任务");
        assertThat(rendered.text()).doesNotContain("null");
        assertThat(rendered.text())
            .as("对照：模板本身仍然正常渲染")
            .contains("内容《示例文章》等待审核");
    }

    // ---- 以下为脚手架 ----

    /**
     * 用迁移后的 {@code body_text} 灌回真实渲染器。{@code taskId} 为 {@code null} 模拟
     * 「组织未配置 portalUrl」——此时 {@code EmailNotificationChannel.taskUrl()} 返回空串。
     */
    private NotificationTemplateService.Rendered renderFactoryTemplate(String key, UUID taskId)
            throws SQLException {
        var entity = new NotificationTemplateEntity();
        entity.setId(UUID.randomUUID());
        entity.setSiteId(null);
        entity.setTemplateKey(key);
        entity.setSubject(subject(key));
        entity.setBodyHtml(htmlBody(key));
        entity.setBodyText(textBody(key));

        NotificationTemplateRepository repo = mock(NotificationTemplateRepository.class);
        when(repo.findBySiteIdIsNullAndTemplateKey(key)).thenReturn(Optional.of(entity));

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("contentTitle", "示例文章");
        model.put("submitter", "editor");
        model.put("reviewer", "reviewer");
        model.put("portalUrl", "https://portal.example.com");
        model.put("taskUrl", taskId == null ? "" : "https://portal.example.com/tasks/" + taskId);

        return new NotificationTemplateService(repo).render(null, key, model, "兜底主题", "兜底正文");
    }

    private void seedOverlayAndPreLinkedTemplate() throws SQLException {
        overlaySiteId = UUID.randomUUID();
        overlayBodyBefore = "站点自定义：内容《${contentTitle}》待审，请联系值班编辑。";
        execute("insert into notification_template (id, site_id, template_key, subject, body_html, body_text,"
            + " updated_by, updated_at) values ('" + UUID.randomUUID() + "', '" + overlaySiteId + "',"
            + " 'task_submitted', '站点自定义', '<p>site</p>', '" + overlayBodyBefore + "', 'ops',"
            + " current_timestamp)");

        alreadyLinkedBodyBefore = "内容《${contentTitle}》已超时。\n任务：${taskUrl}";
        execute("update notification_template set body_text = '" + alreadyLinkedBodyBefore
            + "', body_html = '<p><a href=\"${taskUrl}\">任务</a></p>', updated_by = 'ops'"
            + " where site_id is null and template_key = '" + PRE_LINKED_KEY + "'");
    }

    private void runMigration() throws IOException {
        runScript(MIGRATION_DIR + "/" + MIGRATION);
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
                .sorted(Comparator.comparingInt(NotificationTemplateTaskDeeplinkMigrationTest::versionOf))
                .map(name -> MIGRATION_DIR + "/" + name)
                .toList();
        }
    }

    private static int versionOf(String fileName) {
        return Integer.parseInt(fileName.substring(1, fileName.indexOf("__")));
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    private String textBody(String key) throws SQLException {
        return column(key, "body_text");
    }

    private String htmlBody(String key) throws SQLException {
        return column(key, "body_html");
    }

    private String subject(String key) throws SQLException {
        return column(key, "subject");
    }

    private String updatedBy(String key) throws SQLException {
        return column(key, "updated_by");
    }

    private String column(String key, String column) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                 "select " + column + " from notification_template where site_id is null"
                     + " and template_key = '" + key + "'")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private String bodyTextOfSite(UUID siteId) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                 "select body_text from notification_template where site_id = '" + siteId + "'")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
