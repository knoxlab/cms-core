package com.powercms.cms.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.content.entity.ContentVersionEntity;
import com.powercms.cms.content.repo.ContentVersionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/** FR-AI-AGENT-002 / FR-AGT-FLOW-003：内容版本字段级 Diff。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContentVersionDiffServiceTest {

    @Mock ContentVersionRepository versionRepo;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID itemId = UUID.randomUUID();

    private ContentVersionDiffService service() {
        return new ContentVersionDiffService(versionRepo, objectMapper);
    }

    private ContentVersionEntity version(int version, String fields) {
        return ContentVersionEntity.create(itemId, version, fields, 1, "editor", "note");
    }

    @Test
    void detectsAddedRemovedAndModifiedFields() {
        when(versionRepo.findByContentItemIdAndVersion(itemId, 1))
            .thenReturn(Optional.of(version(1, "{\"body\":\"旧正文\",\"summary\":\"旧摘要\"}")));
        when(versionRepo.findByContentItemIdAndVersion(itemId, 2))
            .thenReturn(Optional.of(version(2, "{\"body\":\"新正文\",\"tags\":[\"a\",\"b\"]}")));

        ContentVersionDiffService.VersionDiff diff = service().diff(itemId, 1, 2);

        assertEquals(3, diff.changedFields());
        assertEquals("MODIFIED", changeType(diff, "body"));
        assertEquals("REMOVED", changeType(diff, "summary"));
        assertEquals("ADDED", changeType(diff, "tags"));
        // 并集共 3 个键（body/summary/tags），全部变更 → 未变 0
        assertEquals(0, diff.unchangedFields());
    }

    @Test
    void initialVersionIsFullyAdded() {
        when(versionRepo.findByContentItemIdAndVersion(itemId, 1))
            .thenReturn(Optional.of(version(1, "{\"body\":\"正文\"}")));

        ContentVersionDiffService.VersionDiff diff = service().diffAgainstPrevious(itemId, 1);

        assertEquals(0, diff.fromVersion());
        assertEquals(1, diff.changedFields());
        assertEquals("ADDED", changeType(diff, "body"));
    }

    @Test
    void unchangedFieldsAreNotReported() {
        when(versionRepo.findByContentItemIdAndVersion(itemId, 1))
            .thenReturn(Optional.of(version(1, "{\"body\":\"同\"}")));
        when(versionRepo.findByContentItemIdAndVersion(itemId, 2))
            .thenReturn(Optional.of(version(2, "{\"body\":\"同\"}")));

        ContentVersionDiffService.VersionDiff diff = service().diff(itemId, 1, 2);

        assertEquals(0, diff.changedFields());
        // 规范 §15.4「未变 N」：模型字段参与了比对但取值相同，须计入未变而不是消失
        assertEquals(1, diff.unchangedFields());
    }

    @Test
    void missingVersionTreatedAsEmptyWithoutFailure() {
        when(versionRepo.findByContentItemIdAndVersion(itemId, 1)).thenReturn(Optional.empty());

        ContentVersionDiffService.VersionDiff diff = service().diff(itemId, 1, 99);

        assertEquals(0, diff.changedFields());
        assertEquals(0, diff.unchangedFields());
        assertTrue(diff.changes().isEmpty());
    }

    private static String changeType(ContentVersionDiffService.VersionDiff diff, String field) {
        return diff.changes().stream()
            .filter(c -> c.field().equals(field))
            .map(ContentVersionDiffService.FieldChange::changeType)
            .findFirst().orElse(null);
    }

    // ==== FR-CV-VER-008：条目级系统字段（标题/路径/模板）也须纳入「版本对比」 ====

    private ContentVersionEntity versionWithItemSnapshot(
            int version, String fields, String title, String routePath, String templatePath) {
        return version(version, fields).withItemSnapshot(title, routePath, templatePath);
    }

    @Test
    void itemFieldDiffReportsTitleRouteAndTemplateChanges() {
        when(versionRepo.findByContentItemIdAndVersion(itemId, 1))
            .thenReturn(Optional.of(versionWithItemSnapshot(1, "{\"body\":\"同\"}", "旧标题", "/old", "/t1.ftl")));
        when(versionRepo.findByContentItemIdAndVersion(itemId, 2))
            .thenReturn(Optional.of(versionWithItemSnapshot(2, "{\"body\":\"同\"}", "新标题", "/new", "/t1.ftl")));

        ContentVersionDiffService.VersionDiff diff = service().diffWithItemFields(itemId, 1, 2);

        // 正文未变 → 不出现；标题与路径变更 → 各 1 条；模板相同 → 不出现
        assertEquals(2, diff.changedFields());
        // 未变 = 模型字段 1（body）+ 条目级 1（templatePath）
        assertEquals(2, diff.unchangedFields());
        assertEquals("MODIFIED", changeType(diff, ContentVersionDiffService.ITEM_TITLE));
        assertEquals("MODIFIED", changeType(diff, ContentVersionDiffService.ITEM_ROUTE_PATH));
        assertEquals(null, changeType(diff, ContentVersionDiffService.ITEM_TEMPLATE_PATH));
        assertEquals("旧标题", before(diff, ContentVersionDiffService.ITEM_TITLE));
        assertEquals("新标题", after(diff, ContentVersionDiffService.ITEM_TITLE));
    }

    @Test
    void itemFieldDiffTreatsMissingRouteAsAddedOrRemoved() {
        when(versionRepo.findByContentItemIdAndVersion(itemId, 1))
            .thenReturn(Optional.of(versionWithItemSnapshot(1, "{}", "标题", null, null)));
        when(versionRepo.findByContentItemIdAndVersion(itemId, 2))
            .thenReturn(Optional.of(versionWithItemSnapshot(2, "{}", "标题", "/p", "/t.ftl")));

        ContentVersionDiffService.VersionDiff diff = service().diffWithItemFields(itemId, 1, 2);

        assertEquals("ADDED", changeType(diff, ContentVersionDiffService.ITEM_ROUTE_PATH));
        assertEquals("ADDED", changeType(diff, ContentVersionDiffService.ITEM_TEMPLATE_PATH));
    }

    @Test
    void itemFieldDiffSkipsBlankVersusNull() {
        // V100 迁移前历史行的 path/template 为 null，空串与 null 视为同值，不产生噪声变更
        when(versionRepo.findByContentItemIdAndVersion(itemId, 1))
            .thenReturn(Optional.of(versionWithItemSnapshot(1, "{}", "标题", null, "")));
        when(versionRepo.findByContentItemIdAndVersion(itemId, 2))
            .thenReturn(Optional.of(versionWithItemSnapshot(2, "{}", "标题", "  ", "")));

        assertEquals(0, service().diffWithItemFields(itemId, 1, 2).changedFields());
    }

    @Test
    void itemFieldDiffKeepsModelFieldChangesAndIsolatesLegacyDiff() {
        when(versionRepo.findByContentItemIdAndVersion(itemId, 1))
            .thenReturn(Optional.of(versionWithItemSnapshot(1, "{\"body\":\"旧\"}", "旧标题", "/p", "/t.ftl")));
        when(versionRepo.findByContentItemIdAndVersion(itemId, 2))
            .thenReturn(Optional.of(versionWithItemSnapshot(2, "{\"body\":\"新\"}", "新标题", "/p", "/t.ftl")));

        // 新版（含条目级）→ 2 条；原 diff（审核摘要用）保持只看模型字段 → 1 条
        assertEquals(2, service().diffWithItemFields(itemId, 1, 2).changedFields());
        assertEquals(1, service().diff(itemId, 1, 2).changedFields());
        assertEquals(null, changeType(service().diff(itemId, 1, 2), ContentVersionDiffService.ITEM_TITLE));
    }

    private static String before(ContentVersionDiffService.VersionDiff diff, String field) {
        return diff.changes().stream()
            .filter(c -> c.field().equals(field))
            .map(ContentVersionDiffService.FieldChange::before)
            .findFirst().orElse(null);
    }

    private static String after(ContentVersionDiffService.VersionDiff diff, String field) {
        return diff.changes().stream()
            .filter(c -> c.field().equals(field))
            .map(ContentVersionDiffService.FieldChange::after)
            .findFirst().orElse(null);
    }
}
