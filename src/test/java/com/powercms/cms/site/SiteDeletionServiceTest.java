package com.powercms.cms.site;

import com.powercms.cms.site.entity.SiteEntity;
import com.powercms.cms.site.repo.SiteRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FR-SITE-LC-007 站点删除的**真实数据库**集成测试：
 *
 * <ul>
 *   <li>仅已归档站点可删（未归档 → CONFLICT）；</li>
 *   <li>确认后级联物理删除站点全部站点级数据（内容类型/字段/栏目/内容/版本…，
 *       含无 site_id 的卫星表按引用清除）；</li>
 *   <li>审计保留：删除前的 audit_event 行不随站点消失，删除动作本身追加审计；</li>
 *   <li>外部引用（用户主站点、跨站主题共享）先提示、后阻断（409 SITE_DELETE_BLOCKED），
 *       阻断时站点数据分毫未动。</li>
 * </ul>
 *
 * <p>用 {@code @SpringBootTest} + Flyway 全量迁移的 H2 内存库：目录或计划器写错时
 * 这里会撞真实外键/缺列，而不是在生产删到一半才炸。</p>
 */
@SpringBootTest
class SiteDeletionServiceTest {

    @Autowired
    private SiteDeletionService service;
    @Autowired
    private SiteRepository siteRepo;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<UUID> createdSites = new ArrayList<>();
    private UUID orgId;

    // ---------------- fixture ----------------

    /** 建一个组织（site.organization_id 非空外键）+ 一个指定状态的站点。 */
    private UUID newSite(String status) {
        if (orgId == null) {
            orgId = UUID.randomUUID();
            jdbc.update("insert into organization (id, name, created_at, updated_at) "
                    + "values (?, ?, current_timestamp, current_timestamp)",
                orgId, "site-deletion-test");
        }
        SiteEntity site = new SiteEntity();
        site.setOrganizationId(orgId);
        site.setSiteKey("del-test-" + UUID.randomUUID());
        site.setName("站点删除测试");
        site.setStatus(status);
        site = siteRepo.save(site);
        createdSites.add(site.getId());
        return site.getId();
    }

    private int count(String table, UUID siteId) {
        return jdbc.queryForObject(
            "select count(*) from " + table + " where site_id = ?", Integer.class, siteId);
    }

    private int countById(String table, UUID id) {
        return jdbc.queryForObject(
            "select count(*) from " + table + " where id = ?", Integer.class, id);
    }

    /** 自建数据逐表清理（共享内存库，测试必须自扫门前雪）。 */
    @AfterEach
    void cleanUp() {
        for (UUID id : createdSites) {
            jdbc.update("delete from sys_user where home_site_id = ?", id);
            jdbc.update("delete from site_theme_share where consumer_site_id = ?", id);
            jdbc.update("delete from site_theme_share where theme_id in "
                + "(select id from site_theme where site_id = ?)", id);
            jdbc.update("delete from site_theme where site_id = ?", id);
            jdbc.update("delete from content_version where content_item_id in "
                + "(select id from content_item where site_id = ?)", id);
            jdbc.update("delete from field_definition where content_type_id in "
                + "(select id from content_type where site_id = ?)", id);
            jdbc.update("delete from content_item where site_id = ?", id);
            jdbc.update("delete from category where site_id = ?", id);
            jdbc.update("delete from content_type where site_id = ?", id);
            jdbc.update("delete from audit_event where site_id = ? or object_id = ?",
                id, id.toString());
            siteRepo.findById(id).ifPresent(siteRepo::delete);
        }
        createdSites.clear();
        if (orgId != null) {
            jdbc.update("delete from organization where id = ?", orgId);
            orgId = null;
        }
    }

    // ---------------- 仅归档可删 ----------------

    @Test
    @DisplayName("未归档站点不能删除（CONFLICT + 稳定消息键），站点保持原样")
    void nonArchivedSiteCannotBeDeleted() {
        UUID siteId = newSite(SiteEntity.STATUS_ACTIVE);
        assertThatThrownBy(() -> service.delete(siteId))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("code", ErrorCodes.CONFLICT)
            .hasFieldOrPropertyWithValue("messageKey", "error.site.delete.not.archived");
        assertThat(siteRepo.findById(siteId)).isPresent();
    }

    @Test
    @DisplayName("预览对未归档站点标记 deletable=false（前端不允许出现确认按钮）")
    void previewMarksNonArchivedNotDeletable() {
        UUID siteId = newSite(SiteEntity.STATUS_FROZEN);
        SiteDeletionService.DeletionPreview preview = service.preview(siteId);
        assertThat(preview.status()).isEqualTo(SiteEntity.STATUS_FROZEN);
        assertThat(preview.deletable()).isFalse();
        assertThat(preview.blockers()).isEmpty(); // 没有阻断项，不可删只因状态
    }

    // ---------------- 确认后级联删除 ----------------

    @Test
    @DisplayName("确认后级联删除站点全部内容（含内容类型/字段/栏目/内容/版本），审计保留")
    void purgesAllSiteScopedContentAndKeepsAudit() {
        UUID siteId = newSite(SiteEntity.STATUS_ARCHIVED);
        UUID typeId = UUID.randomUUID();
        UUID catId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID verId = UUID.randomUUID();
        UUID fieldId = UUID.randomUUID();
        jdbc.update("insert into content_type (id, site_id, code, name) values (?,?,?,?)",
            typeId, siteId, "article", "文章");
        jdbc.update("insert into category (id, site_id, name) values (?,?,?)",
            catId, siteId, "新闻");
        jdbc.update("insert into content_item (id, site_id, category_id, content_type_id, title) "
                + "values (?,?,?,?,?)",
            itemId, siteId, catId, typeId, "第一条内容");
        // 卫星表：content_version 没有 site_id 列，必须按引用（content_item）级联清除
        jdbc.update("insert into content_version "
                + "(id, content_item_id, version, field_values, model_version, created_by) "
                + "values (?,?,?,?,?,?)",
            verId, itemId, 1, "{}", 1, "tester");
        jdbc.update("insert into field_definition "
                + "(id, content_type_id, code, name, field_type) values (?,?,?,?,?)",
            fieldId, typeId, "title", "标题", "STRING");
        // 删除前就存在的审计行：站点删除后必须仍然可查（FR-AUDIT-001）
        UUID auditId = UUID.randomUUID();
        jdbc.update("insert into audit_event "
                + "(id, occurred_at, actor, action, object_type, object_id, site_id, result) "
                + "values (?, current_timestamp, ?, ?, ?, ?, ?, ?)",
            auditId, "tester", "CONTENT_CREATE", "content_item",
            itemId.toString(), siteId, "SUCCESS");

        SiteDeletionService.DeletionPreview preview = service.preview(siteId);
        assertThat(preview.status()).isEqualTo(SiteEntity.STATUS_ARCHIVED);
        assertThat(preview.blockers()).isEmpty();
        assertThat(preview.deletable()).isTrue();
        assertThat(preview.impact())
            .containsEntry("content_type", 1L)
            .containsEntry("category", 1L)
            .containsEntry("content_item", 1L);

        SiteDeletionService.DeletionResult result = service.delete(siteId);
        assertThat(result.siteId()).isEqualTo(siteId.toString());
        assertThat(result.impact()).containsEntry("content_type", 1L);

        // 站点行与全部站点级数据被物理清除
        assertThat(siteRepo.findById(siteId)).isEmpty();
        assertThat(count("content_type", siteId)).isZero();
        assertThat(count("category", siteId)).isZero();
        assertThat(count("content_item", siteId)).isZero();
        assertThat(countById("content_version", verId)).isZero();   // 卫星表
        assertThat(countById("field_definition", fieldId)).isZero(); // 按 content_type 关联清除

        // 审计保留：删除前的行仍在 + 新增一条 SITE_DELETE 动作审计
        assertThat(countById("audit_event", auditId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "select count(*) from audit_event where action = 'SITE_DELETE' and object_id = ?",
            Integer.class, siteId.toString())).isEqualTo(1);
    }

    // ---------------- 被引用：先提示、后阻断 ----------------

    @Test
    @DisplayName("用户以本站为主站点 → 预览提示 USER_HOME_SITE，删除被阻断（站点分毫未动）")
    void usersReferencingSiteAsHomeBlockDeletion() {
        UUID siteId = newSite(SiteEntity.STATUS_ARCHIVED);
        jdbc.update("insert into sys_user "
                + "(id, organization_id, username, password_hash, display_name, home_site_id) "
                + "values (?,?,?,?,?,?)",
            UUID.randomUUID(), orgId, "home-" + UUID.randomUUID().toString().substring(0, 8),
            "{noop}x", "主站点用户", siteId);

        SiteDeletionService.DeletionPreview preview = service.preview(siteId);
        assertThat(preview.blockers())
            .extracting(SiteDeletionService.Blocker::code)
            .contains("USER_HOME_SITE");
        assertThat(preview.deletable()).isFalse();

        assertThatThrownBy(() -> service.delete(siteId))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("code", ErrorCodes.SITE_DELETE_BLOCKED)
            .hasFieldOrPropertyWithValue("messageKey", "error.site.delete.blocked");

        // 阻断 = 零删除：站点仍在（绝不允许部分删除）
        assertThat(siteRepo.findById(siteId)).isPresent();
    }

    @Test
    @DisplayName("本站主题被其他站点共享消费 → 预览提示 THEME_SHARED，双方站点都不受影响")
    void crossSiteThemeShareBlocksDeletion() {
        UUID provider = newSite(SiteEntity.STATUS_ARCHIVED);
        UUID consumer = newSite(SiteEntity.STATUS_ACTIVE);
        UUID themeId = UUID.randomUUID();
        jdbc.update("insert into site_theme (id, site_id, name, config) values (?,?,?,?)",
            themeId, provider, "共享主题", "{}");
        jdbc.update("insert into site_theme_share "
                + "(id, theme_id, consumer_site_id, pinned_version, status) "
                + "values (?,?,?,?,?)",
            UUID.randomUUID(), themeId, consumer, 1, "ACTIVE");

        SiteDeletionService.DeletionPreview preview = service.preview(provider);
        assertThat(preview.blockers())
            .extracting(SiteDeletionService.Blocker::code)
            .contains("THEME_SHARED");
        assertThat(preview.deletable()).isFalse();

        assertThatThrownBy(() -> service.delete(provider))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("code", ErrorCodes.SITE_DELETE_BLOCKED);

        // 被阻断的站点与引用方站点都保持原样
        assertThat(siteRepo.findById(provider)).isPresent();
        assertThat(siteRepo.findById(consumer)).isPresent();
        assertThat(count("site_theme", provider)).isEqualTo(1);
    }
}
