package com.powercms.cms.identity.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.content.entity.ContentItemEntity;
import com.powercms.cms.content.repo.ContentItemRepository;
import com.powercms.cms.identity.permission.entity.ProgramAccessPolicyEntity;
import com.powercms.cms.identity.permission.repo.ProgramAccessPolicyRepository;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProgramAccessServiceTest {

    private final ProgramAccessPolicyRepository policies = mock(ProgramAccessPolicyRepository.class);
    private final PermissionableResolver resolver = mock(PermissionableResolver.class);
    private final ContentItemRepository contents = mock(ContentItemRepository.class);
    private final GeoResolver geo = mock(GeoResolver.class);
    private final ProgramAccessService service = new ProgramAccessService(
        policies, resolver, contents, geo, new ObjectMapper());

    @Test
    void multipleCidrsAreAUnionAndUnknownIpIsDenied() {
        UUID site = UUID.randomUUID();
        ProgramAccessPolicyEntity row = policy(site, "10.0.0.0/8,192.168.1.10");
        when(policies.findByObjectTypeAndObjectId(ObjectType.SITE, site)).thenReturn(Optional.of(row));
        when(resolver.parent(ObjectType.SITE, site)).thenReturn(null);

        assertThat(service.denyReason(ObjectType.SITE, site, "10.1.2.3", Instant.parse("2026-09-23T00:00:00Z"), false)).isNull();
        assertThat(service.denyReason(ObjectType.SITE, site, "192.168.1.10", Instant.parse("2026-09-23T00:00:00Z"), false)).isNull();
        assertThat(service.denyReason(ObjectType.SITE, site, "8.8.8.8", Instant.parse("2026-09-23T00:00:00Z"), false))
            .isEqualTo(ErrorCodes.ACCESS_IP_DENIED);
        assertThat(service.denyReason(ObjectType.SITE, site, null, Instant.parse("2026-09-23T00:00:00Z"), false))
            .isEqualTo(ErrorCodes.ACCESS_IP_DENIED);
    }

    @Test
    void timeWindowsUseUtcAndAnyWindowAllows() {
        UUID site = UUID.randomUUID();
        ProgramAccessPolicyEntity row = policy(site, "");
        row.setTimeWindows("[{\"from\":\"2026-09-01T00:00:00Z\",\"to\":\"2026-09-02T00:00:00Z\"},{\"from\":\"2026-09-10T00:00:00Z\",\"to\":\"2026-09-11T00:00:00Z\"}]");
        when(policies.findByObjectTypeAndObjectId(ObjectType.SITE, site)).thenReturn(Optional.of(row));
        when(resolver.parent(ObjectType.SITE, site)).thenReturn(null);

        assertThat(service.denyReason(ObjectType.SITE, site, "1.1.1.1", Instant.parse("2026-09-10T12:00:00Z"), false)).isNull();
        assertThat(service.denyReason(ObjectType.SITE, site, "1.1.1.1", Instant.parse("2026-09-03T00:00:00Z"), false))
            .isEqualTo(ErrorCodes.ACCESS_WINDOW_DENIED);
    }

    @Test
    void geoMissOrUnknownLocationIsDenied() {
        UUID site = UUID.randomUUID();
        ProgramAccessPolicyEntity row = policy(site, "");
        row.setGeoAllowlist("{\"countries\":[\"CN\"],\"provinces\":[\"GD\"]}");
        when(policies.findByObjectTypeAndObjectId(ObjectType.SITE, site)).thenReturn(Optional.of(row));
        when(resolver.parent(ObjectType.SITE, site)).thenReturn(null);
        when(geo.locate("1.1.1.1")).thenReturn(Optional.empty());
        when(geo.locate("2.2.2.2")).thenReturn(Optional.of(new GeoResolver.Place("US", "CA")));
        when(geo.locate("3.3.3.3")).thenReturn(Optional.of(new GeoResolver.Place("CN", "BJ")));

        assertThat(service.denyReason(ObjectType.SITE, site, "1.1.1.1", Instant.now(), false)).isEqualTo(ErrorCodes.ACCESS_IP_DENIED);
        assertThat(service.denyReason(ObjectType.SITE, site, "2.2.2.2", Instant.now(), false)).isEqualTo(ErrorCodes.ACCESS_IP_DENIED);
        assertThat(service.denyReason(ObjectType.SITE, site, "3.3.3.3", Instant.now(), false)).isNull();
    }

    @Test
    void downloadDefaultsOffAndCategoryChainIsStricter() {
        UUID content = UUID.randomUUID();
        UUID folder = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        ContentItemEntity item = new ContentItemEntity();
        item.setCategoryId(category);
        when(contents.findById(content)).thenReturn(Optional.of(item));
        when(resolver.parent(ObjectType.CONTENT, content)).thenReturn(new PermissionableResolver.Ref(ObjectType.FOLDER, folder));
        when(resolver.parent(ObjectType.FOLDER, folder)).thenReturn(null);
        when(resolver.parent(ObjectType.TAXONOMY, category)).thenReturn(null);
        ProgramAccessPolicyEntity folderRow = policy(folder, "");
        folderRow.setDownloadEnabled(true);
        ProgramAccessPolicyEntity categoryRow = policy(category, "10.0.0.0/8");
        when(policies.findByObjectTypeAndObjectId(ObjectType.CONTENT, content)).thenReturn(Optional.empty());
        when(policies.findByObjectTypeAndObjectId(ObjectType.FOLDER, folder)).thenReturn(Optional.of(folderRow));
        when(policies.findByObjectTypeAndObjectId(ObjectType.TAXONOMY, category)).thenReturn(Optional.of(categoryRow));

        assertThat(service.effective(ObjectType.CONTENT, content).downloadEnabled()).isTrue();
        assertThat(service.denyReason(ObjectType.CONTENT, content, "11.0.0.1", Instant.now(), false))
            .isEqualTo(ErrorCodes.ACCESS_IP_DENIED);
        assertThat(service.denyReason(ObjectType.CONTENT, content, "10.1.0.1", Instant.now(), true)).isNull();
    }

    @Test
    void invalidCidrAndWindowAreRejected() {
        UUID site = UUID.randomUUID();
        assertThatThrownBy(() -> service.save(site, ObjectType.SITE, site, "10.0.0.0/99", null, null, null, null))
            .isInstanceOf(BusinessException.class)
            .extracting(ex -> ((BusinessException) ex).getCode())
            .isEqualTo(ErrorCodes.ACCESS_POLICY_INVALID);
        assertThatThrownBy(() -> service.save(site, ObjectType.SITE, site, null, null,
            List.of(new ProgramAccessService.Window(Instant.parse("2026-09-02T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z"))),
            null, null))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void mediaDownloadFollowsReferencingContentFolderSwitch() {
        UUID media = UUID.randomUUID();
        UUID content = UUID.randomUUID();
        UUID folder = UUID.randomUUID();
        UUID site = UUID.randomUUID();

        // 媒体自身链：MEDIA → SITE（自动创建的媒体内容项不在栏目里），站点未配置下载
        when(resolver.parent(ObjectType.MEDIA, media)).thenReturn(new PermissionableResolver.Ref(ObjectType.SITE, site));
        when(resolver.parent(ObjectType.SITE, site)).thenReturn(null);
        // 引用它的内容在栏目「新闻」里，该栏目打开了允许下载
        when(resolver.parent(ObjectType.CONTENT, content)).thenReturn(new PermissionableResolver.Ref(ObjectType.FOLDER, folder));
        when(resolver.parent(ObjectType.FOLDER, folder)).thenReturn(new PermissionableResolver.Ref(ObjectType.SITE, site));
        ProgramAccessPolicyEntity folderRow = policy(folder, "");
        folderRow.setDownloadEnabled(true);
        when(policies.findByObjectTypeAndObjectId(ObjectType.FOLDER, folder)).thenReturn(Optional.of(folderRow));
        service.setMediaScope(assetId -> List.of(content));

        ProgramAccessService.DownloadDecision decision = service.resolveDownload(ObjectType.MEDIA, media);
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.source()).isEqualTo("REFERENCES");
        // 播放/查看链不受下载开关影响
        assertThat(service.denyReason(ObjectType.MEDIA, media, "1.1.1.1", Instant.now(), false)).isNull();
    }

    @Test
    void mediaDownloadTakesTheStricterReferencingContent() {
        UUID media = UUID.randomUUID();
        UUID allowedContent = UUID.randomUUID();
        UUID deniedContent = UUID.randomUUID();
        UUID openFolder = UUID.randomUUID();
        UUID closedFolder = UUID.randomUUID();
        UUID site = UUID.randomUUID();

        when(resolver.parent(ObjectType.MEDIA, media)).thenReturn(new PermissionableResolver.Ref(ObjectType.SITE, site));
        when(resolver.parent(ObjectType.SITE, site)).thenReturn(null);
        when(resolver.parent(ObjectType.CONTENT, allowedContent))
            .thenReturn(new PermissionableResolver.Ref(ObjectType.FOLDER, openFolder));
        when(resolver.parent(ObjectType.CONTENT, deniedContent))
            .thenReturn(new PermissionableResolver.Ref(ObjectType.FOLDER, closedFolder));
        when(resolver.parent(ObjectType.FOLDER, openFolder)).thenReturn(null);
        when(resolver.parent(ObjectType.FOLDER, closedFolder)).thenReturn(null);
        ProgramAccessPolicyEntity open = policy(openFolder, "");
        open.setDownloadEnabled(true);
        ProgramAccessPolicyEntity closed = policy(closedFolder, "");
        closed.setDownloadEnabled(false);
        when(policies.findByObjectTypeAndObjectId(ObjectType.FOLDER, openFolder)).thenReturn(Optional.of(open));
        when(policies.findByObjectTypeAndObjectId(ObjectType.FOLDER, closedFolder)).thenReturn(Optional.of(closed));
        service.setMediaScope(assetId -> List.of(allowedContent, deniedContent));

        assertThat(service.resolveDownload(ObjectType.MEDIA, media).allowed()).isFalse();
    }

    @Test
    void mediaDownloadDoesNotScanReferencesWhenOwnChainAlreadyDenies() {
        UUID media = UUID.randomUUID();
        UUID content = UUID.randomUUID();
        UUID site = UUID.randomUUID();
        when(resolver.parent(ObjectType.MEDIA, media)).thenReturn(new PermissionableResolver.Ref(ObjectType.SITE, site));
        when(resolver.parent(ObjectType.SITE, site)).thenReturn(null);
        ProgramAccessPolicyEntity siteRow = policy(site, "");
        siteRow.setDownloadEnabled(false);
        when(policies.findByObjectTypeAndObjectId(ObjectType.SITE, site)).thenReturn(Optional.of(siteRow));
        java.util.concurrent.atomic.AtomicInteger scans = new java.util.concurrent.atomic.AtomicInteger();
        service.setMediaScope(assetId -> {
            scans.incrementAndGet();
            return List.of(content);
        });

        ProgramAccessService.DownloadDecision decision = service.resolveDownload(ObjectType.MEDIA, media);
        assertThat(decision.allowed()).isFalse();
        assertThat(scans.get()).isZero();
    }

    @Test
    void mediaInheritsCategoryPolicyThroughItsOwnContentItem() {
        UUID media = UUID.randomUUID();
        UUID mediaItem = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        ContentItemEntity item = new ContentItemEntity();
        item.setCategoryId(category);
        when(resolver.parent(ObjectType.MEDIA, media)).thenReturn(new PermissionableResolver.Ref(ObjectType.CONTENT, mediaItem));
        when(resolver.parent(ObjectType.CONTENT, mediaItem)).thenReturn(null);
        when(contents.findById(mediaItem)).thenReturn(Optional.of(item));
        when(resolver.parent(ObjectType.TAXONOMY, category)).thenReturn(null);
        ProgramAccessPolicyEntity categoryRow = policy(category, "10.0.0.0/8");
        categoryRow.setDownloadEnabled(false);
        when(policies.findByObjectTypeAndObjectId(ObjectType.TAXONOMY, category)).thenReturn(Optional.of(categoryRow));

        assertThat(service.effective(ObjectType.MEDIA, media).downloadOpinion()).isFalse();
        assertThat(service.denyReason(ObjectType.MEDIA, media, "8.8.8.8", Instant.now(), false))
            .isEqualTo(ErrorCodes.ACCESS_IP_DENIED);
        assertThat(service.denyReason(ObjectType.MEDIA, media, "10.1.2.3", Instant.now(), false)).isNull();
    }

    @Test
    void downloadSourceNamesTheLayerThatDefinedTheSwitch() {
        UUID content = UUID.randomUUID();
        UUID folder = UUID.randomUUID();
        UUID site = UUID.randomUUID();
        when(resolver.parent(ObjectType.CONTENT, content)).thenReturn(new PermissionableResolver.Ref(ObjectType.FOLDER, folder));
        when(resolver.parent(ObjectType.FOLDER, folder)).thenReturn(new PermissionableResolver.Ref(ObjectType.SITE, site));
        when(resolver.parent(ObjectType.SITE, site)).thenReturn(null);
        ProgramAccessPolicyEntity folderRow = policy(folder, "");
        folderRow.setDownloadEnabled(true);
        when(policies.findByObjectTypeAndObjectId(ObjectType.FOLDER, folder)).thenReturn(Optional.of(folderRow));

        // 只设下载开关的策略行不填 IP 维度来源，仍要能说清「继承自哪一层」
        assertThat(service.resolveDownload(ObjectType.CONTENT, content).source()).isEqualTo("FOLDER");
        assertThat(service.resolveDownload(ObjectType.CONTENT, content).allowed()).isTrue();
    }

    @Test
    void downloadSwitchDefaultsOffWhenTheWholeChainSaysNothing() {
        UUID content = UUID.randomUUID();
        when(resolver.parent(ObjectType.CONTENT, content)).thenReturn(null);
        ProgramAccessService.DownloadDecision decision = service.resolveDownload(ObjectType.CONTENT, content);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.source()).isNull();
    }

    private static ProgramAccessPolicyEntity policy(UUID objectId, String ips) {
        ProgramAccessPolicyEntity row = ProgramAccessPolicyEntity.create(objectId, ObjectType.SITE, objectId, null);
        row.setIpAllowlist(ips);
        return row;
    }
}
