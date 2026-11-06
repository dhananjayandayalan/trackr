// BLOCK 1: module declaration — the tenant. Depends on no other module: everyone else depends on it.
@ApplicationModule(displayName = "Workspaces", allowedDependencies = {})
package dev.trakr.api.workspace;

import org.springframework.modulith.ApplicationModule;
