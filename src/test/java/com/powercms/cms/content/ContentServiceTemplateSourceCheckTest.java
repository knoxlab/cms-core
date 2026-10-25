package com.powercms.cms.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.audit.AuditService;
import com.powercms.cms.content.compose.ClassComposeService;
import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.repo.CategoryRepository;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.content.repo.ContentVersionRepository;
import com.powercms.cms.delivery.PageRenderService;
import com.powercms.cms.identity.ActorNameResolver;
import com.powercms.cms.identity.permission.ObjectPermissionService;
import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.cms.model.repo.FieldDefinitionRepository;
import com.powercms.cms.outbox.OutboxService;
import com.powercms.cms.sitefolder.repo.SiteFolderRepository;
import com.powercms.cms.sitepackage.PageCompositionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回归缺陷 ①护栏：PAGE 发布检查清单必须校验「渲染源真的可解析」。
 *
 * <p>修复前：displayTemplate / templatePath 只校验 .ftl 形态，手填不存在的模板也能发布成功，
 * 交付侧却 404「页面模板不存在」（发布假成功）。修复后：发布检查与 PageRenderService.render
 * 同源判定——.ftl 命中代码模板工程，或页面绑定可视化模板自足（计划 A），两者皆无则
 * ERROR 拦截发布。</p>
 */
@ExtendWith(MockitoExtension.class)
class ContentServiceTemplateSourceCheckTest {

    @Mock ContentItemRepository itemRepo;
    @Mock ContentVersionRepository versionRepo;
    @Mock ContentTypeRepository ctRepo;
    @Mock FieldDefinitionRepository fieldRepo;
    @Mock ClassComposeService classCompose;
    @Mock AuditService audit;
    @Mock AiDirectPublishGate gate;
    @Mock OutboxService outbox;
    @Mock PageCompositionService compositionService;
    @Mock PageRenderService pageRenderService;

    private ContentService service;
    private final UUID site = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();
    private final UUID typeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ContentService(itemRepo, mock(CategoryRepository.class), versionRepo, ctRepo,
            fieldRepo, mock(SiteFolderRepository.class), classCompose, compositionService,
            new ObjectMapper(), outbox, mock(ApplicationEventPublisher.class),
            mock(ObjectPermissionService.class), audit, gate,
            new ActorNameResolver(mock(com.powercms.cms.identity.repo.UserRepository.class)),
            pageRenderService);
        lenient().when(ctRepo.findById(typeId)).thenReturn(Optional.of(pageType()));
        lenient().when(compositionService.get(any(), any())).thenReturn(
            new PageCompositionService.CompositionInfo(null, itemId, null, Map.of(), 0, null, null));
    }

    private ContentTypeEntity pageType() {
        ContentTypeEntity type = new ContentTypeEntity();
        type.setId(typeId);
        type.setSiteId(site);
        type.setCode("news_article");
        type.setName("新闻文章");
        type.setObjectType("PAGE");
        type.setDisplayTemplate("templates/news_article.ftl");
        return type;
    }

    private ContentItemEntity pageItem(String templatePath) {
        ContentItemEntity item = new ContentItemEntity();
        item.setId(itemId);
        item.setSiteId(site);
        item.setContentTypeId(typeId);
        item.setTitle("新一代 PowerLink 正式发布");
        item.setRoutePath("/news/powerlink-v12");
        item.setTemplatePath(templatePath);
        item.setStatus(ContentItemEntity.STATUS_DRAFT);
        item.setCurrentVersion(1);
        return item;
    }

    @Test
    void checklistFailsWhenTemplateSourceUnresolvable() {
        when(itemRepo.findById(itemId)).thenReturn(Optional.of(pageItem("/templates/missing.ftl")));
        when(pageRenderService.hasRenderableSource(site, itemId, "/templates/missing.ftl")).thenReturn(false);

        ContentService.PublishChecklist checklist = service.publishChecklist(itemId);

        assertThat(checklist.pass()).isFalse();
        assertThat(checklist.items())
            .anySatisfy(item -> {
                assertThat(item.level()).isEqualTo("ERROR");
                assertThat(item.code()).isEqualTo("templateSource");
                assertThat(item.message()).contains("页面模板不存在").contains("/templates/missing.ftl");
            });
    }

    @Test
    void checklistPassesWhenTemplateResolves() {
        when(itemRepo.findById(itemId)).thenReturn(Optional.of(pageItem("/templates/news_article.ftl")));
        when(pageRenderService.hasRenderableSource(site, itemId, "/templates/news_article.ftl")).thenReturn(true);

        ContentService.PublishChecklist checklist = service.publishChecklist(itemId);

        assertThat(checklist.pass()).isTrue();
        assertThat(checklist.items())
            .anySatisfy(item -> {
                assertThat(item.level()).isEqualTo("PASS");
                assertThat(item.code()).isEqualTo("templateSource");
            });
    }

    /** item.templatePath 为空时回退 type.displayTemplate 同样受检（缺陷①的原始形态）。 */
    @Test
    void displayTemplateFallbackIsAlsoChecked() {
        when(itemRepo.findById(itemId)).thenReturn(Optional.of(pageItem(null)));
        when(pageRenderService.hasRenderableSource(site, itemId, "templates/news_article.ftl")).thenReturn(false);

        ContentService.PublishChecklist checklist = service.publishChecklist(itemId);

        assertThat(checklist.pass()).isFalse();
        verify(pageRenderService).hasRenderableSource(site, itemId, "templates/news_article.ftl");
        assertThat(checklist.items())
            .anySatisfy(item -> assertThat(item.message()).contains("templates/news_article.ftl"));
    }
}
