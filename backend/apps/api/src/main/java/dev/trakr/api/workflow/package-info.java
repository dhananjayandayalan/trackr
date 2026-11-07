// BLOCK 1: module declaration — statuses and transitions; seeded when a project is created.
// shared: TransitionNotAllowed is a TrakrException.
@ApplicationModule(displayName = "Workflow", allowedDependencies = { "project", "shared" })
package dev.trakr.api.workflow;

import org.springframework.modulith.ApplicationModule;
