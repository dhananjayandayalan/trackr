// BLOCK 1: module declaration — projects, members, access. Must not know workflow or board:
// they depend on project (they listen to ProjectCreated), so a dependency back would be a cycle.
@ApplicationModule(displayName = "Projects", allowedDependencies = { "workspace", "identity", "shared" })
package dev.trakr.api.project;

import org.springframework.modulith.ApplicationModule;
