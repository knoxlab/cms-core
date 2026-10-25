package com.powercms.cms.content;

import com.powercms.cms.audit.AuditService;
import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.search.AdminSearchService;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 内容物理删除（内容搜索页「已归档内容可删除」）：
 * <ol>
 *   <li>未归档内容必须拒绝删除（CONFLICT），且不产生任何 SQL；</li>
 *   <li>已归档内容级联清除关联表 + 删除双索引文档 + 写审计（审计保留，FR-AUDIT-001）；</li>
 *   <li>批量删除逐条独立成败：未归档进 failed，已归档照删。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContentDeletionServiceTest {

    @Mock ContentItemRepository itemRepo;
    @Mock JdbcTemplate jdbc;
    @Mock AuditService audit;
    @Mock AdminSearchService search;
    @Mock ObjectProvider<AdminSearchService> searchProvider;

    private ContentDeletionService service;
    private final UUID site = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ContentDeletionService(itemRepo, jdbc, audit, searchProvider);
        lenient().when(searchProvider.getIfAvailable()).thenReturn(search);
        // 默认每张关联表命中 1 行（触发 DELETE 分支）；单测不关心逐表差异
        lenient().when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
            .thenReturn(1L);
        lenient().when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    }

    private ContentItemEntity item(String status) {
        ContentItemEntity item = new ContentItemEntity();
        item.setId(UUID.randomUUID());
        item.setSiteId(site);
        item.setStatus(status);
        item.setTitle("待删内容");
        return item;
    }

    @Test
    void 未归档内容拒绝删除() {
        ContentItemEntity draft = item(ContentItemEntity.STATUS_DRAFT);
        when(itemRepo.findById(draft.getId())).thenReturn(Optional.of(draft));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.deleteItem(draft.getId()));

        assertEquals(ErrorCodes.CONFLICT, ex.getCode());
        verify(itemRepo, never()).deleteById(any());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(audit, never()).write(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void 已归档内容级联删除并保留审计() {
        ContentItemEntity archived = item(ContentItemEntity.STATUS_ARCHIVED);
        when(itemRepo.findById(archived.getId())).thenReturn(Optional.of(archived));

        Map<String, Long> impact = service.deleteItem(archived.getId());

        // 子表（版本/关系/发布快照/KG/工作流等）逐表清除，最后删 content_item 行
        verify(jdbc).update(contains("content_version"), any(Object[].class));
        verify(jdbc).update(contains("content_relation_item"), any(Object[].class));
        verify(jdbc).update(contains("release_page"), any(Object[].class));
        verify(jdbc).update(contains("kg_node"), any(Object[].class));
        verify(jdbc).update(contains("analytics_content_daily"), any(Object[].class));
        // 独立生命周期对象只解绑、不删除
        verify(jdbc).update(contains("media_asset SET content_item_id = NULL"), eq(archived.getId()));
        verify(itemRepo).deleteById(archived.getId());
        // 双索引文档同步删除（否则访客搜索仍能命中已删内容）
        verify(search).deleteAdminDocument(site, archived.getId());
        verify(search).deletePublicDocument(site, archived.getId());
        // 审计保留：删除动作本身落审计行
        verify(audit).write(any(), eq("CONTENT_DELETE"), eq("content_item"),
            eq(archived.getId().toString()), eq(site), eq("SUCCESS"), contains("title=待删内容"));
        assertTrue(impact.get("content_version") == 1L);
    }

    @Test
    void 索引删除失败不影响删除本身() {
        ContentItemEntity archived = item(ContentItemEntity.STATUS_ARCHIVED);
        when(itemRepo.findById(archived.getId())).thenReturn(Optional.of(archived));
        org.mockito.Mockito.doThrow(new IllegalStateException("index down"))
            .when(search).deleteAdminDocument(site, archived.getId());

        service.deleteItem(archived.getId());

        verify(itemRepo).deleteById(archived.getId());
    }

    @Test
    void 批量删除逐条独立成败() {
        ContentItemEntity archived = item(ContentItemEntity.STATUS_ARCHIVED);
        ContentItemEntity draft = item(ContentItemEntity.STATUS_DRAFT);
        when(itemRepo.findById(archived.getId())).thenReturn(Optional.of(archived));
        when(itemRepo.findById(draft.getId())).thenReturn(Optional.of(draft));

        ContentService.BatchResult result =
            service.deleteBatch(List.of(archived.getId(), draft.getId()));

        assertEquals(List.of(archived.getId().toString()), result.succeeded());
        assertEquals(1, result.failed().size());
        assertEquals(draft.getId().toString(), result.failed().get(0).id());
        verify(itemRepo).deleteById(archived.getId());
        verify(itemRepo, never()).deleteById(draft.getId());
    }

    @Test
    void 批量删除空列表直接拒绝() {
        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.deleteBatch(List.of()));
        assertEquals(ErrorCodes.VALIDATION_FAILED, ex.getCode());
    }
}
