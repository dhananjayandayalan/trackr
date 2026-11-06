package dev.trakr.api.project;

/** {@code available} false: every candidate is taken, so the user has to pick a key. */
public record KeySuggestion(String key, boolean available) {
}
