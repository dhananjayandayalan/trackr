// BLOCK 1: module declaration — the bell (phase 17): list, unread count, mark read. The notification table is
// written by the workers' notifier; this module reads it and sets read_at, nothing else.
// identity: the signed-in user; shared: cursors, errors.
@ApplicationModule(displayName = "Notifications", allowedDependencies = { "identity", "shared" })
package dev.trakr.api.notification;

import org.springframework.modulith.ApplicationModule;
