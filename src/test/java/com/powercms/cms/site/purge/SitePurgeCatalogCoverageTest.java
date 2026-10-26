package com.powercms.cms.site.purge;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 站点删除目录的**完整性回归**（FR-SITE-LC-007）：拿真实迁移 {@code db/migration} 与
 * 实体源码逐项比对 {@link SitePurgeCatalog}／{@link SitePurgePlanner}。
 *
 * <p>这是本特性的核心安全网——目录漏一条，删除就会留下孤儿行（残留数据），
 * 或按错误顺序删到一半被外键挡住（部分删除）。任何断言失败都必须**修目录**，
 * 而不是放宽断言。</p>
 *
 * <p>迁移脚本按方言占位符（«uuid»、«tbl_release» 等）解析前先做预处理：
 * {@code «tbl_x» → x}（表名占位符），其余 {@code «token»} 替换为无害标识符。</p>
 */
class SitePurgeCatalogCoverageTest {

    /** 建表语句：{@code create table <name> ( … }。 */
    private static final Pattern CREATE_TABLE =
        Pattern.compile("(?i)create\\s+table\\s+(\\w+)\\s*\\(");
    /** 外键子句：{@code foreign key (a, b) references parent (…)}（建表体与 ALTER 均适用）。 */
    private static final Pattern FK_CLAUSE =
        Pattern.compile("(?i)foreign\\s+key\\s*\\(([^)]+)\\)\\s*references\\s+(\\w+)\\s*\\(");
    /** 列级引用：{@code <col> <type> … references parent (…)}。 */
    private static final Pattern REFERENCES =
        Pattern.compile("(?i)\\breferences\\s+(\\w+)\\s*\\(");
    private static final Pattern ALTER_TABLE =
        Pattern.compile("(?i)\\balter\\s+table\\s+(\\w+)\\b");
    private static final Pattern ADD_COLUMN =
        Pattern.compile("(?i)\\badd\\s+(?:column\\s+)?(\\w+)");
    private static final Pattern FIRST_WORD =
        Pattern.compile("^\\s*(\\w+)");

    /** 段的首词是这些词 → 该段是表约束（primary key / unique / check / constraint…），不是列定义。 */
    private static final Set<String> CONSTRAINT_KEYWORDS = Set.of(
        "constraint", "primary", "foreign", "unique", "check", "key");

    /**
     * 指向 {@code site(id)} 但<b>不是</b> {@code site_id} 列的引用：必须逐个显式登记
     * （要么是阻断项——别人指向本站，要么是 {@link SitePurgeCatalog#SITE_REF_COLUMNS}）。
     */
    private static final Set<String> KNOWN_NON_SITE_ID_SITE_REFS = Set.of(
        "sys_user.home_site_id",
        "release_theme_version.owner_site_id",
        "site_theme_share.consumer_site_id");

    private static final Set<String> migrationSiteIdTables = new HashSet<>();
    private static final Set<String> siteIdReferencingColumns = new HashSet<>();
    private static final List<Fk> migrationFks = new ArrayList<>();
    private static Set<String> entityTables;

    private record Fk(String child, String column, String parent) {}

    // ---------------- 迁移扫描 ----------------

    @BeforeAll
    static void scanMigrationsAndEntities() throws IOException {
        Path dir = migrationsDir();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().matches("V\\d+.*\\.sql"))
                .sorted()
                .forEach(SitePurgeCatalogCoverageTest::scanFile);
        }
        entityTables = scanEntities();
    }

    private static void scanFile(Path file) {
        String sql = preprocess(readQuietly(file));
        Matcher create = CREATE_TABLE.matcher(sql);
        while (create.find()) {
            String table = create.group(1).toLowerCase();
            for (String seg : splitTopLevel(extractBody(sql, create.end()))) {
                classifyColumnSegment(table, seg);
            }
        }
        Matcher alter = ALTER_TABLE.matcher(sql);
        while (alter.find()) {
            String table = alter.group(1).toLowerCase();
            int end = sql.indexOf(';', alter.end());
            classifyAlterStatement(table, sql.substring(alter.end(), end < 0 ? sql.length() : end));
        }
    }

    /** 建表体里的一段（顶层逗号切分）：列定义或表约束。 */
    private static void classifyColumnSegment(String table, String seg) {
        Matcher fw = FIRST_WORD.matcher(seg);
        if (!fw.find()) return;
        String first = fw.group(1).toLowerCase();

        Matcher fk = FK_CLAUSE.matcher(seg);
        boolean sawFk = false;
        while (fk.find()) {
            sawFk = true;
            for (String col : fk.group(1).split(",")) {
                addFk(table, col.trim().toLowerCase(), fk.group(2).toLowerCase());
            }
        }
        if (first.equals("site_id")) migrationSiteIdTables.add(table);
        if (sawFk) return;

        Matcher ref = REFERENCES.matcher(seg);
        if (ref.find() && !CONSTRAINT_KEYWORDS.contains(first)) {
            addFk(table, first, ref.group(1).toLowerCase());
        }
    }

    /** ALTER 语句（表名之后到分号）。 */
    private static void classifyAlterStatement(String table, String stmt) {
        Matcher fk = FK_CLAUSE.matcher(stmt);
        boolean sawFk = false;
        while (fk.find()) {
            sawFk = true;
            for (String col : fk.group(1).split(",")) {
                addFk(table, col.trim().toLowerCase(), fk.group(2).toLowerCase());
            }
        }
        if (sawFk) return;

        Matcher add = ADD_COLUMN.matcher(stmt);
        if (!add.find()) return;
        String col = add.group(1).toLowerCase();
        if (col.equals("site_id")) migrationSiteIdTables.add(table);
        Matcher ref = REFERENCES.matcher(stmt);
        if (ref.find()) {
            addFk(table, col, ref.group(1).toLowerCase());
        }
    }

    /** 登记一条声明外键（或列级引用）；指向 site(id) 的列单独归类。 */
    private static void addFk(String child, String column, String parent) {
        migrationFks.add(new Fk(child, column, parent));
        if (column.equals("site_id")) migrationSiteIdTables.add(child);
        if (parent.equals("site")) siteIdReferencingColumns.add(child + "." + column);
    }

    /**
     * 方言占位符预处理：{@code «tbl_release» → release}（表名占位符），
     * 其余 {@code «token»}（类型/默认值/约束片段）→ {@code tok}。
     */
    private static String preprocess(String sql) {
        return sql.replaceAll("(?m)--.*$", "")          // 行注释
            .replaceAll("«tbl_(\\w+)»", "$1")           // «tbl_release» → release
            .replaceAll("«\\w+»", "tok");               // «uuid»/«def_now»/… → 无害标识符
    }

    /** 从 {@code (} 之后按括号配平截取建表体。 */
    private static String extractBody(String sql, int start) {
        int depth = 1;
        int i = start;
        while (i < sql.length() && depth > 0) {
            char c = sql.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            i++;
        }
        return sql.substring(start, Math.max(start, i - 1));
    }

    /** 顶层逗号切分（列与列之间），括号内逗号（varchar(10,2)、unique(a,b)）不切。 */
    private static List<String> splitTopLevel(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        if (start < s.length()) out.add(s.substring(start));
        return out;
    }

    // ---------------- 实体扫描 ----------------

    /** 实体源码里带 {@code siteId} 字段的表名（@Table(name=…)，缺省回退实体类名蛇形）。 */
    private static Set<String> scanEntities() throws IOException {
        Set<String> tables = new HashSet<>();
        List<Path> roots = new ArrayList<>();
        Path core = Path.of("src/main/java");
        if (Files.isDirectory(core)) roots.add(core);
        Path common = Path.of("../cms-common/src/main/java");
        if (Files.isDirectory(common)) roots.add(common);
        assertThat(roots).as("实体源码根目录不可为空（工作目录: %s）", Path.of("").toAbsolutePath())
            .isNotEmpty();
        Pattern siteIdField = Pattern.compile("private\\s+UUID\\s+siteId\\s*;");
        for (Path root : roots) {
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                    String src = readQuietly(p);
                    if (!src.contains("@Entity") || !siteIdField.matcher(src).find()) return;
                    tables.add(tableNameOf(p, src));
                });
            }
        }
        return tables;
    }

    private static String tableNameOf(Path file, String src) {
        int at = src.indexOf("@Table");
        if (at >= 0) {
            int open = src.indexOf('(', at);
            if (open > 0) {
                int depth = 0;
                int i = open;
                for (; i < src.length(); i++) {
                    char c = src.charAt(i);
                    if (c == '(') depth++;
                    else if (c == ')') {
                        depth--;
                        if (depth == 0) break;
                    }
                }
                String region = src.substring(open, Math.min(i, src.length()));
                Matcher name = Pattern.compile("name\\s*=\\s*\"(\\w+)\"").matcher(region);
                if (name.find()) return name.group(1);
            }
        }
        // 无 @Table：Hibernate 隐式命名 = 类名蛇形（含 Entity 后缀）
        String cls = file.getFileName().toString().replace(".java", "");
        return cls.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }

    private static String readQuietly(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new IllegalStateException("无法读取源文件: " + p, e);
        }
    }

    private static Path migrationsDir() {
        for (String candidate : new String[] {
            "src/main/resources/db/migration",
            "server/cms-core/src/main/resources/db/migration"}) {
            Path p = Path.of(candidate);
            if (Files.isDirectory(p)) return p;
        }
        throw new IllegalStateException("找不到 db/migration 目录（工作目录: "
            + Path.of("").toAbsolutePath() + "）");
    }

    // ---------------- 断言 ----------------

    @Test
    @DisplayName("迁移里每个带 site_id 列的表都登记在 SITE_TABLES（或被明确保留为审计）")
    void everyMigrationSiteIdTableIsCatalogued() {
        assertThat(migrationSiteIdTables)
            .contains("release", "content_item", "audit_event"); // 解析冒烟：三种建表写法都要认出来
        Set<String> allowed = new HashSet<>(SitePurgeCatalog.SITE_TABLES);
        allowed.addAll(SitePurgeCatalog.RETAINED_TABLES);
        assertThat(migrationSiteIdTables)
            .as("迁移建表带 site_id 却未登记 = 删除后残留行")
            .isSubsetOf(allowed);
    }

    @Test
    @DisplayName("SITE_TABLES 里的每个表都真实存在且带 site_id 列（拼写/孤儿校验）")
    void cataloguedTablesExistInMigrations() {
        assertThat(SitePurgeCatalog.SITE_TABLES)
            .as("目录里的表必须在迁移中带 site_id 列存在")
            .isSubsetOf(migrationSiteIdTables);
        assertThat(SitePurgeCatalog.EXTRA_SITE_TABLES).isSubsetOf(SitePurgeCatalog.SITE_TABLES);
    }

    @Test
    @DisplayName("审计表保留、且不参与站点删除")
    void auditTableIsRetainedAndNeverPurged() {
        assertThat(SitePurgeCatalog.RETAINED_TABLES).contains("audit_event");
        assertThat(SitePurgeCatalog.SITE_TABLES).doesNotContain("audit_event");
        assertThat(migrationSiteIdTables).contains("audit_event");
    }

    @Test
    @DisplayName("实体里带 siteId 字段的表全部登记（实体层看得见的表不能漏）")
    void entitySiteIdTablesAreCatalogued() {
        assertThat(entityTables).as("实体扫描必须有产出").isNotEmpty()
            .contains("content_item", "site_theme");
        Set<String> allowed = new HashSet<>(SitePurgeCatalog.SITE_TABLES);
        allowed.addAll(SitePurgeCatalog.RETAINED_TABLES);
        assertThat(entityTables)
            .as("实体带 siteId 却未登记 = 删除后残留行")
            .isSubsetOf(allowed);
    }

    @Test
    @DisplayName("有实体的目录表都能被实体扫描命中（EXTRA_SITE_TABLES 没有把有实体的表误列为『无实体』）")
    void nonExtraTablesHaveEntities() {
        Set<String> expected = new HashSet<>(SitePurgeCatalog.SITE_TABLES);
        expected.removeAll(SitePurgeCatalog.EXTRA_SITE_TABLES);
        Set<String> missing = new HashSet<>(expected);
        missing.removeAll(entityTables);
        assertThat(missing)
            .as("这些表既不在 EXTRA_SITE_TABLES（声明无实体）里、扫描也没找到实体——"
                + "要么补实体扫描规则，要么把表移入 EXTRA_SITE_TABLES")
            .isEmpty();
    }

    @Test
    @DisplayName("声明外键里「站点作用域表之间」的顺序边全部在 EDGES 中（子先删、父后删）")
    void declaredForeignKeysHaveOrderingEdges() {
        Set<String> nodes = new HashSet<>(SitePurgeCatalog.SITE_TABLES);
        nodes.addAll(SitePurgeCatalog.RETAINED_TABLES);
        SitePurgeCatalog.EDGES.forEach(e -> {
            nodes.add(e.child());
            nodes.add(e.parent());
        });
        Set<String> edgeKeys = new HashSet<>();
        SitePurgeCatalog.EDGES.forEach(e -> edgeKeys.add(e.child() + "->" + e.parent()));

        Set<String> missing = new HashSet<>();
        for (Fk fk : migrationFks) {
            if (fk.child().equals(fk.parent())) continue;          // 自引用：作用域已覆盖
            if ("site".equals(fk.parent())) continue;              // 指向 site 行：最后删 site 行
            if (!nodes.contains(fk.child()) || !nodes.contains(fk.parent())) continue; // 全局父表
            if (!edgeKeys.contains(fk.child() + "->" + fk.parent())) {
                missing.add(fk.child() + "." + fk.column() + " -> " + fk.parent());
            }
        }
        assertThat(missing)
            .as("外键要求「子先删」但目录缺边 → 按目录顺序删除会撞外键（部分删除）")
            .isEmpty();
    }

    @Test
    @DisplayName("迁移里的自引用外键（同表父子）全部登记为解环置空列——否则同条 DELETE 撞立即外键检查")
    void selfReferencingForeignKeysAreCycleBroken() {
        Set<String> scopeChildren = new HashSet<>();               // 卫星表 = EDGES 里出现过的子表
        SitePurgeCatalog.EDGES.forEach(e -> scopeChildren.add(e.child()));
        Set<String> breakers = new HashSet<>();
        SitePurgePlanner.CYCLE_BREAKERS.forEach((t, cols) ->
            cols.forEach(c -> breakers.add(t + "#" + c)));

        Set<String> selfRefs = new HashSet<>();
        for (Fk fk : migrationFks) {
            if (fk.child().equals(fk.parent())) selfRefs.add(fk.child() + "." + fk.column());
        }
        assertThat(selfRefs)
            .as("解析冒烟：迁移里的自引用外键必须被识别出来")
            .contains("field_definition.parent_id", "category.parent_id",
                "content_type.parent_type_id", "site_theme.parent_theme_id");

        Set<String> missing = new HashSet<>();
        for (String selfRef : selfRefs) {
            int dot = selfRef.indexOf('.');
            String table = selfRef.substring(0, dot);
            if (!SitePurgeCatalog.SITE_TABLES.contains(table) && !scopeChildren.contains(table)) {
                continue;                                          // 全局表不参与站点删除
            }
            if (!breakers.contains(selfRef.replace('.', '#'))) missing.add(selfRef);
        }
        assertThat(missing)
            .as("自引用外键未登记 CYCLE_BREAKERS → H2 逐行外键检查下同条 DELETE 内父子互删即失败"
                + "（PostgreSQL 恰好能过，不能依赖方言差异）")
            .isEmpty();

        // 置空语句复用删除谓词：卫星表按引用边取作用域，站点表用 site_id
        SitePurgePlanner.SitePurgePlan plan = new SitePurgePlanner(true).plan();
        assertThat(plan.breakStatements())
            .as("field_definition 无 site_id 列，置空必须按 content_type 边限定作用域")
            .anyMatch(s -> s.startsWith(
                "UPDATE field_definition SET parent_id = NULL WHERE content_type_id IN (SELECT id FROM content_type WHERE site_id = ?)"));
        assertThat(plan.breakStatements())
            .anyMatch(s -> s.startsWith("UPDATE category SET parent_id = NULL WHERE site_id = ?"))
            .anyMatch(s -> s.startsWith("UPDATE content_type SET parent_type_id = NULL WHERE site_id = ?"));
    }

    @Test
    @DisplayName("指向 site(id) 的列只有三类：site_id 列 / 阻断项 / 站点引用列——全部显式登记")
    void siteIdReferencingColumnsAreClassified() {
        Set<String> known = new HashSet<>();
        SitePurgeCatalog.SITE_TABLES.forEach(t -> known.add(t + ".site_id"));
        SitePurgeCatalog.RETAINED_TABLES.forEach(t -> known.add(t + ".site_id"));
        SitePurgeCatalog.SITE_REF_COLUMNS.forEach((t, cols) ->
            cols.forEach(c -> known.add(t + "." + c)));
        known.addAll(KNOWN_NON_SITE_ID_SITE_REFS);

        Set<String> unclassified = new HashSet<>(siteIdReferencingColumns);
        unclassified.removeAll(known);
        assertThat(unclassified)
            .as("有列直接指向 site(id) 却不在删除/阻断清单里 → 删 site 行时会撞外键，"
                + "或静默丢掉「别人指向本站」的阻断提示")
            .isEmpty();
        assertThat(siteIdReferencingColumns)
            .as("登记的站点引用必须真实存在（目录与库结构保持同步）")
            .containsAll(KNOWN_NON_SITE_ID_SITE_REFS);
    }

    @Test
    @DisplayName("引用边端点与表名都是安全标识符（进 SQL 拼接前的白名单自检）")
    void edgesUseSafeIdentifiers() {
        SitePurgeCatalog.EDGES.forEach(e -> {
            assertThat(SitePurgeCatalog.isSafeIdentifier(e.child())).as(e.child()).isTrue();
            assertThat(SitePurgeCatalog.isSafeIdentifier(e.column())).as(e.column()).isTrue();
            assertThat(SitePurgeCatalog.isSafeIdentifier(e.parent())).as(e.parent()).isTrue();
        });
        SitePurgeCatalog.SITE_TABLES.forEach(t ->
            assertThat(SitePurgeCatalog.isSafeIdentifier(t)).as("表名 %s", t).isTrue());
    }

    @Test
    @DisplayName("目录内没有重复的边声明（子表/列/父表三元组唯一）")
    void noDuplicateEdges() {
        Map<String, String> seen = new HashMap<>();
        for (SitePurgeCatalog.Edge e : SitePurgeCatalog.EDGES) {
            String key = e.child() + "|" + e.column() + "|" + e.parent();
            assertThat(seen.put(key, e.column()))
                .as("边 %s → %s.%s 重复声明", e.child(), e.column(), e.parent())
                .isNull();
        }
    }

    @Test
    @DisplayName("影响面清单全部是站点作用域表（确认弹窗计数 SQL 必须带 site_id 列）")
    void impactTablesAreSiteScoped() {
        assertThat(SitePurgeCatalog.IMPACT_TABLES).isSubsetOf(SitePurgeCatalog.SITE_TABLES);
    }

    @Test
    @DisplayName("删除计划覆盖全部站点作用域表，且解环置空语句存在（计划器可执行）")
    void planCoversEverySiteScopedTable() {
        SitePurgePlanner.SitePurgePlan plan = new SitePurgePlanner(true).plan(); // 目录错则抛
        Set<String> covered = new HashSet<>();
        for (String sql : plan.deleteStatements()) {
            Matcher m = Pattern.compile("^DELETE FROM (\\w+) ").matcher(sql);
            assertThat(m.find()).as("删除语句格式: %s", sql).isTrue();
            covered.add(m.group(1));
        }
        Set<String> expected = new HashSet<>(SitePurgeCatalog.SITE_TABLES);
        expected.removeAll(SitePurgeCatalog.RETAINED_TABLES);
        assertThat(covered)
            .as("每个站点作用域表都必须有删除语句（否则删完残留行）")
            .containsAll(expected);
        assertThat(plan.breakStatements())
            .as("agent_task ↔ agent_message 互引环必须有解环置空语句")
            .isNotEmpty();
        // 保留表绝不能出现在删除语句里
        SitePurgeCatalog.RETAINED_TABLES.forEach(t -> assertThat(covered).doesNotContain(t));
    }
}
