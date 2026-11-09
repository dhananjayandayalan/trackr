// BLOCK 1: module declaration — TQL search over the Elasticsearch read model, autocomplete, quick search,
// CSV export and saved filters (05-search §3–4). It reads the index; workers write it.
// project: the visibility rule (ProjectVisibility) for the security filter; workspace: the caller's
// workspaces; issue: the issue type and priority values, UserRef; identity: the signed-in user; shared: errors.
@ApplicationModule(displayName = "Search",
        allowedDependencies = { "project", "workspace", "issue", "identity", "shared" })
package dev.trakr.api.search;

import org.springframework.modulith.ApplicationModule;
