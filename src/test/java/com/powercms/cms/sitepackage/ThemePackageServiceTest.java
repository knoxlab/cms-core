package com.powercms.cms.sitepackage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.audit.AuditService;
import com.powercms.cms.delivery.RenderDatasetCache;
import com.powercms.cms.sitepackage.entity.*;
import com.powercms.cms.sitepackage.repo.*;
import com.powercms.cms.site.repo.SiteRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ThemePackageServiceTest {
    @Mock SiteThemeRepository themes; @Mock SiteThemeFileRepository files; @Mock SiteThemeRevisionRepository revisions; @Mock SiteThemeRevisionFileRepository revisionFiles; @Mock SiteThemeShareRepository shares; @Mock SiteRepository sites; @Mock com.powercms.cms.publish.repo.ReleaseThemeVersionRepository releaseThemes; @Mock AuditService audit;
    ThemePackageService service; UUID siteId=UUID.randomUUID(); Map<UUID,SiteThemeEntity> themeStore; Map<UUID,List<SiteThemeFileEntity>> fileStore; Map<UUID,SiteThemeRevisionEntity> revisionStore; Map<UUID,List<SiteThemeRevisionFileEntity>> revisionFileStore;
    @BeforeEach void setup(){themeStore=new HashMap<>();fileStore=new HashMap<>();revisionStore=new HashMap<>();revisionFileStore=new HashMap<>();service=new ThemePackageService(themes,files,revisions,revisionFiles,shares,sites,releaseThemes,audit,new RenderDatasetCache(32),new ObjectMapper());
        lenient().when(themes.save(any())).thenAnswer(i->{SiteThemeEntity t=i.getArgument(0);if(t.getId()==null)t.setId(UUID.randomUUID());themeStore.put(t.getId(),t);return t;});
        lenient().when(themes.findBySiteIdAndNameIgnoreCase(any(),any())).thenAnswer(i->themeStore.values().stream().filter(t->t.getSiteId().equals(i.getArgument(0))&&t.getName().equalsIgnoreCase(i.getArgument(1))).findFirst());
        lenient().when(themes.saveAndFlush(any())).thenAnswer(i->{SiteThemeEntity t=i.getArgument(0);if(t.getId()==null)t.setId(UUID.randomUUID());themeStore.put(t.getId(),t);return t;});
        lenient().when(themes.findById(any())).thenAnswer(i->Optional.ofNullable(themeStore.get(i.getArgument(0)))); lenient().when(files.save(any())).thenAnswer(i->{SiteThemeFileEntity f=i.getArgument(0);fileStore.computeIfAbsent(f.getThemeId(),x->new ArrayList<>()).add(f);return f;});
        lenient().doAnswer(i->{fileStore.remove((UUID)i.getArgument(0));return null;}).when(files).deleteByThemeId(any()); lenient().when(files.findByThemeIdAndPath(any(),any())).thenAnswer(i->fileStore.getOrDefault(i.getArgument(0),List.of()).stream().filter(f->f.getPath().equals(i.getArgument(1))).findFirst()); lenient().when(files.findByThemeIdOrderByPathAsc(any())).thenAnswer(i->fileStore.getOrDefault(i.getArgument(0),List.of()).stream().sorted(Comparator.comparing(SiteThemeFileEntity::getPath)).toList());
        lenient().when(revisions.save(any())).thenAnswer(i->{SiteThemeRevisionEntity r=i.getArgument(0);if(r.getId()==null)r.setId(UUID.randomUUID());revisionStore.put(r.getId(),r);return r;});
        lenient().when(revisions.findByThemeIdAndVersion(any(),anyInt())).thenAnswer(i->revisionStore.values().stream().filter(r->r.getThemeId().equals(i.getArgument(0))&&r.getVersion()==(int)i.getArgument(1)).findFirst());
        lenient().when(revisions.findByThemeIdOrderByVersionDesc(any())).thenAnswer(i->revisionStore.values().stream().filter(r->r.getThemeId().equals(i.getArgument(0))).sorted(Comparator.comparingInt(SiteThemeRevisionEntity::getVersion).reversed()).toList());
        lenient().when(revisionFiles.save(any())).thenAnswer(i->{SiteThemeRevisionFileEntity f=i.getArgument(0);revisionFileStore.computeIfAbsent(f.getThemeRevisionId(),x->new ArrayList<>()).add(f);return f;});
        lenient().when(revisionFiles.findByThemeRevisionIdOrderByPathAsc(any())).thenAnswer(i->revisionFileStore.getOrDefault(i.getArgument(0),List.of()).stream().sorted(Comparator.comparing(SiteThemeRevisionFileEntity::getPath)).toList());
        lenient().when(shares.save(any())).thenAnswer(i->{SiteThemeShareEntity x=i.getArgument(0);if(x.getId()==null)x.setId(UUID.randomUUID());return x;}); }
    @Test void importsValidTheme(){var r=service.importZip(siteId,zip(Map.of("theme.yaml",manifest(),"template.ftl","<html>${body?no_esc}</html>","header.ftl","<header>H</header>","footer.ftl","F","html_head.ftl","H")),"admin");assertThat(r.validity()).isEqualTo("VALID");assertThat(r.files()).hasSize(5);}
    @Test void listsOnlyPublishedThemesWithNonEmptyTemplateUnlessDraftsRequested() {
        var valid=service.importZip(siteId,zip(Map.of("theme.yaml",manifest("valid"),"template.ftl","layout")),"admin");
        var draft=new SiteThemeEntity(); draft.setId(UUID.randomUUID()); draft.setSiteId(siteId); draft.setName("draft"); draft.setStatus("DRAFT"); draft.setValidity("LEGACY"); themeStore.put(draft.getId(),draft);
        when(themes.findBySiteIdOrderByNameAsc(siteId)).thenAnswer(i->themeStore.values().stream().sorted(Comparator.comparing(SiteThemeEntity::getName)).toList());
        assertThat(service.themesAvailableToSite(siteId)).extracting(x->x.theme().getId()).containsExactly(valid.themeId());
        assertThat(service.themesAvailableToSite(siteId,true)).extracting(x->x.theme().getId()).contains(draft.getId(),valid.themeId());
    }

    @Test void firstTemplateFileActivatesDraftThemeAndCreatesInitialRevision() {
        var draft=new SiteThemeEntity(); draft.setId(UUID.randomUUID()); draft.setSiteId(siteId); draft.setName("new-theme"); draft.setStatus("DRAFT"); draft.setValidity("LEGACY"); draft.setCurrentVersion(0); themeStore.put(draft.getId(),draft);
        var saved=service.saveBrowserTextFile(siteId,draft.getId(),"themes/new-theme/template.ftl","<html>${pageBody!}</html>",0,"admin");
        assertThat(saved.version()).isEqualTo(1); assertThat(draft.getStatus()).isEqualTo("PUBLISHED"); assertThat(draft.getValidity()).isEqualTo("VALID");
        assertThat(service.revisions(siteId,draft.getId())).extracting(ThemePackageService.RevisionInfo::version).containsExactly(1);
    }
    @Test void rejectsMissingTemplate(){assertThatThrownBy(()->service.importZip(siteId,zip(Map.of("theme.yaml",manifest())),"a")).hasMessageContaining("template.ftl");}
    @Test void warnsForMissingFragments(){var r=service.importZip(siteId,zip(Map.of("theme.yaml",manifest(),"template.ftl","${body?no_esc}")),"a");assertThat(r.warnings()).hasSize(3);}
    @Test void rejectsTraversalAndDuplicate(){assertThatThrownBy(()->service.importZip(siteId,zip(Map.of("../theme.yaml",manifest(),"template.ftl","x")),"a")).hasMessageContaining("路径穿越");assertThatThrownBy(()->service.importZip(siteId,caseConflictZip(),"a")).hasMessageContaining("大小写冲突");}
    @Test void versionsDiffRestoreAndHistoricalExport(){var first=service.importZip(siteId,zip(Map.of("theme.yaml",manifest(),"template.ftl","one")),"alice");var second=service.importZip(siteId,zip(Map.of("theme.yaml",manifest(),"template.ftl","two","extra.css","x")),"bob");assertThat(first.version()).isEqualTo(1);assertThat(second.version()).isEqualTo(2);assertThat(service.revisions(siteId,first.themeId())).extracting(ThemePackageService.RevisionInfo::version).containsExactly(2,1);assertThat(service.diff(siteId,first.themeId(),1,2)).extracting(ThemePackageService.DiffEntry::change).contains("MODIFIED","ADDED");var oldExport=service.exportZip(siteId,first.themeId(),1,"alice");assertThat(new String(unzip(oldExport.content()).get("themes/clean-theme/template.ftl"),StandardCharsets.UTF_8)).isEqualTo("one");var restored=service.restore(siteId,first.themeId(),1,"admin",null);assertThat(restored.version()).isEqualTo(3);assertThat(new String(fileStore.get(first.themeId()).stream().filter(f->f.getPath().endsWith("template.ftl")).findFirst().orElseThrow().getContent(),StandardCharsets.UTF_8)).isEqualTo("one");assertThat(new String(revisionFileStore.get(revisionStore.values().stream().filter(r->r.getVersion()==2).findFirst().orElseThrow().getId()).stream().filter(f->f.getPath().endsWith("template.ftl")).findFirst().orElseThrow().getContent(),StandardCharsets.UTF_8)).isEqualTo("two");}
    @Test void browserEditCreatesImmutableThemeRevision() {
        var imported=service.importZip(siteId,zip(Map.of("theme.yaml",manifest(),"template.ftl","one","css/site.css","body{}")),"alice");
        assertThat(service.browserFiles(siteId,imported.themeId())).extracting(ThemePackageService.BrowserFile::path).contains("themes/clean-theme/css/site.css");
        var edited=service.saveBrowserTextFile(siteId,imported.themeId(),"themes/clean-theme/css/site.css","body{color:red}",1,"alice");
        assertThat(edited.version()).isEqualTo(2); assertThat(edited.content()).isEqualTo("body{color:red}");
        assertThat(service.revisions(siteId,imported.themeId())).extracting(ThemePackageService.RevisionInfo::version).containsExactly(2,1);
        assertThat(new String(unzip(service.exportZip(siteId,imported.themeId(),1,"alice").content()).get("themes/clean-theme/css/site.css"),StandardCharsets.UTF_8)).isEqualTo("body{}");
    }

    @Test void listsCssAndJsAssetsForSiteBrowserAndRenderer() {
        var imported = service.importZip(siteId, zip(Map.of(
            "theme.yaml", manifest(),
            "template.ftl", "one",
            "css/site.css", "body{}",
            "css/extra.css", "h1{}",
            "js/site.js", "console.log(1)")), "alice");
        assertThat(service.listRelativeTextAssets(siteId, imported.themeId(), "css"))
            .containsExactly("css/extra.css", "css/site.css");
        assertThat(service.listRelativeTextAssets(siteId, imported.themeId(), "js"))
            .containsExactly("js/site.js");
        assertThat(service.browserFiles(siteId, imported.themeId()))
            .extracting(ThemePackageService.BrowserFile::path)
            .contains("themes/clean-theme/css/site.css", "themes/clean-theme/js/site.js");
    }

    @Test void exportReimportKeepsDigestSummary(){var first=service.importZip(siteId,zip(Map.of("theme.yaml",manifest(),"template.ftl","${body?no_esc}")),"a");var out=service.exportZip(siteId,first.themeId(),"a");var expected=first.files().stream().map(ThemePackageService.FileDigest::sha256).sorted().toList();themeStore.clear();fileStore.clear();revisionStore.clear();revisionFileStore.clear();
        lenient().when(themes.findBySiteIdAndNameIgnoreCase(any(),any())).thenReturn(Optional.empty());var second=service.importZip(siteId,out.content(),"a");assertThat(second.files().stream().map(ThemePackageService.FileDigest::sha256).sorted()).containsExactlyElementsOf(expected);}

    @Test void inheritanceFallsBackOverridesAndPinsParentVersion(){var parent=service.importZip(siteId,zip(Map.of("theme.yaml",manifest("parent"),"template.ftl","P1","header.ftl","parent-header","footer.ftl","parent-footer")),"a");var child=service.importZip(siteId,zip(Map.of("theme.yaml",manifest("child"),"template.ftl","C","header.ftl","child-header")),"a");service.setParent(siteId,child.themeId(),parent.themeId(),"admin");assertThat(service.readText(siteId,child.themeId(),"header.ftl",false)).isEqualTo("child-header");assertThat(service.readText(siteId,child.themeId(),"footer.ftl",false)).isEqualTo("parent-footer");service.importZip(siteId,zip(Map.of("theme.yaml",manifest("parent"),"template.ftl","P2","footer.ftl","new-footer")),"a");assertThat(service.readText(siteId,child.themeId(),"footer.ftl",false)).isEqualTo("parent-footer");}
    @Test void supportsMultiLevelAndRejectsCycles(){var a=service.importZip(siteId,zip(Map.of("theme.yaml",manifest("a"),"template.ftl","A","footer.ftl","deep")),"a");var b=service.importZip(siteId,zip(Map.of("theme.yaml",manifest("b"),"template.ftl","B")),"a");var c=service.importZip(siteId,zip(Map.of("theme.yaml",manifest("c"),"template.ftl","C")),"a");service.setParent(siteId,b.themeId(),a.themeId(),"a");service.setParent(siteId,c.themeId(),b.themeId(),"a");assertThat(service.readText(siteId,c.themeId(),"footer.ftl",false)).isEqualTo("deep");assertThatThrownBy(()->service.setParent(siteId,a.themeId(),a.themeId(),"a")).isInstanceOf(com.powercms.common.error.BusinessException.class);assertThatThrownBy(()->service.setParent(siteId,a.themeId(),c.themeId(),"a")).isInstanceOf(com.powercms.common.error.BusinessException.class);}
    @Test void restoreRecoversParentReference(){var p1=service.importZip(siteId,zip(Map.of("theme.yaml",manifest("p1"),"template.ftl","P1")),"a");var p2=service.importZip(siteId,zip(Map.of("theme.yaml",manifest("p2"),"template.ftl","P2")),"a");var child=service.importZip(siteId,zip(Map.of("theme.yaml",manifest("restore-child"),"template.ftl","C")),"a");int first=service.setParent(siteId,child.themeId(),p1.themeId(),"a").version();service.setParent(siteId,child.themeId(),p2.themeId(),"a");service.restore(siteId,child.themeId(),first,"a",null);assertThat(themeStore.get(child.themeId()).getParentThemeId()).isEqualTo(p1.themeId());}

    @Test void sharingPinsVersionAndRequiresExplicitUpdate() {
        UUID consumer=UUID.randomUUID(); when(sites.existsById(consumer)).thenReturn(true);
        var imported=service.importZip(siteId,zip(Map.of("theme.yaml",manifest(),"template.ftl","v1")),"owner");
        var share=service.grantShare(siteId,imported.themeId(),consumer,null,"owner");
        when(shares.findByThemeIdAndConsumerSiteId(imported.themeId(),consumer)).thenReturn(Optional.of(shareEntity(imported.themeId(),consumer,1,"ACTIVE")));
        assertThat(share.pinnedVersion()).isEqualTo(1);
        assertThat(service.readAvailableText(consumer,imported.themeId(),"template.ftl",false)).isEqualTo("v1");
        var sharedFiles = service.browserFiles(consumer, imported.themeId());
        assertThat(sharedFiles).extracting(ThemePackageService.BrowserFile::editable).containsOnly(false);
        assertThat(sharedFiles).extracting(ThemePackageService.BrowserFile::version).containsOnly(1);
        assertThatThrownBy(() -> service.saveBrowserTextFile(consumer, imported.themeId(), "themes/clean-theme/template.ftl", "blocked", 1, "consumer"))
            .isInstanceOf(com.powercms.common.error.BusinessException.class);
        service.importZip(siteId,zip(Map.of("theme.yaml",manifest(),"template.ftl","v2")),"owner");
        assertThat(service.readAvailableText(consumer,imported.themeId(),"template.ftl",false)).isEqualTo("v1");
        var active=shareEntity(imported.themeId(),consumer,1,"ACTIVE"); when(shares.findByThemeIdAndConsumerSiteId(imported.themeId(),consumer)).thenReturn(Optional.of(active));
        service.updateShareVersion(siteId,imported.themeId(),consumer,2,"owner");
        assertThat(active.getPinnedVersion()).isEqualTo(2);
        assertThat(service.readAvailableText(consumer,imported.themeId(),"template.ftl",false)).isEqualTo("v2");
        service.revokeShare(siteId,imported.themeId(),consumer,"owner");
        assertThatThrownBy(()->service.readAvailableText(consumer,imported.themeId(),"template.ftl",false)).isInstanceOf(com.powercms.common.error.BusinessException.class);
    }
    @Test void consumerCannotUseOwnerWriteOperations() {
        UUID consumer=UUID.randomUUID(); var imported=service.importZip(siteId,zip(Map.of("theme.yaml",manifest(),"template.ftl","v1")),"owner");
        assertThatThrownBy(()->service.restore(consumer,imported.themeId(),1,"consumer",null)).isInstanceOf(com.powercms.common.error.BusinessException.class);
        assertThatThrownBy(()->service.setParent(consumer,imported.themeId(),null,"consumer")).isInstanceOf(com.powercms.common.error.BusinessException.class);
        assertThatThrownBy(()->service.exportZip(consumer,imported.themeId(),"consumer")).isInstanceOf(com.powercms.common.error.BusinessException.class);
    }

    @Test void blocksDeletingReleaseShareAndParentReferencedVersions() {
        var imported=service.importZip(siteId,zip(Map.of("theme.yaml",manifest(),"template.ftl","v1")),"owner");
        service.importZip(siteId,zip(Map.of("theme.yaml",manifest(),"template.ftl","v2")),"owner");
        when(releaseThemes.existsByThemeRevisionId(any())).thenReturn(true);
        assertThatThrownBy(()->service.deleteRevision(siteId,imported.themeId(),1,"owner")).hasMessageContaining("Release");
        when(releaseThemes.existsByThemeRevisionId(any())).thenReturn(false);
        when(shares.existsByThemeIdAndPinnedVersionAndStatus(imported.themeId(),1,SiteThemeShareEntity.ACTIVE)).thenReturn(true);
        assertThatThrownBy(()->service.deleteRevision(siteId,imported.themeId(),1,"owner")).hasMessageContaining("\u56fa\u5b9a");
        when(shares.existsByThemeIdAndPinnedVersionAndStatus(imported.themeId(),1,SiteThemeShareEntity.ACTIVE)).thenReturn(false);
        when(revisions.existsByParentThemeIdAndParentThemeVersion(imported.themeId(),1)).thenReturn(true);
        assertThatThrownBy(()->service.deleteRevision(siteId,imported.themeId(),1,"owner")).hasMessageContaining("revision");
        assertThatThrownBy(()->service.deleteRevision(siteId,imported.themeId(),2,"owner")).hasMessageContaining("\u5f53\u524d\u7248\u672c");
        verify(audit,atLeast(4)).write(anyString(),eq("THEME_REVISION_DELETE"),anyString(),anyString(),eq(siteId),eq("BLOCKED"),anyString());
    }
    @Test void deletesUnreferencedHistoricalVersionAndAudits() {
        var imported=service.importZip(siteId,zip(Map.of("theme.yaml",manifest(),"template.ftl","v1")),"owner");
        service.importZip(siteId,zip(Map.of("theme.yaml",manifest(),"template.ftl","v2")),"owner");
        service.deleteRevision(siteId,imported.themeId(),1,"owner");
        verify(revisions).delete(argThat(r->r.getThemeId().equals(imported.themeId())&&r.getVersion()==1));
        verify(audit).write(eq("owner"),eq("THEME_REVISION_DELETE"),eq("THEME_REVISION"),anyString(),eq(siteId),eq("SUCCESS"),anyString());
    }
    private SiteThemeShareEntity shareEntity(UUID themeId,UUID consumer,int version,String status){var x=new SiteThemeShareEntity();x.setId(UUID.randomUUID());x.setThemeId(themeId);x.setConsumerSiteId(consumer);x.setPinnedVersion(version);x.setStatus(status);return x;}
    private String manifest(){return manifest("clean-theme");}
    private String manifest(String name){return "name: " + name + "\nversion: 1.0.0\nvendor: PowerCOM\ncmsVersionRange: 12.x\n";}
    private byte[] zip(Map<String,String> entries){try{ByteArrayOutputStream b=new ByteArrayOutputStream();try(ZipOutputStream z=new ZipOutputStream(b)){for(var e:entries.entrySet()){z.putNextEntry(new ZipEntry(e.getKey()));z.write(e.getValue().getBytes(StandardCharsets.UTF_8));z.closeEntry();}}return b.toByteArray();}catch(IOException e){throw new UncheckedIOException(e);}}
    private Map<String,byte[]> unzip(byte[] archive){try{Map<String,byte[]> out=new HashMap<>();try(ZipInputStream z=new ZipInputStream(new ByteArrayInputStream(archive))){ZipEntry e;while((e=z.getNextEntry())!=null)out.put(e.getName(),z.readAllBytes());}return out;}catch(IOException e){throw new UncheckedIOException(e);}}
    private byte[] caseConflictZip(){try{ByteArrayOutputStream b=new ByteArrayOutputStream();try(ZipOutputStream z=new ZipOutputStream(b)){for(String p:List.of("theme.yaml","template.ftl","Template.ftl")){z.putNextEntry(new ZipEntry(p));z.write((p.equals("theme.yaml")?manifest():"x").getBytes(StandardCharsets.UTF_8));z.closeEntry();}}return b.toByteArray();}catch(IOException e){throw new UncheckedIOException(e);}}
}
