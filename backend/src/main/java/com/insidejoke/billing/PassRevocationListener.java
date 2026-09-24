package com.insidejoke.billing;

import com.insidejoke.auth.AccountDeletedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Revokes the passes of a deleted account, in the same transaction as the deletion. */
@Component
public class PassRevocationListener {

    private final EntitlementRepository entitlements;

    public PassRevocationListener(EntitlementRepository entitlements) {
        this.entitlements = entitlements;
    }

    @EventListener
    public void accountDeleted(AccountDeletedEvent event) {
        entitlements
                .findActive(event.userId(), event.at())
                .forEach(e -> entitlements.revoke(e.id(), event.at(), RevokeReason.ACCOUNT_DELETED, null));
    }
}
