// BLOCK 1: module declaration — burndown, velocity, sprint report and breakdowns (phase 17). It owns no tables:
// it reads the workers' read models (sprint_day_snapshot, sprint_report) and the issue tables with jOOQ.
// project: who may see a project's reports; identity: the signed-in user; shared: errors.
@ApplicationModule(displayName = "Reporting", allowedDependencies = { "project", "identity", "shared" })
package dev.trakr.api.reporting;

import org.springframework.modulith.ApplicationModule;
