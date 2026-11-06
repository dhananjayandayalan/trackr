package dev.trakr.api.project;

import java.util.UUID;

/**
 * One card of the projects directory, read by one jOOQ query. openIssues, activeSprint and
 * progressPct join it when issues (phase 07) and sprints (phase 08) exist.
 */
public record ProjectSummary(UUID id, String key, String name, Template template, Access access, String color,
        UUID leadId, boolean starred) {
}
