package dev.trakr.api.project.web;

import dev.trakr.api.project.Access;
import dev.trakr.api.project.ProjectPatch;
import dev.trakr.api.shared.web.TrakrException;
import dev.trakr.api.shared.web.TrakrException.FieldMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

/**
 * PATCH /projects/{key} as JSON merge patch. A record can't tell {"description": null} ("clear it")
 * from {} ("leave it"): both give null. So the controller binds the body to a Map — absent key vs
 * key with null value — and {@link #parse} turns it into a typed ProjectPatch. The record itself
 * only documents the shape for OpenAPI.
 */
record ProjectPatchRequest(String name, @Nullable String description, UUID leadId, Access access, String color) {

    static final String COLORS = "^(pink|cyan|lime|yellow|orange|violet)$";
    private static final Set<String> FIELDS = Set.of("name", "description", "leadId", "access", "color");

    static ProjectPatch parse(Map<String, @Nullable Object> body) {
        List<FieldMessage> errors = new ArrayList<>();
        body.keySet().stream().filter(field -> !FIELDS.contains(field))
                .forEach(field -> errors.add(new FieldMessage(field, "is not a field you can change")));

        String name = text(body, "name", 80, errors);
        if (name != null && name.isBlank()) {
            errors.add(new FieldMessage("name", "must not be blank"));
        }
        boolean descriptionPresent = body.containsKey("description");
        String description = descriptionPresent && body.get("description") != null
                ? text(body, "description", 2000, errors)
                : null;
        String lead = text(body, "leadId", 36, errors);
        UUID leadId = lead == null ? null : uuid(lead, errors);
        String accessValue = text(body, "access", 10, errors);
        Access access = accessValue == null ? null : access(accessValue, errors);
        String color = text(body, "color", 10, errors);
        if (color != null && !color.matches(COLORS)) {
            errors.add(new FieldMessage("color", "must be a VOLT colour token"));
        }

        if (!errors.isEmpty()) {
            throw new TrakrException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The patch has invalid fields.", errors);
        }
        return new ProjectPatch(name == null ? null : name.strip(), descriptionPresent, description, leadId, access, color);
    }

    // null when absent; present fields must be strings (only description may be null, handled above)
    private static @Nullable String text(Map<String, @Nullable Object> body, String field, int maxLength,
            List<FieldMessage> errors) {
        if (!body.containsKey(field)) {
            return null;
        }
        if (!(body.get(field) instanceof String value)) {
            errors.add(new FieldMessage(field, body.get(field) == null ? "cannot be cleared" : "must be a string"));
            return null;
        }
        if (value.length() > maxLength) {
            errors.add(new FieldMessage(field, "must be at most " + maxLength + " characters"));
        }
        return value;
    }

    private static @Nullable UUID uuid(String value, List<FieldMessage> errors) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            errors.add(new FieldMessage("leadId", "must be a user id"));
            return null;
        }
    }

    private static @Nullable Access access(String value, List<FieldMessage> errors) {
        try {
            return Access.fromValue(value);
        } catch (IllegalArgumentException e) {
            errors.add(new FieldMessage("access", "must be private, limited or open"));
            return null;
        }
    }
}
