package com.powercms.cms.delivery;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryCachePolicyTest {

    @Test
    void anonymousPublicStopsAtTheExposureWindow() {
        String header = DeliveryCachePolicy.anonymousPublic();
        assertThat(header).isEqualTo("public, max-age=60");
        assertThat(header).doesNotContain("stale-while-revalidate");
    }

    @Test
    void memberResponsesStayPrivate() {
        assertThat(DeliveryCachePolicy.memberPrivate()).isEqualTo("private, max-age=60");
    }

    @Test
    void conditionRestrictedAndPreviewResponsesAreNotStored() {
        assertThat(DeliveryCachePolicy.unshared()).isEqualTo("private, no-store");
        assertThat(DeliveryCachePolicy.preview()).isEqualTo("no-store");
    }
}
