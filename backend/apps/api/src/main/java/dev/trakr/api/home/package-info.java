// BLOCK 1: module declaration — the "Your work" screen: read-only queries across issues and projects.
// It owns no tables. project: the visibility rule in SQL; issue: the shared view records; identity: the
// signed-in user; shared: cursors, errors.
@ApplicationModule(displayName = "Home", allowedDependencies = { "project", "issue", "identity", "shared" })
package dev.trakr.api.home;

import org.springframework.modulith.ApplicationModule;
