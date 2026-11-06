package dev.trakr.api.workflow;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** {@code category}: todo, in_progress or done — what the status means, whatever it is called. */
public record WorkflowStatusView(UUID id, String name, String category, int position, String color,
        @Nullable Integer wipLimit) {
}
