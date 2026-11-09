package dev.trakr.api.project;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One card of the projects directory (03-api §3.2), read by one jOOQ query. {@code openIssues}: live issues
 * not in a done status; {@code progressPct}: done issues as a share of all live issues, 0–100;
 * {@code activeSprint}: its name, null when none is running (always null for Kanban and bug trackers);
 * {@code lastVisitedAt}: when the caller last opened it, for "Recent projects" on Home.
 */
public record ProjectSummary(UUID id, String key, String name, Template template, Access access, String color,
        Lead lead, boolean starred, int openIssues, int progressPct, @Nullable String activeSprint,
        @Nullable Instant lastVisitedAt) {

    /** The project lead as the card shows them. */
    public record Lead(UUID id, String displayName, String initials, String avatarColor) {
    }
}
