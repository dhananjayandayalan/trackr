package dev.trakr.api.issue;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Stored and sent as the lowercase value. {@code weight} sorts them (highest = 5 … lowest = 1);
 * sorting the strings would put "high" before "highest" and "low" before "medium".
 */
public enum Priority {
    HIGHEST("highest", 5),
    HIGH("high", 4),
    MEDIUM("medium", 3),
    LOW("low", 2),
    LOWEST("lowest", 1);

    private final String value;
    private final int weight;

    Priority(String value, int weight) {
        this.value = value;
        this.weight = weight;
    }

    @JsonValue
    public String value() {
        return value;
    }

    public int weight() {
        return weight;
    }

    public static Priority fromValue(String value) {
        for (Priority candidate : values()) {
            if (candidate.value.equals(value)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("unknown priority: " + value);
    }
}
