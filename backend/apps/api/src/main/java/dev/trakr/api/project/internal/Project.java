package dev.trakr.api.project.internal;

import dev.trakr.api.project.Access;
import dev.trakr.api.project.ProjectView;
import dev.trakr.api.project.Template;
import dev.trakr.kernel.Ids;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "project")
public class Project {

    @Id
    private UUID id;
    private UUID workspaceId;
    private String key;
    private String name;
    private @Nullable String description;
    private String template;
    private UUID leadId;
    private String access;
    private String color;
    private long issueSeq;
    private @Nullable Instant archivedAt;
    // optimistic locking: Hibernate adds "AND version = ?" to every UPDATE and bumps it; 0 rows updated
    // means someone else committed first → ObjectOptimisticLockingFailureException → 412
    @Version
    private long version;
    private Instant createdAt;
    private Instant updatedAt;

    protected Project() {
        // for JPA
    }

    public static Project create(UUID workspaceId, String key, String name, @Nullable String description,
            Template template, UUID leadId, Access access, String color, Clock clock) {
        Project project = new Project();
        project.id = Ids.newId();
        project.workspaceId = workspaceId;
        project.key = key;
        project.name = name;
        project.description = description;
        project.template = template.value();
        project.leadId = leadId;
        project.access = access.value();
        project.color = color;
        project.createdAt = clock.instant();
        project.updatedAt = project.createdAt;
        return project;
    }

    public void rename(String newName, Clock clock) {
        name = newName;
        touch(clock);
    }

    public void describe(@Nullable String newDescription, Clock clock) {
        description = newDescription;
        touch(clock);
    }

    public void changeLead(UUID newLeadId, Clock clock) {
        leadId = newLeadId;
        touch(clock);
    }

    public void changeAccess(Access newAccess, Clock clock) {
        access = newAccess.value();
        touch(clock);
    }

    public void recolor(String newColor, Clock clock) {
        color = newColor;
        touch(clock);
    }

    public void archive(Clock clock) {
        if (archivedAt == null) {
            archivedAt = clock.instant();
            touch(clock);
        }
    }

    private void touch(Clock clock) {
        updatedAt = clock.instant();
    }

    public ProjectView toView() {
        return new ProjectView(id, workspaceId, key, name, description, template(), leadId, access(), color,
                archivedAt, version, createdAt, updatedAt);
    }

    public UUID id() {
        return id;
    }

    public UUID workspaceId() {
        return workspaceId;
    }

    public String key() {
        return key;
    }

    public Template template() {
        return Template.fromValue(template);
    }

    public Access access() {
        return Access.fromValue(access);
    }

    public long version() {
        return version;
    }
}
