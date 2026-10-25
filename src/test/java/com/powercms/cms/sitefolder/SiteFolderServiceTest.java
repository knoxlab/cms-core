package com.powercms.cms.sitefolder;
import com.powercms.cms.audit.AuditService;import com.powercms.cms.content.repo.ContentItemRepository;import com.powercms.cms.delivery.RenderDatasetCache;import com.powercms.cms.site.repo.SiteRepository;import com.powercms.cms.sitefolder.entity.SiteFolderEntity;import com.powercms.cms.sitefolder.repo.SiteFolderRepository;import org.junit.jupiter.api.Test;import java.util.*;import static org.junit.jupiter.api.Assertions.*;import static org.mockito.ArgumentMatchers.*;import static org.mockito.Mockito.*;
class SiteFolderServiceTest {
 @Test void createsNestedMenuFoldersWithCanonicalPaths(){var repo=mock(SiteFolderRepository.class);var sites=mock(SiteRepository.class);var content=mock(ContentItemRepository.class);var audit=mock(AuditService.class);var site=UUID.randomUUID();when(sites.existsById(site)).thenReturn(true);when(repo.saveAndFlush(any())).thenAnswer(x->{SiteFolderEntity e=x.getArgument(0);if(e.getId()==null)e.setId(UUID.randomUUID());return e;});SiteFolderEntity parent=new SiteFolderEntity();parent.setId(UUID.randomUUID());parent.setSiteId(site);parent.setTitle("Store");parent.setName("store");parent.setPath("/store");when(repo.findByIdAndSiteId(parent.getId(),site)).thenReturn(Optional.of(parent));var service=new SiteFolderService(repo,sites,content,audit,new RenderDatasetCache(20));var result=service.create(site,parent.getId(),"Apparel","apparel",2,true,"jpg,png","IMAGE","admin");assertEquals("/store/apparel",result.path());assertTrue(result.showOnMenu());assertEquals("IMAGE",result.defaultFileAssetType());verify(audit).write(eq("admin"),eq("SITE_FOLDER_CREATE"),eq("SITE_FOLDER"),anyString(),eq(site),eq("SUCCESS"),contains("/store/apparel"));}
 @Test void refusesCrossSiteParent(){var repo=mock(SiteFolderRepository.class);var sites=mock(SiteRepository.class);var service=new SiteFolderService(repo,sites,mock(ContentItemRepository.class),mock(AuditService.class),new RenderDatasetCache(20));UUID site=UUID.randomUUID();when(sites.existsById(site)).thenReturn(true);assertThrows(RuntimeException.class,()->service.create(site,UUID.randomUUID(),"Bad","bad",0,true,null,"FILE","admin"));}
 @Test void assessImpactReportsDeleteBlockersAndPathChange(){
  var repo=mock(SiteFolderRepository.class);var content=mock(ContentItemRepository.class);
  var service=new SiteFolderService(repo,mock(SiteRepository.class),content,mock(AuditService.class),new RenderDatasetCache(20));
  UUID site=UUID.randomUUID();
  SiteFolderEntity e=folder(site,null,"Apparel","apparel","/store/apparel");
  SiteFolderEntity kid=folder(site,e.getId(),"Kids","kids","/store/apparel/kids");
  when(repo.findByIdAndSiteId(e.getId(),site)).thenReturn(Optional.of(e));
  when(repo.findBySiteIdOrderBySortOrderAscTitleAsc(site)).thenReturn(List.of(e,kid));
  when(content.countByFolderId(e.getId())).thenReturn(2L);
  when(content.countByFolderId(kid.getId())).thenReturn(1L);
  var unlinked=new com.powercms.cms.content.entity.ContentItemEntity();unlinked.setRoutePath("/store/apparel/faq");
  when(content.findBySiteId(site)).thenReturn(List.of(unlinked));
  var impact=service.assessImpact(site,e.getId());
  assertEquals(1,impact.descendantFolders());
  assertEquals(3L,impact.contentsInSubtree());
  assertEquals(2L,impact.directContents());
  assertEquals(1L,impact.pagesUnderPath());
  assertFalse(impact.deletable());
  assertTrue(impact.pathChangeBlocked());
  assertTrue(impact.deleteBlockers().containsAll(List.of("HAS_CHILD_FOLDERS","HAS_CONTENT","UNLINKED_PAGES")));
 }
 @Test void assessImpactMarksDeletableLeafWithNoPages(){
  var repo=mock(SiteFolderRepository.class);var content=mock(ContentItemRepository.class);
  var service=new SiteFolderService(repo,mock(SiteRepository.class),content,mock(AuditService.class),new RenderDatasetCache(20));
  UUID site=UUID.randomUUID();
  SiteFolderEntity e=folder(site,null,"Apparel","apparel","/store/apparel");
  when(repo.findByIdAndSiteId(e.getId(),site)).thenReturn(Optional.of(e));
  when(repo.findBySiteIdOrderBySortOrderAscTitleAsc(site)).thenReturn(List.of(e));
  when(content.countByFolderId(e.getId())).thenReturn(0L);
  when(content.findBySiteId(site)).thenReturn(List.of());
  var impact=service.assessImpact(site,e.getId());
  assertTrue(impact.deletable());
  assertTrue(impact.deleteBlockers().isEmpty());
  assertFalse(impact.pathChangeBlocked());
  assertEquals(0,impact.descendantFolders());
  assertEquals(0L,impact.pagesUnderPath());
 }
 private static SiteFolderEntity folder(UUID site,UUID parentId,String title,String name,String path){
  SiteFolderEntity e=new SiteFolderEntity();e.setId(UUID.randomUUID());e.setSiteId(site);e.setParentId(parentId);e.setTitle(title);e.setName(name);e.setPath(path);return e;
 }
}
