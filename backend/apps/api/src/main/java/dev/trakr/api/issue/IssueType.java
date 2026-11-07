package dev.trakr.api.issue;

import com.fasterxml.jackson.annotation.JsonValue;

/** Stored and sent as the lowercase value; Jackson also reads it back through {@code @JsonValue}. */
public enum IssueType {
    STORY("story"),
    BUG("bug"),
    TASK("task"),
    EPIC("epic"),
    SUBTASK("subtask");

    private final String value;

    IssueType(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    public static IssueType fromValue(String value) {
        for (IssueType candidate : values()) {
            if (candidate.value.equals(value)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("unknown issue type: " + value);
    }
}
