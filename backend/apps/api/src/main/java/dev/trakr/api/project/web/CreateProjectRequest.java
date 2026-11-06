package dev.trakr.api.project.web;

import dev.trakr.api.project.Access;
import dev.trakr.api.project.NewProject;
import dev.trakr.api.project.Template;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

record CreateProjectRequest(
        @NotBlank @Size(max = 80) String name,
        @NotNull @Pattern(regexp = "^[A-Z]{2,6}$", message = "must be 2 to 6 capital letters") String key,
        @NotNull Template template,
        @Nullable UUID leadId,
        @NotNull Access access,
        @Nullable @Size(max = 2000) String description,
        @NotNull @Pattern(regexp = ProjectPatchRequest.COLORS, message = "must be a VOLT colour token") String color,
        @Nullable @Size(max = 50) List<@NotBlank @Email String> invite) {

    NewProject toCommand() {
        return new NewProject(name.strip(), key, template, leadId, access, description, color,
                invite == null ? List.of() : invite);
    }
}
