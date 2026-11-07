// BLOCK 1: module declaration — sprints and the backlog screen.
// project: access checks; issue: moving a sprint's issues when it completes or is deleted;
// identity: the signed-in user; shared: errors, web helpers.
@ApplicationModule(displayName = "Sprints", allowedDependencies = { "project", "issue", "identity", "shared" })
package dev.trakr.api.sprint;

import org.springframework.modulith.ApplicationModule;
