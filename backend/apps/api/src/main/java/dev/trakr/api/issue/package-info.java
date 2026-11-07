// BLOCK 1: module declaration — issues, their history, links, watchers, work log.
// project: access checks (ProjectAccess); workflow: which status moves are allowed; identity: the signed-in user.
// The events it publishes are records from libs/contracts, so other apps share one definition.
@ApplicationModule(displayName = "Issues", allowedDependencies = { "project", "workflow", "identity", "shared" })
package dev.trakr.api.issue;

import org.springframework.modulith.ApplicationModule;
