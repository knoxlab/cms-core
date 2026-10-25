package com.powercms.cms.delivery;

import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeliveryGraphqlAccessTest {

    @Test
    void hiddenContentComesBackAsNullThroughTheDeliveryService() {
        DeliveryService delivery = mock(DeliveryService.class);
        UUID siteId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        when(delivery.getViewById(siteId, contentId))
            .thenThrow(new BusinessException(ErrorCodes.NOT_FOUND, "missing"));
        DeliveryGraphqlController graphql = new DeliveryGraphqlController(delivery, 20);
        assertThat(graphql.content(siteId, contentId, null)).isNull();
        verify(delivery).getViewById(siteId, contentId);
    }
}
