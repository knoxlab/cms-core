package com.powercms.cms.delivery;

import com.powercms.cms.identity.permission.AccessControlChangedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class AccessInvalidationListener {

    private final AccessInvalidationService invalidation;

    public AccessInvalidationListener(AccessInvalidationService invalidation) {
        this.invalidation = invalidation;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccessChanged(AccessControlChangedEvent event) {
        invalidation.apply(event);
    }
}
