package com.powercms.cms.site.purge;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 站点删除的**数据目录**（FR-SITE-LC-007：删除站点 = 删除该站点的全部站点级数据）。
 *
 * <p>三类事实集中在这里，便于评审与回归校验（{@code SitePurgeCatalogCoverageTest} 会拿
 * {@code src/main/resources/db/migration} 的真实表结构与实体源码逐项比对，漏一条即红）：</p>
 * <ol>
 *   <li><b>站点作用域表</b> {@link #SITE_TABLES}：带 {@code site_id} 列、按
 *       {@code WHERE site_id = ?} 整表清除的 120 张表（含只有迁移建表、没有实体的
 *       {@link #EXTRA_SITE_TABLES}）；</li>
 *   <li><b>保留表</b> {@link #RETAINED_TABLES}：审计追加写、不得随站点删除（FR-AUDIT-001）；</li>
 *   <li><b>引用边</b> {@link #EDGES}：子表 → 父表。父表带 site_id 时只决定「先删子、后删父」
 *       的顺序；子表<b>没有</b> site_id（卫星表，如 {@code content_version}）时同时生成作用域
 *       子查询。没有 site_id、却直接指向 {@code site(id)} 的列见 {@link #SITE_REF_COLUMNS}。</li>
 * </ol>
 *
 * <p><b>跨站点引用不在此列</b>：其他站点指向本站的行（用户主站点、跨站主题消费、外部发布引用
 * 主题等）是<b>阻断项</b>——先提示、不删除，由 {@code SiteDeletionService} 按同一目录生成检查 SQL。</p>
 */
public final class SitePurgeCatalog {

    private SitePurgeCatalog() {}

    /** 保留表：站点删除后仍需留存的审计行（删除动作本身也写审计，FR-AUDIT-001）。 */
    public static final Set<String> RETAINED_TABLES = Set.of("audit_event");

    /**
     * 带 {@code site_id} 列的站点作用域表（120 张，按站点整删）。
     * 数据来源：{@code db/migration} 全量扫描（create table 体与 alter add column 命中
     * {@code site_id} 列者）∩ 实体 {@code siteId} 字段核对。
     * <b>漏掉任何一条 = 删除后残留行（或 site 行被外键挡住）——由覆盖测试兜底。</b>
     */
    public static final Set<String> SITE_TABLES = Set.of(
        "agent_job",
        "agent_message",
        "agent_orchestration",
        "agent_session",
        "agent_task",
        "ai_ask_blacklist",
        "ai_ask_log",
        "ai_auto_draft_rule",
        "ai_auto_draft_run",
        "ai_batch_job",
        "ai_content_suggestion",
        "ai_geo_refresh_job",
        "ai_geo_refresh_schedule",
        "ai_model_config",
        "ai_model_credential",
        "ai_ops_report",
        "ai_ops_report_definition",
        "ai_ops_report_delivery",
        "ai_ops_report_subscription",
        "ai_search_degrade_event",
        "ai_search_event",
        "ai_seo_config",
        "ai_site_policy",
        "ai_source_route_rule",
        "ai_usage_record",
        "analytics_asset_daily",
        "analytics_client_daily",
        "analytics_content_daily",
        "analytics_content_type_daily",
        "analytics_custom_daily",
        "analytics_custom_event_def",
        "analytics_depth_daily",
        "analytics_entry_daily",
        "analytics_event",
        "analytics_exit_daily",
        "analytics_folder_daily",
        "analytics_frequency_daily",
        "analytics_hourly",
        "analytics_ip_daily",
        "analytics_online_minute",
        "analytics_play_daily",
        "analytics_play_session",
        "analytics_search_daily",
        "analytics_session",
        "analytics_site_daily",
        "analytics_source_daily",
        "analytics_visitor_daily",
        "api_token",
        "category",
        "content_citation_count",
        "content_item",
        "content_relation",
        "content_source_fact",
        "content_type",
        "datasource_registration",
        "datasource_sync_log",
        "deploy_target",
        "deployment",
        "doc_chunk",
        "doc_embedding_cache",
        "doc_embedding_cache_stat",
        "doc_index_state",
        "doc_parse_job",
        "doc_site_policy",
        "doc_unit",
        "embedding_dead_letter",
        "embedding_rebuild_progress",
        "form_definition",
        "form_submission_audit",
        "interaction_ad_item",
        "interaction_ad_slot",
        "interaction_chat_archive",
        "interaction_chat_room",
        "interaction_message",
        "interaction_message_like",
        "interaction_report",
        "interaction_review_flow",
        "interaction_review_task",
        "interaction_sensitive_word",
        "interaction_site_settings",
        "interaction_submission",
        "interaction_submission_guard",
        "kg_edge",
        "kg_node",
        "knox_chunk_session",
        "knox_site_binding",
        "mcp_idempotency",
        "media_asset",
        "media_folder",
        "member_library_entry",
        "member_progress",
        "notification_template",
        "oauth_token",
        "object_acl",
        "program_access_policy",
        "relation_template",
        "release",
        "release_incident",
        "release_monitor_policy",
        "search_index_mapping_state",
        "shared_component",
        "site_authorization_policy",
        "site_container",
        "site_controller",
        "site_environment",
        "site_folder",
        "site_index_status",
        "site_navigation_config",
        "site_template",
        "site_theme",
        "sys_user_role",
        "template_project",
        "url_rewrite_rule",
        "webhook_subscription",
        "widget_definition",
        "workflow_definition",
        "workflow_task",
        "workflow_transition_log");

    /**
     * {@link #SITE_TABLES} 中<b>没有对应实体</b>的表（只有迁移建表，实体层看不见，
     * 靠人工目录兜底）。有实体的表与实体一一对应，由覆盖测试双向核对。
     */
    public static final Set<String> EXTRA_SITE_TABLES = Set.of(
        "ai_search_degrade_event",
        "ai_search_event",
        "analytics_asset_daily",
        "analytics_client_daily",
        "analytics_content_daily",
        "analytics_content_type_daily",
        "analytics_custom_daily",
        "analytics_custom_event_def",
        "analytics_depth_daily",
        "analytics_entry_daily",
        "analytics_exit_daily",
        "analytics_folder_daily",
        "analytics_frequency_daily",
        "analytics_hourly",
        "analytics_ip_daily",
        "analytics_online_minute",
        "analytics_play_daily",
        "analytics_search_daily",
        "analytics_session",
        "analytics_site_daily",
        "analytics_source_daily",
        "analytics_visitor_daily",
        "content_citation_count",
        "doc_site_policy",
        "agent_job",
        "agent_message",
        "agent_orchestration",
        "agent_session",
        "agent_task",
        "ai_ask_blacklist",
        "ai_ask_log",
        "ai_auto_draft_rule",
        "ai_auto_draft_run",
        "ai_batch_job",
        "ai_content_suggestion",
        "ai_geo_refresh_job",
        "ai_geo_refresh_schedule",
        "ai_model_config",
        "ai_model_credential",
        "ai_ops_report",
        "ai_ops_report_definition",
        "ai_ops_report_delivery",
        "ai_ops_report_subscription",
        "ai_seo_config",
        "ai_site_policy",
        "ai_source_route_rule",
        "ai_usage_record",
        "datasource_registration",
        "datasource_sync_log",
        "doc_chunk",
        "doc_embedding_cache",
        "doc_embedding_cache_stat",
        "doc_index_state",
        "doc_parse_job",
        "doc_unit",
        "embedding_dead_letter",
        "embedding_rebuild_progress",
        "form_definition",
        "form_submission_audit",
        "interaction_ad_item",
        "interaction_ad_slot",
        "interaction_chat_archive",
        "interaction_chat_room",
        "interaction_message",
        "interaction_message_like",
        "interaction_report",
        "interaction_review_flow",
        "interaction_review_task",
        "interaction_sensitive_word",
        "interaction_site_settings",
        "interaction_submission",
        "interaction_submission_guard",
        "kg_edge",
        "kg_node",
        "knox_chunk_session",
        "knox_site_binding",
        "mcp_idempotency",
        "site_navigation_config");

    /**
     * 表本身没有 site_id 列、却存在指向 {@code site(id)} 的列，且该列<b>属于本站数据</b>
     * （消费记录属于消费者站点）。删除站点前必须先删这些行，否则 site 行被外键挡住。
     *
     * <p>注意区分：{@code sys_user.home_site_id}、{@code release_theme_version.owner_site_id}
     * 这类「别人指向本站」的列是<b>阻断项</b>，不在此列——见 {@code SiteDeletionService}。</p>
     */
    public static final Map<String, List<String>> SITE_REF_COLUMNS = Map.of(
        "site_theme_share", List.of("consumer_site_id"));

    /** 子表 → 父表引用边（子先删）。 */
    public record Edge(String child, String column, String parent) {}

    private static Edge e(String child, String column, String parent) {
        return new Edge(child, column, parent);
    }

    /**
     * 全部引用边：迁移声明外键与实体层逻辑引用的并集。
     *
     * <p>卫星表（无 site_id）按<b>第一条</b>命中该表的边确定作用域——所有权边
     * （如 {@code release_theme_version.release_id}）必须放最前。</p>
     *
     * <p>自引用边（field_definition.parent_id、site_theme.parent_theme_id、category.parent_id
     * 等同表列）不列：子表作用域已由其它父边覆盖，且 DELETE 的子查询不能引用目标表自身。</p>
     */
    public static final List<Edge> EDGES = List.of(
        // --- Agent 工作台（agent_task.message_id ↔ agent_task.task_id 互引，先置空解环）---
        e("agent_message", "session_id", "agent_session"),
        e("agent_message", "task_id", "agent_task"),
        e("agent_task", "message_id", "agent_message"),
        e("agent_task", "session_id", "agent_session"),
        e("agent_job_item", "job_id", "agent_job"),

        // --- 内容模型与内容 ---
        e("content_item", "category_id", "category"),
        e("content_item", "content_type_id", "content_type"),
        e("content_item", "folder_id", "site_folder"),
        e("content_relation", "source_type_id", "content_type"),
        e("content_relation", "target_type_id", "content_type"),
        e("content_source_fact", "content_item_id", "content_item"),
        e("content_version", "content_item_id", "content_item"),
        e("content_relation_item", "relation_id", "content_relation"),
        e("content_relation_item", "source_content_id", "content_item"),
        e("content_relation_item", "target_content_id", "content_item"),
        e("content_type_change_log", "content_type_id", "content_type"),
        e("content_type_lifecycle", "content_type_id", "content_type"),
        e("field_definition", "content_type_id", "content_type"),
        e("category_content_type", "category_id", "category"),
        e("category_content_type", "content_type_id", "content_type"),
        e("relation_template_link_log", "template_id", "relation_template"),
        e("relation_template_link_log", "datasource_id", "datasource_registration"),
        e("relation_template_link_log", "record_content_id", "content_item"),
        e("relation_template_link_log", "anchor_content_id", "content_item"),
        e("relation_template_link_log", "relation_id", "content_relation"),

        // --- 表单、控件、页面与视觉布局 ---
        e("form_definition", "content_type_id", "content_type"),
        e("form_definition_revision", "form_id", "form_definition"),
        e("form_submission_audit", "form_id", "form_definition"),
        e("form_submission_audit", "content_item_id", "content_item"),
        e("form_layout", "content_type_id", "content_type"),
        e("form_layout", "element_id", "field_definition"),
        e("shared_component", "content_type_id", "content_type"),
        e("shared_component_revision", "component_id", "shared_component"),
        e("site_controller_version", "controller_id", "site_controller"),
        e("site_container_revision", "container_id", "site_container"),
        e("site_container_structure", "container_id", "site_container"),
        e("site_container_structure", "structure_id", "content_type"),
        e("site_drop_zone", "template_id", "site_template"),
        e("site_drop_zone", "container_id", "site_container"),
        e("site_template", "theme_id", "site_theme"),
        e("site_template_acl", "template_id", "site_template"),
        e("site_template_revision", "template_id", "site_template"),
        e("site_theme_revision", "theme_id", "site_theme"),
        e("site_theme_revision", "parent_theme_id", "site_theme"),
        e("site_theme_revision_file", "theme_revision_id", "site_theme_revision"),
        e("site_theme_file", "theme_id", "site_theme"),
        e("site_theme_share", "theme_id", "site_theme"),
        e("page_visual_composition", "page_id", "content_item"),
        e("page_visual_composition", "template_id", "site_template"),

        // --- 媒体（knox_media_ref 无外键，按资产属主清理）---
        e("media_version", "media_asset_id", "media_asset"),
        e("knox_media_ref", "media_asset_id", "media_asset"),
        e("release_media_version", "media_asset_id", "media_asset"),
        e("analytics_asset_daily", "media_asset_id", "media_asset"),

        // --- 发布、构建、部署 ---
        e("release", "template_project_id", "template_project"),
        e("release_content", "release_id", "release"),
        e("release_content", "content_item_id", "content_item"),
        e("release_page", "release_id", "release"),
        e("release_page", "page_id", "content_item"),
        e("release_media_version", "release_id", "release"),
        e("release_url_rewrite", "release_id", "release"),
        e("release_url_rewrite", "rule_id", "url_rewrite_rule"),
        e("release_theme_version", "release_id", "release"),
        e("release_theme_version", "theme_id", "site_theme"),
        e("release_theme_version", "theme_revision_id", "site_theme_revision"),
        e("build_job", "release_id", "release"),
        e("build_job", "template_project_id", "template_project"),
        e("build_log", "build_job_id", "build_job"),
        e("build_artifact", "build_job_id", "build_job"),
        e("build_artifact", "release_id", "release"),
        e("deployment", "release_id", "release"),
        e("deployment", "deploy_target_id", "deploy_target"),
        e("deployment", "artifact_id", "build_artifact"),
        e("template_file", "template_project_id", "template_project"),
        e("template_revision", "template_project_id", "template_project"),

        // --- 工作流、通知、Webhook ---
        e("workflow_task", "workflow_def_id", "workflow_definition"),
        e("workflow_task", "content_item_id", "content_item"),
        e("workflow_task_confirmation", "task_id", "workflow_task"),
        e("workflow_transition", "workflow_definition_id", "workflow_definition"),
        e("webhook_delivery", "subscription_id", "webhook_subscription"),

        // --- 互动（外键未声明，按实体字段建立边）---
        e("interaction_ad_stat", "ad_item_id", "interaction_ad_item"),
        e("interaction_chat_member", "room_id", "interaction_chat_room"),
        e("interaction_review_confirmation", "task_id", "interaction_review_task"),

        // --- 数据源、知识库、AI 批处理 ---
        e("datasource_field_mapping", "datasource_id", "datasource_registration"),
        e("doc_source_block", "doc_unit_id", "doc_unit"),
        e("doc_chunk", "doc_unit_id", "doc_unit"),
        e("doc_chunk_revision", "doc_chunk_id", "doc_chunk"),
        e("doc_chunk_revision", "doc_unit_id", "doc_unit"),
        e("doc_table_structure", "doc_unit_id", "doc_unit"),
        e("ai_content_suggestion", "content_item_id", "content_item"),
        e("ai_content_suggestion", "content_type_id", "content_type"),
        e("ai_batch_job_item", "content_item_id", "content_item"),
        e("ai_batch_job_item", "suggestion_id", "ai_content_suggestion"),
        // 必须放最后：作用域已由 content_item 边确定，这条只补「先删 item、后删 job」的顺序
        e("ai_batch_job_item", "job_id", "ai_batch_job"));

    /** 确认弹窗展示的影响面（只列用户看得懂的对象族；全部在 {@link #SITE_TABLES} 内按 site_id 计数）。 */
    public static final List<String> IMPACT_TABLES = List.of(
        "content_type",
        "content_item",
        "category",
        "media_asset",
        "site_folder",
        "site_controller",
        "site_template",
        "site_theme",
        "site_container",
        "template_project",
        "release",
        "deployment",
        "form_definition",
        "interaction_submission",
        "doc_unit",
        "analytics_event");

    /** 表名/列名白名单：进入 SQL 拼接前必须通过（防目录被误改后拼出非法/危险语句）。 */
    public static boolean isSafeIdentifier(String name) {
        if (name == null || name.isEmpty() || name.length() > 64) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
            if (!ok) return false;
        }
        return name.charAt(0) >= 'a' && name.charAt(0) <= 'z';
    }
}
