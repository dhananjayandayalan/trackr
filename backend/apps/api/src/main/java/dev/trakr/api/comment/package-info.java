// BLOCK 1: module declaration — comments on issues and the people they mention.
// issue: which issue, and may the caller see or act on it (IssueService.require); project: who counts as a member
// when resolving mentions; identity: the signed-in user (controllers); shared: errors, HTML sanitising, ETags.
// The events it publishes (CommentAdded/Edited/Deleted) are records from libs/contracts.
@ApplicationModule(displayName = "Comments", allowedDependencies = { "issue", "project", "identity", "shared" })
package dev.trakr.api.comment;

import org.springframework.modulith.ApplicationModule;
