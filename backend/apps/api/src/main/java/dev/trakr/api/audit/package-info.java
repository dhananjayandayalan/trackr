// BLOCK 1: module declaration (phase 18, 09-security §9) — the audit log: who changed a project's members or its
// workflow, from what to what. Issue changes are not here: issue_event is their history already.
// project and workflow: the in-process events it listens to.
@ApplicationModule(displayName = "Audit", allowedDependencies = { "project", "workflow" })
package dev.trakr.api.audit;

import org.springframework.modulith.ApplicationModule;
