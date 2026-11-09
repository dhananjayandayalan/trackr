package dev.trakr.api.search;

import dev.trakr.api.search.SearchViews.FilterPatch;
import dev.trakr.api.search.SearchViews.FilterView;
import dev.trakr.api.search.SearchViews.NewFilter;
import dev.trakr.api.search.internal.SavedFilter;
import dev.trakr.api.search.internal.SavedFilterRepository;
import dev.trakr.api.search.internal.SearchQueries;
import dev.trakr.api.search.internal.tql.FieldRegistry;
import dev.trakr.api.search.internal.tql.TqlParser;
import dev.trakr.api.shared.web.TrakrException;
import dev.trakr.api.workspace.CurrentWorkspace;
import dev.trakr.api.workspace.WorkspaceService;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saved filters (03-api §3.8): mine, plus the ones shared in my workspaces. Only the owner may change or
 * delete one; for everyone else a shared filter is read-only, and a private one doesn't exist (404).
 */
@Service
public class FilterService {

    private final SavedFilterRepository filters;
    private final SearchQueries queries;
    private final WorkspaceService workspaces;
    private final Clock clock;

    FilterService(SavedFilterRepository filters, SearchQueries queries, WorkspaceService workspaces, Clock clock) {
        this.filters = filters;
        this.queries = queries;
        this.workspaces = workspaces;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<FilterView> list(UUID userId) {
        return queries.filters(userId, workspaces.workspaceIdsOf(userId));
    }

    /** A filter whose TQL doesn't parse, or names an unknown field, isn't saved: 400 with the position. */
    @Transactional
    public FilterView create(UUID userId, NewFilter filter) {
        check(filter.tql());
        CurrentWorkspace workspace = workspaces.currentFor(userId)
                .orElseThrow(() -> new TrakrException.Forbidden("You don't belong to a workspace."));
        SavedFilter saved = filters.saveAndFlush(SavedFilter.create(workspace.id(), userId, filter.name(), filter.tql(),
                filter.starred(), filter.shared(), clock));
        return view(userId, saved.id());
    }

    @Transactional
    public FilterView patch(UUID userId, UUID id, FilterPatch patch) {
        SavedFilter filter = ownFilter(userId, id);
        if (patch.name() != null) {
            filter.rename(patch.name());
        }
        if (patch.tql() != null) {
            check(patch.tql());
            filter.changeQuery(patch.tql());
        }
        if (patch.starred() != null) {
            filter.star(patch.starred());
        }
        if (patch.shared() != null) {
            filter.share(patch.shared());
        }
        // flush before the jOOQ read below, which doesn't see changes JPA still holds in memory
        filters.flush();
        return view(userId, id);
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        filters.delete(ownFilter(userId, id));
    }

    // syntax, fields and operators — not values: "assignee = Ada" stays valid even if Ada leaves later
    private static void check(String tql) {
        FieldRegistry.validate(TqlParser.parse(tql));
    }

    private SavedFilter ownFilter(UUID userId, UUID id) {
        // first "can I see it?" (404 otherwise, like every resource), then "is it mine?" (403)
        view(userId, id);
        SavedFilter filter = filters.findById(id).orElseThrow(() -> new TrakrException.NotFound("No such filter."));
        if (!filter.ownedBy(userId)) {
            throw new TrakrException.Forbidden("Only the owner can change this filter.");
        }
        return filter;
    }

    private FilterView view(UUID userId, UUID id) {
        return queries.filter(userId, workspaces.workspaceIdsOf(userId), id)
                .orElseThrow(() -> new TrakrException.NotFound("No such filter."));
    }
}
