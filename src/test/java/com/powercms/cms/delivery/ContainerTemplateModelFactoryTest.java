package com.powercms.cms.delivery;

import com.powercms.cms.delivery.controller.SiteScopedContentQueryService;
import com.powercms.cms.sitepackage.entity.SiteContainerEntity;
import freemarker.template.TemplateMethodModelEx;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ContainerTemplateModelFactoryTest {
  @Test
  void preloadsConfiguredQueryAndExposesContentQueryMethod() throws Exception {
    UUID siteId = UUID.randomUUID();
    SiteScopedContentQueryService query = mock(SiteScopedContentQueryService.class);
    when(query.queryByType(siteId, "travel_destination", "PUBLISHED", 3, "viewCount", true))
        .thenReturn(List.of(Map.of("title", "High", "viewCount", 99L)));

    SiteContainerEntity container = new SiteContainerEntity();
    container.setId(UUID.randomUUID());
    container.setName("Popular");
    container.setSiteId(siteId);

    Map<String, Object> config =
        Map.of(
            "type",
            "ZERO_CONTENT",
            "query",
            Map.of(
                "contentType", "travel_destination",
                "limit", 3,
                "orderBy", "viewCount",
                "as", "items"));

    ContainerTemplateModelFactory factory = new ContainerTemplateModelFactory(query);
    Map<String, Object> model = factory.create(siteId, UUID.randomUUID(), container, config, false);

    assertEquals(1, ((List<?>) model.get("items")).size());
    assertTrue(model.get("contentQuery") instanceof TemplateMethodModelEx);
    @SuppressWarnings("unchecked")
    List<Object> dynamic =
        (List<Object>)
            ((TemplateMethodModelEx) model.get("contentQuery"))
                .exec(List.of("travel_event", 5, "viewCount"));
    verify(query).queryByType(siteId, "travel_event", "PUBLISHED", 5, "viewCount", true);
    assertNotNull(dynamic);
  }
}
