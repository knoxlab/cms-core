package com.powercms.cms.delivery.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.audit.AuditService;
import com.powercms.cms.delivery.FreeMarkerSandbox;
import com.powercms.cms.delivery.RenderDatasetCache;
import com.powercms.cms.sitepackage.TemplateService;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ControllerScriptServiceTest {
 private SiteControllerRepository repo; private ControllerScriptService service; private UUID site;
 @BeforeEach void setUp(){repo=mock(SiteControllerRepository.class);site=UUID.randomUUID();service=new ControllerScriptService(repo,mock(SiteControllerVersionRepository.class),new ObjectMapper(),mock(SiteScopedContentQueryService.class),mock(TemplateService.class),mock(FreeMarkerSandbox.class),new RenderDatasetCache(8),mock(AuditService.class),100,2048);}
 @Test void acceptsSafeViewDsl(){var v=service.validate(site,"/scripts/controllers/articles.controller.json","{\"route\":\"/articles\",\"method\":\"GET\",\"view\":\"/templates/articles.ftl\",\"queries\":[{\"as\":\"items\",\"op\":\"list\",\"limit\":10}]}",null);assertTrue(v.valid(),()->v.errors().toString());}
 @Test
 void rejectsDangerousAndTraversalFields(){
   var v=assertDoesNotThrow(()->service.validate(site,"/scripts/controllers/../evil.controller.json","{\"route\":\"/api/admin\",\"method\":\"DELETE\",\"view\":\"/templates/../evil.ftl\",\"groovy\":\"Runtime.exec('x')\"}",null));
   assertFalse(v.valid());
   assertTrue(v.errors().stream().anyMatch(x->x.contains("Forbidden field: groovy")),v.errors().toString());
   assertTrue(v.errors().stream().anyMatch(x->x.contains("GET or POST")),v.errors().toString());
   assertTrue(v.errors().stream().anyMatch(x->x.contains("/scripts/controllers/")),v.errors().toString());
   assertTrue(v.errors().stream().anyMatch(x->x.toLowerCase(Locale.ROOT).contains("route")),v.errors().toString());
   assertTrue(v.errors().stream().anyMatch(x->x.contains("/templates/...ftl")),v.errors().toString());
 }
 @Test void rejectsRouteConflict(){when(repo.existsBySiteIdAndRoutePathAndHttpMethod(site,"/same","GET")).thenReturn(true);var v=service.validate(site,"/scripts/controllers/a.controller.json","{\"route\":\"/same\",\"method\":\"GET\",\"direct\":{\"mediaType\":\"application/json\",\"body\":{}}}",null);assertFalse(v.valid());assertTrue(v.errors().contains("route + method conflict"),v.errors().toString());}
 @Test void rejectsOutputAndQueryCaps(){String body="x".repeat(3000);var v=service.validate(site,"/scripts/controllers/a.controller.json","{\"route\":\"/a\",\"method\":\"POST\",\"direct\":{\"mediaType\":\"text/html\",\"body\":\""+body+"\"},\"queries\":[{\"as\":\"x\",\"op\":\"list\",\"limit\":51}]}",null);assertFalse(v.valid());assertTrue(v.errors().stream().anyMatch(x->x.contains("mediaType")));assertTrue(v.errors().stream().anyMatch(x->x.contains("最大 50")));}
}
