// BLOCK 1: module declaration — statuses and transitions; seeded when a project is created.
@ApplicationModule(displayName = "Workflow", allowedDependencies = { "project" })
package dev.trakr.api.workflow;

import org.springframework.modulith.ApplicationModule;
