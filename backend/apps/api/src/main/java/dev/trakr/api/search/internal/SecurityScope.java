package dev.trakr.api.search.internal;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import dev.trakr.api.search.internal.tql.ValueResolver;
import dev.trakr.api.workspace.WorkspaceService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the caller may see, as an Elasticsearch filter. Defence in depth: the user's TQL is compiled into
 * one query, this filter is another, and SearchService puts both side by side in a bool — so nothing
 * typed into TQL ({@code project = OTHER}, {@code OR project != MAR}) can widen it. The two never mix.
 *
 * <p>Built from Postgres on every request: when someone is removed from a project, their next search
 * no longer sees it — no index update needed.
 */
@Component
public class SecurityScope {

    /** The caller's workspaces and visible projects, worked out once per request. */
    public record Scope(UUID userId, List<UUID> workspaceIds, List<UUID> projectIds) {

        public Scope {
            workspaceIds = List.copyOf(workspaceIds);
            projectIds = List.copyOf(projectIds);
        }

        /** workspaceId in mine AND projectId in visible AND not deleted (soft-deleted issues stay out). */
        public Query filter() {
            return Query.of(q -> q.bool(b -> b
                    .filter(terms("workspaceId", workspaceIds))
                    .filter(terms("projectId", projectIds))
                    .mustNot(m -> m.term(t -> t.field("deleted").value(true)))));
        }

        private static Query terms(String field, List<UUID> ids) {
            // no ids (a user who sees no project) → an empty terms list, which matches nothing
            List<FieldValue> values = ids.stream().map(id -> FieldValue.of(id.toString())).toList();
            return Query.of(q -> q.terms(t -> t.field(field).terms(v -> v.value(values))));
        }
    }

    private final SearchQueries queries;
    private final WorkspaceService workspaces;

    SecurityScope(SearchQueries queries, WorkspaceService workspaces) {
        this.queries = queries;
        this.workspaces = workspaces;
    }

    @Transactional(readOnly = true)
    public Scope of(UUID userId) {
        return new Scope(userId, workspaces.workspaceIdsOf(userId), queries.visibleProjectIds(userId));
    }

    /** TQL name lookups confined to the scope: the Directory ValueResolver resolves names through. */
    public ValueResolver.Directory directory(Scope scope) {
        return new ValueResolver.Directory() {
            @Override
            public List<UUID> usersNamed(String nameOrEmail) {
                return queries.usersNamed(scope.workspaceIds(), nameOrEmail);
            }

            @Override
            public List<UUID> membersOf(String projectKey) {
                return queries.membersOf(scope.projectIds(), projectKey);
            }

            @Override
            public List<UUID> sprintsNamed(String name) {
                return queries.sprintsNamed(scope.projectIds(), name);
            }

            @Override
            public List<UUID> sprintsInStates(Set<String> states) {
                return queries.sprintsInStates(scope.projectIds(), states);
            }

            @Override
            public List<UUID> issuesWithKey(String key) {
                return queries.issuesWithKey(scope.projectIds(), key);
            }

            @Override
            public List<String> statusNames(String name) {
                return queries.statusNames(scope.projectIds(), name);
            }
        };
    }
}
