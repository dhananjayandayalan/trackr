package dev.trakr.api.issue;

import com.fasterxml.jackson.annotation.JsonValue;

/** One stored row, two readings: "MAR-1 blocks MAR-2" is also "MAR-2 is blocked by MAR-1". */
public enum LinkType {
    BLOCKS("blocks", "blocks", "is blocked by"),
    RELATES("relates", "relates to", "relates to"),
    DUPLICATES("duplicates", "duplicates", "is duplicated by");

    private final String value;
    private final String outward;
    private final String inward;

    LinkType(String value, String outward, String inward) {
        this.value = value;
        this.outward = outward;
        this.inward = inward;
    }

    @JsonValue
    public String value() {
        return value;
    }

    /** How the link reads from the issue you're looking at: the source sees outward, the target inward. */
    public String label(boolean fromSource) {
        return fromSource ? outward : inward;
    }

    public static LinkType fromValue(String value) {
        for (LinkType candidate : values()) {
            if (candidate.value.equals(value)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("unknown link type: " + value);
    }
}
