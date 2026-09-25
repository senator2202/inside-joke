package com.insidejoke.auth;

import com.insidejoke.common.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * APP_ADMIN_EMAILS only changes with a restart, so on startup the stored roles are brought in line with it: addresses
 * no longer listed become hosts, listed ones become admins. The profile and the admin's user list then show the truth.
 */
@Component
public class AdminRoleSyncListener {

    private static final Logger log = LoggerFactory.getLogger(AdminRoleSyncListener.class);

    private final UserRepository users;
    private final AppProperties props;

    public AdminRoleSyncListener(UserRepository users, AppProperties props) {
        this.users = users;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncRoles() {
        int changed = users.syncAdminRoles(props.adminEmails());
        if (changed > 0) {
            log.info("Admin roles brought in line with APP_ADMIN_EMAILS: {} account(s) changed", changed);
        }
    }
}
