// BLOCK 1: module declaration — boards and columns, built from the project's workflow statuses.
@ApplicationModule(displayName = "Boards", allowedDependencies = { "project", "workflow" })
package dev.trakr.api.board;

import org.springframework.modulith.ApplicationModule;
