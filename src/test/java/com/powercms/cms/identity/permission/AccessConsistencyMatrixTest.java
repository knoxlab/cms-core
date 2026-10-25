package com.powercms.cms.identity.permission;

import com.powercms.cms.delivery.DeliveryCachePolicy;
import com.powercms.cms.identity.entity.UserEntity;
import com.powercms.cms.identity.repo.UserRepository;
import com.powercms.common.error.ErrorCodes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 同一主体与条件在查看、下载上的判定，以及 GraphQL 复用交付服务。
 */
class AccessConsistencyMatrixTest {

    private final ObjectPermissionService permissions = mock(ObjectPermissionService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ProgramAccessService program = mock(ProgramAccessService.class);
    private final ViewerAccessService access = new ViewerAccessService(permissions, users, null, program);
    private final UUID contentId = UUID.randomUUID();
    private final UUID memberId = UUID.randomUUID();

    @AfterEach
    void clearScope() {
        AccessCacheScope.clear();
        AccessRequestContext.clear();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "anonymous-public, false, true, , true, false",
        "member-sees-public, true, true, , true, false",
        "member-granted, true, false, true, true, false",
        "member-denied, true, false, false, false, false",
        "anonymous-denied, false, false, , false, false"
    })
    void viewDecisionIsStable(String name, boolean member, boolean anonymous, String memberRight,
                              boolean expectView, boolean unused) {
        if (member) {
            UserEntity user = new UserEntity();
            user.setKind(UserEntity.KIND_FRONTEND);
            when(users.findById(memberId)).thenReturn(Optional.of(user));
        }
        when(permissions.hasAnonymousRight(ObjectType.CONTENT, contentId, ObjectType.VIEW)).thenReturn(anonymous);
        if (memberRight != null && !memberRight.isBlank()) {
            when(permissions.hasRight(memberId, ObjectType.CONTENT, contentId, ObjectType.VIEW))
                .thenReturn(Boolean.parseBoolean(memberRight));
        }
        when(program.denyReason(eq(ObjectType.CONTENT), eq(contentId), nullable(String.class), any(), eq(false)))
            .thenReturn(null);
        when(program.variesByRequest(ObjectType.CONTENT, contentId)).thenReturn(false);

        UUID viewer = member ? memberId : null;
        boolean visible = access.visible(viewer, ObjectType.CONTENT, contentId);
        boolean again = access.decide(viewer, ObjectType.CONTENT, contentId, false).allowed();
        assertThat(visible).as(name).isEqualTo(expectView);
        assertThat(again).isEqualTo(visible);
    }

    @Test
    void ipDenialAndDownloadOffAgreeAcrossViewAndDownload() {
        when(permissions.hasAnonymousRight(ObjectType.MEDIA, contentId, ObjectType.VIEW)).thenReturn(true);
        when(program.denyReason(eq(ObjectType.MEDIA), eq(contentId), nullable(String.class), any(), eq(false)))
            .thenReturn(ErrorCodes.ACCESS_IP_DENIED);
        assertThat(access.visible(null, ObjectType.MEDIA, contentId)).isFalse();
        assertThat(access.downloadable(null, ObjectType.MEDIA, contentId)).isFalse();

        when(program.denyReason(eq(ObjectType.MEDIA), eq(contentId), nullable(String.class), any(), eq(false)))
            .thenReturn(null);
        when(program.variesByRequest(ObjectType.MEDIA, contentId)).thenReturn(false);
        when(program.resolveDownload(ObjectType.MEDIA, contentId))
            .thenReturn(new ProgramAccessService.DownloadDecision(false, null));
        assertThat(access.visible(null, ObjectType.MEDIA, contentId)).isTrue();
        assertThat(access.downloadable(null, ObjectType.MEDIA, contentId)).isFalse();
    }

    @Test
    void conditionRestrictedSuccessIsNotSharedCacheable() {
        AccessCacheScope.reset();
        when(permissions.hasAnonymousRight(ObjectType.CONTENT, contentId, ObjectType.VIEW)).thenReturn(true);
        when(program.denyReason(eq(ObjectType.CONTENT), eq(contentId), nullable(String.class), any(), eq(false)))
            .thenReturn(null);
        when(program.variesByRequest(ObjectType.CONTENT, contentId)).thenReturn(true);
        assertThat(access.visible(null, ObjectType.CONTENT, contentId)).isTrue();
        assertThat(AccessCacheScope.shared()).isFalse();
        assertThat(DeliveryCachePolicy.unshared()).isEqualTo("private, no-store");
        assertThat(DeliveryCachePolicy.anonymousPublic()).doesNotContain("stale-while-revalidate");
    }

    @Test
    void decisionChainCacheHitStaysInsideThirtyMilliseconds() {
        when(permissions.hasAnonymousRight(ObjectType.CONTENT, contentId, ObjectType.VIEW)).thenReturn(true);
        when(program.denyReason(eq(ObjectType.CONTENT), eq(contentId), nullable(String.class), any(), eq(false)))
            .thenReturn(null);
        when(program.variesByRequest(ObjectType.CONTENT, contentId)).thenReturn(false);
        access.visible(null, ObjectType.CONTENT, contentId);
        long[] samples = new long[200];
        for (int i = 0; i < samples.length; i++) {
            long start = System.nanoTime();
            access.decide(null, ObjectType.CONTENT, contentId, false);
            samples[i] = System.nanoTime() - start;
        }
        java.util.Arrays.sort(samples);
        long p95 = samples[(int) Math.ceil(samples.length * 0.95) - 1];
        assertThat(p95).isLessThan(30_000_000L);
    }

    @Test
    void forgedForwardedForIsIgnoredWhenNoTrustedProxy() throws Exception {
        AccessRequestFilter filter = new AccessRequestFilter(0);
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.8");
        request.setRemoteAddr("198.51.100.4");
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) ->
            assertThat(AccessRequestContext.current().clientIp()).isEqualTo("198.51.100.4"));
    }

    @Test
    void offlinePrefixDbHitsKnownRangeAndMissesTheRest() throws Exception {
        String csv = """
            # cidr,country,province
            192.0.2.0/24,CN,GD
            """;
        PrefixGeoResolver geo = PrefixGeoResolver.load(new java.io.StringReader(csv));
        assertThat(geo.locate("192.0.2.10")).contains(new GeoResolver.Place("CN", "GD"));
        assertThat(geo.locate("198.51.100.4")).isEmpty();
        assertThat(new UnavailableGeoResolver().locate("192.0.2.10")).isEmpty();
    }
}
