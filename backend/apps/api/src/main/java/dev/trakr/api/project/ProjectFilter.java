package dev.trakr.api.project;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The project directory's filters; null = no filter. {@code q} matches name or key. */
public record ProjectFilter(@Nullable String q, @Nullable Template template, @Nullable UUID leadId, boolean starred) {
}
