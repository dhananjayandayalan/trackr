package dev.trakr.api.sprint;

import com.fasterxml.jackson.annotation.JsonValue;

/** planned → active → closed, never backwards (Sprint.java enforces it). */
public enum SprintState {
    PLANNED("planned"),
    ACTIVE("active"),
    CLOSED("closed");

    private final String value;

    SprintState(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    public static SprintState fromValue(String value) {
        for (SprintState candidate : values()) {
            if (candidate.value.equals(value)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("unknown sprint state: " + value);
    }
}
