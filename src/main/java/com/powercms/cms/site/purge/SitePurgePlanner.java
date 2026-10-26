package com.powercms.cms.site.purge;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把 {@link SitePurgeCatalog} 变成可执行的删除计划：
 * 按「子先父后」拓扑排序，并给没有 {@code site_id} 列的卫星表生成作用域子查询。
 *
 * <p>顺序约束（任何一条不满足都会被外键挡住）：</p>
 * <ul>
 *   <li>所有边的子表都<b>先于</b>父表删除（外键：父行必须等子行清空）；</li>
 *   <li>互引环先在本站范围内把 {@link #CYCLE_BREAKERS} 声明的列置空解环；</li>
 *   <li>指向 {@code site(id)} 的本站列（{@link SitePurgeCatalog#SITE_REF_COLUMNS}）
 *       在 {@code site} 行删除前处理（service 最后删 site 行）。</li>
 * </ul>
 *
 * <p>目录写错会在构造期抛 {@link IllegalArgumentException}——启动/测试立刻红，而不是删到一半才炸。</p>
 */
public final class SitePurgePlanner {

    /**
     * 解环/自引用置空：删除前先在站点作用域内把「同表互指」的列置空。
     * 两类：互引环（agent_task.message_id ↔ agent_message.task_id）与自引用 FK（parent_id → id）。
     * 为何必须置空：PostgreSQL 的外键检查在语句结束时才触发，同一条 DELETE 内父子行互删可行；
     * H2 则逐行立即检查——子行尚在时删父行即报 violations foreign key，故不能依赖方言差异。
     * 列必须允许 NULL；置空语句复用删除谓词（站点表 site_id = ?，卫星表按引用边推导作用域）。
     */
    public static final Map<String, List<String>> CYCLE_BREAKERS = Map.of(
        "agent_task", List.of("message_id"),
        "category", List.of("parent_id"),
        "content_type", List.of("parent_type_id", "copied_from_type_id"),
        "deployment", List.of("previous_deployment_id"),
        "field_definition", List.of("parent_id"),
        "site_folder", List.of("parent_id"),
        "site_theme", List.of("parent_theme_id"));

    /** 计划结果：先执行置空语句，再按序执行删除语句。全部使用同一参数 {@code siteId}。 */
    public record SitePurgePlan(List<String> breakStatements, List<String> deleteStatements) {}

    private final boolean includeStatistics;

    public SitePurgePlanner(boolean includeStatistics) {
        this.includeStatistics = includeStatistics;
    }

    /**
     * 默认计划：全量站点作用域表 {@link SitePurgeCatalog#SITE_TABLES}。
     * 服务层调这个；测试可用 {@link #plan(Collection)} 传子集验证排序。
     */
    public SitePurgePlan plan() {
        return plan(SitePurgeCatalog.SITE_TABLES);
    }

    /**
     * @param siteScopedTables 带 {@code site_id} 列的站点作用域表（通常即
     *                         {@link SitePurgeCatalog#SITE_TABLES}）
     * @throws IllegalArgumentException 目录存在无法排序/无法确定作用域的问题
     */
    public SitePurgePlan plan(Collection<String> siteScopedTables) {
        Set<String> withSiteId = new LinkedHashSet<>(siteScopedTables);
        withSiteId.removeAll(SitePurgeCatalog.RETAINED_TABLES);
        if (!includeStatistics) {
            withSiteId.removeIf(SitePurgePlanner::isStatisticsTable);
        }
        // 作用域闭包：带 site_id 的表是根，卫星表沿引用边纳入（不动点，直到不再增长）。
        // 站点引用表（site_theme_share：无 site_id 但有 theme 边 + consumer 列）同样靠边纳入。
        Set<String> scope = new LinkedHashSet<>(withSiteId);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (SitePurgeCatalog.Edge edge : SitePurgeCatalog.EDGES) {
                if (scope.contains(edge.parent()) && !scope.contains(edge.child())) {
                    scope.add(edge.child());
                    grew = true;
                }
            }
        }
        if (!includeStatistics) {
            scope.removeIf(SitePurgePlanner::isStatisticsTable);
            withSiteId.removeIf(SitePurgePlanner::isStatisticsTable);
        }
        withSiteId = Set.copyOf(withSiteId);

        for (String t : scope) {
            require(SitePurgeCatalog.isSafeIdentifier(t), "非法表名: " + t);
        }
        for (SitePurgeCatalog.Edge edge : SitePurgeCatalog.EDGES) {
            require(SitePurgeCatalog.isSafeIdentifier(edge.child()), "非法子表名: " + edge.child());
            require(SitePurgeCatalog.isSafeIdentifier(edge.column()), "非法列名: " + edge.column());
            require(SitePurgeCatalog.isSafeIdentifier(edge.parent()), "非法父表名: " + edge.parent());
            boolean childIn = scope.contains(edge.child());
            boolean parentIn = scope.contains(edge.parent());
            if (childIn) {
                require(parentIn, "边 " + edge.child() + " -> " + edge.parent() + " 的父表不在站点作用域内");
            }
        }
        // 卫星表（无 site_id）必须能由某个父边推出作用域
        Set<String> satellitePredicated = new HashSet<>();
        for (SitePurgeCatalog.Edge edge : SitePurgeCatalog.EDGES) {
            if (scope.contains(edge.child()) && !withSiteId.contains(edge.child())) {
                satellitePredicated.add(edge.child());
            }
        }
        for (String table : scope) {
            if (!withSiteId.contains(table) && !satellitePredicated.contains(table)
                && !SitePurgeCatalog.SITE_REF_COLUMNS.containsKey(table)) {
                throw new IllegalArgumentException("卫星表 " + table + " 既没有 site_id 列，也没有引用边/引用列，"
                    + "无法生成删除语句（请在 SitePurgeCatalog.EDGES 补边）");
            }
        }

        Map<String, String> predicates = resolvePredicates(scope, withSiteId);
        List<String> order = topologicalOrder(scope);

        // 1) 解环/自引用置空（谓词与删除语句同源——卫星表无 site_id 列，须按引用边推导作用域）
        List<String> breakStatements = new ArrayList<>();
        for (Map.Entry<String, List<String>> breaker : CYCLE_BREAKERS.entrySet()) {
            String table = breaker.getKey();
            if (!scope.contains(table)) continue;
            String predicate = predicates.get(table);
            require(predicate != null, "解环表 " + table + " 无法生成作用域谓词（卫星表需有引用边）");
            for (String column : breaker.getValue()) {
                require(SitePurgeCatalog.isSafeIdentifier(column), "非法置空列名: " + column);
                breakStatements.add("UPDATE " + table + " SET " + column
                    + " = NULL WHERE " + predicate + " AND " + column + " IS NOT NULL");
            }
        }

        // 2) 按序删除
        List<String> deletes = new ArrayList<>();
        for (String table : order) {
            String pred = predicates.get(table);
            if (pred == null) {
                if (SitePurgeCatalog.SITE_REF_COLUMNS.containsKey(table)) {
                    // 无边可推作用域的站点引用表：逐列处理（见 3）
                    continue;
                }
                throw new IllegalArgumentException("无法确定删除谓词: " + table);
            }
            deletes.add("DELETE FROM " + table + " WHERE " + pred);
        }

        // 3) 指向 site(id) 的本站列（site_theme_share.consumer_site_id 等）：
        //    卫星表的边谓词覆盖不到「consumer=本站、theme 属他站」的行，必须按列补删
        for (Map.Entry<String, List<String>> ref : SitePurgeCatalog.SITE_REF_COLUMNS.entrySet()) {
            String table = ref.getKey();
            if (!scope.contains(table)) continue;
            for (String column : ref.getValue()) {
                require(SitePurgeCatalog.isSafeIdentifier(column), "非法引用列名: " + column);
                deletes.add("DELETE FROM " + table + " WHERE " + column + " = ?");
            }
        }

        return new SitePurgePlan(List.copyOf(breakStatements), List.copyOf(deletes));
    }

    /**
     * 按 EDGES 声明顺序做不动点推导：父表先有谓词，子表沿<b>第一条</b>命中的边继承作用域
     * （保证 release_theme_version 这类多父卫星表按所有权边 release_id 取谓词）。
     */
    private Map<String, String> resolvePredicates(Set<String> scope, Set<String> withSiteId) {
        Map<String, String> predicate = new HashMap<>();
        for (String table : scope) {
            if (withSiteId.contains(table)) {
                predicate.put(table, "site_id = ?");
            }
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (SitePurgeCatalog.Edge edge : SitePurgeCatalog.EDGES) {
                String child = edge.child();
                if (!scope.contains(child) || withSiteId.contains(child)) continue;
                String parentPred = predicate.get(edge.parent());
                if (parentPred == null || predicate.containsKey(child)) continue;
                predicate.put(child, edge.column() + " IN (SELECT id FROM " + edge.parent()
                    + " WHERE " + parentPred + ")");
                changed = true;
            }
        }
        return predicate;
    }

    /** 拓扑排序：所有边均为「子先于父」。环必须能被 {@link #CYCLE_BREAKERS} 解开。 */
    private List<String> topologicalOrder(Set<String> scope) {
        Map<String, List<String>> parentsOf = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        for (String table : scope) {
            indegree.put(table, 0);
        }
        Set<String> ignored = new HashSet<>();
        CYCLE_BREAKERS.forEach((table, columns) -> columns.forEach(c -> ignored.add(table + "#" + c)));
        for (SitePurgeCatalog.Edge edge : SitePurgeCatalog.EDGES) {
            if (!scope.contains(edge.child()) || !scope.contains(edge.parent())) continue;
            if (ignored.contains(edge.child() + "#" + edge.column())) continue;
            parentsOf.computeIfAbsent(edge.child(), k -> new ArrayList<>()).add(edge.parent());
            indegree.merge(edge.parent(), 1, Integer::sum);
        }
        ArrayDeque<String> ready = new ArrayDeque<>();
        for (String table : scope) {
            if (indegree.get(table) == 0) ready.add(table);
        }
        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String table = ready.poll();
            order.add(table);
            for (String parent : parentsOf.getOrDefault(table, List.of())) {
                if (indegree.merge(parent, -1, Integer::sum) == 0) ready.add(parent);
            }
        }
        if (order.size() != scope.size()) {
            Set<String> remaining = new LinkedHashSet<>(scope);
            order.forEach(remaining::remove);
            throw new IllegalArgumentException("站点作用域表存在无法解开的引用环: " + remaining
                + "（请在 SitePurgePlanner.CYCLE_BREAKERS 声明可置空的解环列）");
        }
        return order;
    }

    static boolean isStatisticsTable(String table) {
        return table.startsWith("analytics_")
            || table.equals("ai_search_event")
            || table.equals("ai_search_degrade_event");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
