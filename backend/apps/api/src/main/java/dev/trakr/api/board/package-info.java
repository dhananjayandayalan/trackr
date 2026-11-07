// BLOCK 1: module declaration — boards and columns, built from the project's workflow statuses, and the
// board screen's read model. identity: the signed-in user (controllers); shared: errors, web helpers.
@ApplicationModule(displayName = "Boards", allowedDependencies = { "project", "workflow", "identity", "shared" })
package dev.trakr.api.board;

import org.springframework.modulith.ApplicationModule;
