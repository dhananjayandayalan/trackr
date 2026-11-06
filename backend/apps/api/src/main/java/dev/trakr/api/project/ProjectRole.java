package dev.trakr.api.project;

import com.fasterxml.jackson.annotation.JsonValue;

/** A member's role, weakest first, so {@link #atLeast} can compare them. Stored and sent as the lowercase value (03-api §5). */
public enum ProjectRole {
    VIEWER("viewer"),
    MEMBER("member"),
    ADMIN("admin");

    private final String value;

    ProjectRole(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    /** ADMIN.atLeast(MEMBER) is true: declaration order is the strength order. */
    public boolean atLeast(ProjectRole minimum) {
        return compareTo(minimum) >= 0;
    }

    public static ProjectRole fromValue(String value) {
        for (ProjectRole candidate : values()) {
            if (candidate.value.equals(value)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("unknown project role: " + value);
    }
}
