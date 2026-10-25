package com.powercms.cms.publish;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.model.entity.ContentTypeEntity;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.cms.sitepackage.ComponentInstance;
import com.powercms.cms.sitepackage.entity.PageVisualCompositionEntity;
import com.powercms.cms.sitepackage.repo.PageVisualCompositionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AffectedPageResolverTest {
    @Mock ContentItemRepository items;
    @Mock ContentTypeRepository types;
    @Mock PageVisualCompositionRepository compositions;
    ObjectMapper mapper = new ObjectMapper();

    @Test void pageMapsToItselfAndReferencedPages() throws Exception {
        UUID site = UUID.randomUUID();
        UUID articleId = UUID.randomUUID();
        UUID pageId = UUID.randomUUID();
        UUID pageTypeId = UUID.randomUUID();
        UUID articleTypeId = UUID.randomUUID();

        ContentItemEntity article = item(articleId, site, articleTypeId, null);
        ContentItemEntity page = item(pageId, site, pageTypeId, "/home");
        ContentTypeEntity articleType = type(articleTypeId, "CONTENT");

        when(items.findById(articleId)).thenReturn(Optional.of(article));
        when(items.findById(pageId)).thenReturn(Optional.of(page));
        when(types.findById(articleTypeId)).thenReturn(Optional.of(articleType));

        var composition = new PageVisualCompositionEntity();
        composition.setPageId(pageId);
        composition.setZonesJson(mapper.writeValueAsString(Map.of("main", List.of(
            new ComponentInstance("c1", ComponentInstance.Kind.CONTENT, null, null, null, "t", Map.of(), null, null, Map.of(), 0, null, Map.of(), articleId, 1)
        ))));
        when(compositions.findAll()).thenReturn(List.of(composition));

        var resolved = new AffectedPageResolver(items, types, compositions, mapper).resolve(site, articleId);
        assertThat(resolved).extracting(AffectedPageResolver.AffectedPage::pageId).containsExactly(pageId);
        assertThat(resolved).extracting(AffectedPageResolver.AffectedPage::routePath).containsExactly("/home");
    }

    @Test void pageContentIncludesSelf() {
        UUID site = UUID.randomUUID(), pageId = UUID.randomUUID(), typeId = UUID.randomUUID();
        ContentItemEntity page = item(pageId, site, typeId, "/about");
        when(items.findById(pageId)).thenReturn(Optional.of(page));
        when(types.findById(typeId)).thenReturn(Optional.of(type(typeId, "PAGE")));
        when(compositions.findAll()).thenReturn(List.of());
        var resolved = new AffectedPageResolver(items, types, compositions, mapper).resolve(site, pageId);
        assertThat(resolved).extracting(AffectedPageResolver.AffectedPage::routePath).containsExactly("/about");
    }

    private static ContentItemEntity item(UUID id, UUID site, UUID typeId, String route) {
        var x = new ContentItemEntity();
        x.setId(id); x.setSiteId(site); x.setContentTypeId(typeId); x.setRoutePath(route); x.setStatus("PUBLISHED");
        return x;
    }

    private static ContentTypeEntity type(UUID id, String objectType) {
        var x = new ContentTypeEntity();
        x.setId(id); x.setObjectType(objectType);
        return x;
    }
}
