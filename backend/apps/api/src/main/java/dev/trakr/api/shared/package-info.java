// BLOCK 1: module declaration — cross-cutting config, web and security code. OPEN: other modules may
// use its sub-packages (shared.web, shared.config); in a normal module those would be internal.
// It depends on no module, so nothing here may import identity, project, …
@ApplicationModule(displayName = "Shared", type = ApplicationModule.Type.OPEN, allowedDependencies = {})
package dev.trakr.api.shared;

import org.springframework.modulith.ApplicationModule;
