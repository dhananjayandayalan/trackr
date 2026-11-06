package dev.trakr.api.project;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** What ProjectService.create needs; {@code leadId} null = the creator leads. */
public record NewProject(String name, String key, Template template, @Nullable UUID leadId, Access access,
        @Nullable String description, String color, List<String> invite) {
}
