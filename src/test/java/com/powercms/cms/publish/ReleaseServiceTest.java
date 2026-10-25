package com.powercms.cms.publish;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.delivery.PageRenderService;
import com.powercms.cms.delivery.navigation.UrlRewriteRuleRepository;
import com.powercms.cms.media.repo.MediaAssetRepository;
import com.powercms.cms.media.repo.MediaVersionRepository;
import com.powercms.cms.publish.entity.*;
import com.powercms.cms.publish.repo.*;
import com.powercms.cms.sitepackage.ThemePackageService;
import com.powercms.cms.sitepackage.entity.*;
import com.powercms.cms.sitepackage.repo.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReleaseServiceTest {
    @Mock ReleaseRepository releases; @Mock ReleaseContentRepository contents; @Mock ContentItemRepository items;
    @Mock ReleasePageRepository pages; @Mock PageRenderService renderer; @Mock TemplateProjectRepository projects;
    @Mock SiteThemeRepository themes; @Mock SiteTemplateRepository templates; @Mock SiteContainerRepository containers;
    @Mock SiteDropZoneRepository zones; @Mock PageVisualCompositionRepository compositions; @Mock ThemePackageService themePackages;
    @Mock SiteThemeRevisionRepository revisions; @Mock ReleaseThemeVersionRepository releaseThemes;
    @Mock MediaAssetRepository mediaAssets; @Mock MediaVersionRepository mediaVersions;
    @Mock ReleaseMediaVersionRepository releaseMedia; @Mock UrlRewriteRuleRepository rewriteRules;
    @Mock ReleaseUrlRewriteRepository releaseRewrites;
    @Mock com.powercms.cms.identity.permission.ObjectPermissionService permissions;

    @Test void freezesOnlyThemesUsedByPublishedPageCompositionsWithOwnedAndSharedVersions() {
        UUID site=UUID.randomUUID(), releaseId=UUID.randomUUID(), ownedId=UUID.randomUUID(), sharedId=UUID.randomUUID(), unusedId=UUID.randomUUID();
        ReleaseEntity release=draft(releaseId,site); when(releases.findById(releaseId)).thenReturn(Optional.of(release)); when(releases.save(any())).thenAnswer(i->i.getArgument(0));
        ContentItemEntity ownedPage=page(site,"/owned"), sharedPage=page(site,"/shared"), plainPage=page(site,"/plain");
        when(items.findBySiteIdAndStatus(site,"PUBLISHED")).thenReturn(List.of(ownedPage,sharedPage,plainPage));
        SiteTemplateEntity ownedTemplate=template(site,ownedId), sharedTemplate=template(site,sharedId);
        when(compositions.findByPageId(ownedPage.getId())).thenReturn(Optional.of(composition(ownedPage,ownedTemplate)));
        when(compositions.findByPageId(sharedPage.getId())).thenReturn(Optional.of(composition(sharedPage,sharedTemplate)));
        when(compositions.findByPageId(plainPage.getId())).thenReturn(Optional.empty());
        when(templates.findById(ownedTemplate.getId())).thenReturn(Optional.of(ownedTemplate)); when(templates.findById(sharedTemplate.getId())).thenReturn(Optional.of(sharedTemplate));
        SiteThemeEntity owned=theme(ownedId,site,3), shared=theme(sharedId,UUID.randomUUID(),9);
        when(themePackages.resolveAvailableTheme(site,ownedId)).thenReturn(new ThemePackageService.AvailableTheme(owned,"OWNED",site,false,null));
        when(themePackages.resolveAvailableTheme(site,sharedId)).thenReturn(new ThemePackageService.AvailableTheme(shared,"SHARED",shared.getSiteId(),true,4));
        when(themes.findById(ownedId)).thenReturn(Optional.of(owned)); when(themes.findById(sharedId)).thenReturn(Optional.of(shared));
        when(revisions.findByThemeIdAndVersion(ownedId,3)).thenReturn(Optional.of(revision(ownedId,3,"owned-digest")));
        when(revisions.findByThemeIdAndVersion(sharedId,4)).thenReturn(Optional.of(revision(sharedId,4,"shared-digest")));
        when(renderer.renderPublishedForRelease(eq(site),anyString(),any())).thenAnswer(i->new PageRenderService.RenderedPage(UUID.randomUUID(),i.getArgument(1),"t",null,"<html>fixed</html>","PUBLISHED",1));
        when(renderer.renderForRelease(eq(site),anyString())).thenAnswer(i->new PageRenderService.RenderedPage(UUID.randomUUID(),i.getArgument(1),"t",null,"<html>plain</html>","PUBLISHED",1));
        when(projects.findBySiteIdAndProjectType(site,"FTL")).thenReturn(List.of()); when(templates.findBySiteIdOrderByNameAsc(site)).thenReturn(List.of()); when(containers.findBySiteIdOrderByNameAsc(site)).thenReturn(List.of()); when(compositions.findAll()).thenReturn(List.of());
        when(mediaAssets.findBySiteId(site)).thenReturn(List.of()); when(rewriteRules.findBySiteIdOrderByPriorityDescNameAsc(site)).thenReturn(List.of());
        List<ReleaseThemeVersionEntity> saved=new ArrayList<>(); when(releaseThemes.save(any())).thenAnswer(i->{saved.add(i.getArgument(0));return i.getArgument(0);});

        service().freeze(releaseId);

        assertThat(saved).extracting(ReleaseThemeVersionEntity::getThemeId).containsExactlyInAnyOrder(ownedId,sharedId).doesNotContain(unusedId);
        assertThat(saved).extracting(ReleaseThemeVersionEntity::getThemeVersion).containsExactlyInAnyOrder(3,4);
        assertThat(saved).extracting(ReleaseThemeVersionEntity::getSource).containsExactlyInAnyOrder("OWNED","SHARED");
        assertThat(release.getInputDigest()).isNotBlank(); verify(pages,times(3)).save(any());
    }

    @Test void laterThemeUpgradeDoesNotMutateFrozenPinsOrHtml() {
        UUID site=UUID.randomUUID(), releaseId=UUID.randomUUID(), themeId=UUID.randomUUID(); ReleaseEntity release=draft(releaseId,site);
        ContentItemEntity page=page(site,"/page"); SiteTemplateEntity template=template(site,themeId); PageVisualCompositionEntity composition=composition(page,template); SiteThemeEntity theme=theme(themeId,site,1);
        when(releases.findById(releaseId)).thenReturn(Optional.of(release)); when(releases.save(any())).thenAnswer(i->i.getArgument(0)); when(items.findBySiteIdAndStatus(site,"PUBLISHED")).thenReturn(List.of(page));
        when(compositions.findByPageId(page.getId())).thenReturn(Optional.of(composition)); when(templates.findById(template.getId())).thenReturn(Optional.of(template));
        when(themePackages.resolveAvailableTheme(site,themeId)).thenReturn(new ThemePackageService.AvailableTheme(theme,"OWNED",site,false,null)); when(themes.findById(themeId)).thenReturn(Optional.of(theme)); when(revisions.findByThemeIdAndVersion(themeId,1)).thenReturn(Optional.of(revision(themeId,1,"digest-v1")));
        when(renderer.renderPublishedForRelease(eq(site),eq("/page"),any())).thenReturn(new PageRenderService.RenderedPage(page.getId(),"/page","t",null,"<html>v1</html>","PUBLISHED",1));
        when(projects.findBySiteIdAndProjectType(site,"FTL")).thenReturn(List.of()); when(templates.findBySiteIdOrderByNameAsc(site)).thenReturn(List.of()); when(containers.findBySiteIdOrderByNameAsc(site)).thenReturn(List.of()); when(compositions.findAll()).thenReturn(List.of(composition));
        when(mediaAssets.findBySiteId(site)).thenReturn(List.of()); when(rewriteRules.findBySiteIdOrderByPriorityDescNameAsc(site)).thenReturn(List.of());
        List<ReleaseThemeVersionEntity> pins=new ArrayList<>(); List<ReleasePageEntity> frozenPages=new ArrayList<>(); when(releaseThemes.save(any())).thenAnswer(i->{pins.add(i.getArgument(0));return i.getArgument(0);}); when(pages.save(any())).thenAnswer(i->{frozenPages.add(i.getArgument(0));return i.getArgument(0);});
        service().freeze(releaseId); String inputDigest=release.getInputDigest(), html=frozenPages.get(0).getHtmlContent();
        theme.setCurrentVersion(2);
        assertThat(pins.get(0).getThemeVersion()).isEqualTo(1); assertThat(pins.get(0).getFileDigest()).isEqualTo("digest-v1");
        assertThat(frozenPages.get(0).getHtmlContent()).isEqualTo(html); assertThat(release.getInputDigest()).isEqualTo(inputDigest);
    }

    @Test void freezesInheritanceClosureAndPassesPinnedParentContext() {
        UUID site=UUID.randomUUID(), releaseId=UUID.randomUUID(), rootId=UUID.randomUUID(), parentId=UUID.randomUUID();
        ReleaseEntity release=draft(releaseId,site); ContentItemEntity page=page(site,"/inherited"); SiteTemplateEntity template=template(site,rootId);
        SiteThemeEntity root=theme(rootId,site,2), parent=theme(parentId,site,7);
        SiteThemeRevisionEntity rootRevision=revision(rootId,2,"root-files"), parentRevision=revision(parentId,7,"parent-files");
        rootRevision.setParentThemeId(parentId); rootRevision.setParentThemeVersion(7);
        when(releases.findById(releaseId)).thenReturn(Optional.of(release)); when(releases.save(any())).thenAnswer(i->i.getArgument(0));
        when(items.findBySiteIdAndStatus(site,"PUBLISHED")).thenReturn(List.of(page)); when(compositions.findByPageId(page.getId())).thenReturn(Optional.of(composition(page,template)));
        when(templates.findById(template.getId())).thenReturn(Optional.of(template)); when(themePackages.resolveAvailableTheme(site,rootId)).thenReturn(new ThemePackageService.AvailableTheme(root,"OWNED",site,false,null));
        when(themes.findById(rootId)).thenReturn(Optional.of(root)); when(themes.findById(parentId)).thenReturn(Optional.of(parent));
        when(revisions.findByThemeIdAndVersion(rootId,2)).thenReturn(Optional.of(rootRevision)); when(revisions.findByThemeIdAndVersion(parentId,7)).thenReturn(Optional.of(parentRevision));
        when(renderer.renderPublishedForRelease(eq(site),eq("/inherited"),any())).thenAnswer(i -> {
            ReleaseThemeContext context=i.getArgument(2); assertThat(context.require(rootId).revisionId()).isEqualTo(rootRevision.getId()); assertThat(context.require(parentId).revisionId()).isEqualTo(parentRevision.getId());
            parent.setCurrentVersion(8); return new PageRenderService.RenderedPage(page.getId(),"/inherited","t",null,"<html>parent-v7</html>","PUBLISHED",1);
        });
        when(projects.findBySiteIdAndProjectType(site,"FTL")).thenReturn(List.of()); when(templates.findBySiteIdOrderByNameAsc(site)).thenReturn(List.of()); when(containers.findBySiteIdOrderByNameAsc(site)).thenReturn(List.of()); when(compositions.findAll()).thenReturn(List.of());
        when(mediaAssets.findBySiteId(site)).thenReturn(List.of()); when(rewriteRules.findBySiteIdOrderByPriorityDescNameAsc(site)).thenReturn(List.of());
        List<ReleaseThemeVersionEntity> saved=new ArrayList<>(); when(releaseThemes.save(any())).thenAnswer(i->{saved.add(i.getArgument(0));return i.getArgument(0);});
        service().freeze(releaseId);
        assertThat(saved).extracting(ReleaseThemeVersionEntity::getThemeRevisionId).containsExactlyInAnyOrder(rootRevision.getId(),parentRevision.getId());
        assertThat(saved).filteredOn(x->x.getThemeRevisionId().equals(rootRevision.getId())).extracting(ReleaseThemeVersionEntity::getReferenceRole).containsOnly("ROOT");
        assertThat(saved).filteredOn(x->x.getThemeRevisionId().equals(parentRevision.getId())).extracting(ReleaseThemeVersionEntity::getReferenceRole).containsOnly("PARENT");
    }

    @Test void freezeOmitsAnonymousDeniedPageFromRouteManifest() {
        UUID site=UUID.randomUUID(), releaseId=UUID.randomUUID();
        ReleaseEntity release=draft(releaseId,site);
        ContentItemEntity open=page(site,"/open"), closed=page(site,"/closed");
        when(releases.findById(releaseId)).thenReturn(Optional.of(release));
        when(releases.save(any())).thenAnswer(i->i.getArgument(0));
        when(items.findBySiteIdAndStatus(site,"PUBLISHED")).thenReturn(List.of(open, closed));
        when(compositions.findByPageId(any())).thenReturn(Optional.empty());
        when(renderer.renderForRelease(eq(site), eq("/open"))).thenReturn(new PageRenderService.RenderedPage(open.getId(),"/open","t",null,"<html>open</html>","PUBLISHED",1));
        when(projects.findBySiteIdAndProjectType(site,"FTL")).thenReturn(List.of());
        when(templates.findBySiteIdOrderByNameAsc(site)).thenReturn(List.of());
        when(containers.findBySiteIdOrderByNameAsc(site)).thenReturn(List.of());
        when(compositions.findAll()).thenReturn(List.of());
        when(mediaAssets.findBySiteId(site)).thenReturn(List.of());
        when(rewriteRules.findBySiteIdOrderByPriorityDescNameAsc(site)).thenReturn(List.of());
        ReleaseService svc = service();
        when(permissions.hasAnonymousRight(any(), eq(closed.getId()), any())).thenReturn(false);
        svc.freeze(releaseId);
        assertThat(release.getRouteManifest()).contains("/open").doesNotContain("/closed");
        verify(pages, times(1)).save(any());
        verify(renderer, never()).renderForRelease(site, "/closed");
    }

    @Test
    void freezeOmitsPageRestrictedByIpOrTime() {
        UUID releaseId = UUID.randomUUID();
        UUID site = UUID.randomUUID();
        ReleaseEntity release = draft(releaseId, site);
        ContentItemEntity timed = page(site, "/timed");
        when(releases.findById(releaseId)).thenReturn(Optional.of(release));
        when(releases.save(any())).thenAnswer(i -> i.getArgument(0));
        when(items.findBySiteIdAndStatus(site, "PUBLISHED")).thenReturn(List.of(timed));
        when(compositions.findByPageId(any())).thenReturn(Optional.empty());
        when(projects.findBySiteIdAndProjectType(site, "FTL")).thenReturn(List.of());
        when(templates.findBySiteIdOrderByNameAsc(site)).thenReturn(List.of());
        when(containers.findBySiteIdOrderByNameAsc(site)).thenReturn(List.of());
        when(compositions.findAll()).thenReturn(List.of());
        when(mediaAssets.findBySiteId(site)).thenReturn(List.of());
        when(rewriteRules.findBySiteIdOrderByPriorityDescNameAsc(site)).thenReturn(List.of());
        com.powercms.cms.identity.permission.ProgramAccessService program =
            mock(com.powercms.cms.identity.permission.ProgramAccessService.class);
        when(program.variesByRequest(any(), eq(timed.getId()))).thenReturn(true);
        ReleaseService svc = service();
        svc.setProgramAccess(program);
        svc.freeze(releaseId);
        assertThat(release.getRouteManifest()).doesNotContain("/timed");
        verify(renderer, never()).renderForRelease(site, "/timed");
    }

    private ReleaseService service(){
        lenient().when(permissions.hasAnonymousRight(any(), any(), any())).thenReturn(true);
        return new ReleaseService(releases,contents,items,pages,renderer,projects,themes,templates,containers,zones,compositions,new ObjectMapper(),themePackages,revisions,releaseThemes,
            mediaAssets,mediaVersions,releaseMedia,rewriteRules,releaseRewrites,permissions);
    }
    private static ReleaseEntity draft(UUID id,UUID site){var x=new ReleaseEntity();x.setId(id);x.setSiteId(site);x.setName("r");return x;}
    private static ContentItemEntity page(UUID site,String route){var x=new ContentItemEntity();x.setId(UUID.randomUUID());x.setSiteId(site);x.setStatus("PUBLISHED");x.setPublishedVersion(1);x.setRoutePath(route);return x;}
    private static SiteTemplateEntity template(UUID site,UUID theme){var x=new SiteTemplateEntity();x.setId(UUID.randomUUID());x.setSiteId(site);x.setThemeId(theme);return x;}
    private static PageVisualCompositionEntity composition(ContentItemEntity page,SiteTemplateEntity template){var x=new PageVisualCompositionEntity();x.setPageId(page.getId());x.setTemplateId(template.getId());x.setVersion(2);return x;}
    private static SiteThemeEntity theme(UUID id,UUID site,int version){var x=new SiteThemeEntity();x.setId(id);x.setSiteId(site);x.setCurrentVersion(version);return x;}
    private static SiteThemeRevisionEntity revision(UUID theme,int version,String digest){var x=new SiteThemeRevisionEntity();x.setId(UUID.randomUUID());x.setThemeId(theme);x.setVersion(version);x.setFileDigest(digest);return x;}
}
