package dev.trakr.api.project;

import com.fasterxml.jackson.annotation.JsonValue;

/** Who sees a project: members only, every workspace member read-only, or every workspace member as a member. Stored and sent as the lowercase value (03-api §5). */
public enum Access {
    PRIVATE("private"),
    LIMITED("limited"),
    OPEN("open");

    private final String value;

    Access(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    public static Access fromValue(String value) {
        for (Access candidate : values()) {
            if (candidate.value.equals(value)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("unknown access: " + value);
    }
}
