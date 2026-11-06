// BLOCK 1: module declaration — accounts, sign-in, sessions' principal. Calls workspace at sign-up.
@ApplicationModule(displayName = "Identity", allowedDependencies = { "workspace", "shared" })
package dev.trakr.api.identity;

import org.springframework.modulith.ApplicationModule;
