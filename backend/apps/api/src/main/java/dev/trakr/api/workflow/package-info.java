// BLOCK 1: module declaration — statuses and transitions; seeded when a project is created, edited as a whole in
// project settings (phase 17). shared: TransitionNotAllowed is a TrakrException; identity: the signed-in user.
@ApplicationModule(displayName = "Workflow", allowedDependencies = { "project", "identity", "shared" })
package dev.trakr.api.workflow;

import org.springframework.modulith.ApplicationModule;
