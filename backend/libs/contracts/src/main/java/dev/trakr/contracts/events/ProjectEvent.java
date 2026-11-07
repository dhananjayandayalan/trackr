package dev.trakr.contracts.events;

/**
 * Events on {@code trakr.projects.v1}, keyed by the project id: the project's own lifecycle and its
 * sprints. One key per project keeps "sprint started" after "sprint created" for every consumer.
 */
public sealed interface ProjectEvent extends TrakrEvent permits ProjectLifecycle, SprintEvent {
}
