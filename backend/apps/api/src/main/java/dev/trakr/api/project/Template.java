package dev.trakr.api.project;

import com.fasterxml.jackson.annotation.JsonValue;

/** What a new project starts with: each template seeds its own workflow and board (00-product §5). Stored and sent as the lowercase value (03-api §5). */
public enum Template {
    SCRUM("scrum"),
    KANBAN("kanban"),
    BUGS("bugs"),
    BLANK("blank");

    private final String value;

    Template(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    public static Template fromValue(String value) {
        for (Template candidate : values()) {
            if (candidate.value.equals(value)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("unknown template: " + value);
    }
}
