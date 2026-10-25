package com.powercms.cms.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.repo.ContentTypeRepository;
import graphql.ExecutionInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DynamicSchemaRegistryTest {

    @Mock ContentTypeRepository contentTypeRepo;

    private DynamicSchemaRegistry registry;
    private UUID siteId;

    @BeforeEach
    void setUp() {
        registry = new DynamicSchemaRegistry(contentTypeRepo);
        siteId = UUID.randomUUID();
    }

    private static ContentTypeEntity publishedType(String code) {
        ContentTypeEntity ct = new ContentTypeEntity();
        ct.setSiteId(UUID.randomUUID());
        ct.setCode(code);
        ct.setStatus(ContentTypeEntity.STATUS_PUBLISHED);
        return ct;
    }

    @Test
    void buildsSdlFromPublishedModelsAndParsesAtomically() {
        when(contentTypeRepo.findBySiteIdOrderByCodeAsc(siteId))
            .thenReturn(List.of(publishedType("news_article"), publishedType("banner")));

        var version = registry.rebuildForSite(siteId);

        assertThat(version.sdl()).contains("type NewsArticle {");
        assertThat(version.sdl()).contains("newsArticle(siteId: ID!, id: ID!): NewsArticle");
        assertThat(version.sdl()).contains("newsArticleList(");
        assertThat(version.sdl()).contains("type Banner {");
        assertThat(version.fingerprint()).hasSize(64);

        // 二次构建指纹变化（时间戳无关，SDL 相同则指纹相同）
        var again = registry.rebuildForSite(siteId);
        assertThat(again.fingerprint()).isEqualTo(version.fingerprint());
    }

    @Test
    void draftModelsAreExcluded() {
        ContentTypeEntity draft = publishedType("secret_draft");
        draft.setStatus(ContentTypeEntity.STATUS_DRAFT);
        when(contentTypeRepo.findBySiteIdOrderByCodeAsc(siteId)).thenReturn(List.of(draft));

        var version = registry.rebuildForSite(siteId);

        assertThat(version.sdl()).doesNotContain("SecretDraft");
        assertThat(version.sdl()).contains("type Query"); // 基础查询仍存在
    }

    @Test
    void invalidSiteKeepsExistingSchemaUntouchedOnFailure() {
        when(contentTypeRepo.findBySiteIdOrderByCodeAsc(siteId)).thenReturn(List.of(publishedType("ok_type")));
        registry.rebuildForSite(siteId);
        var before = registry.get(siteId);

        // 仓库抛异常时，现有 schema 不受影响
        when(contentTypeRepo.findBySiteIdOrderByCodeAsc(siteId)).thenThrow(new IllegalStateException("db down"));
        assertThatThrownBy(() -> registry.rebuildForSite(siteId)).isInstanceOf(IllegalStateException.class);
        assertThat(registry.get(siteId)).isSameAs(before);
    }

    @Test
    void sdlComposerSanitizesCodesToGraphqlIdentifiers() {
        assertThat(SdlComposer.pascal("news-article")).isEqualTo("NewsArticle");
        assertThat(SdlComposer.camel("NEWS_ARTICLE")).isEqualTo("newsArticle");
        assertThat(SdlComposer.pascal("2fast")).isEqualTo("Type2fast");
        assertThat(SdlComposer.compose(List.of(publishedType("a")))).startsWith("type Query {");
    }

    @Test
    void executorRegistryBuildsExecutableEnginePerFingerprint() {
        when(contentTypeRepo.findBySiteIdOrderByCodeAsc(siteId)).thenReturn(List.of(publishedType("article")));
        registry.rebuildForSite(siteId);
        DeliveryQueryResolver resolver = new DeliveryQueryResolver(null);
        SiteGraphQlExecutorRegistry executors = new SiteGraphQlExecutorRegistry(registry, resolver);

        graphql.GraphQL engine = executors.engineFor(siteId);
        assertThat(engine).isNotNull();

        Map<String, Object> result = engine.execute(
            ExecutionInput.newExecutionInput().query("{ __typename }").build())
            .toSpecification();
        assertThat(result).containsKey("data");
    }
}
