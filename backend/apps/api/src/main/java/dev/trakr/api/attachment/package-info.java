// BLOCK 1: module declaration — files on issues. The bytes go from the browser straight to S3 (SeaweedFS) with
// presigned URLs; this module only signs, checks and records. issue: which issue, and may the caller act on it;
// identity: the signed-in user (controllers); shared: errors, ETags.
@ApplicationModule(displayName = "Attachments", allowedDependencies = { "issue", "project", "identity", "shared" })
package dev.trakr.api.attachment;

import org.springframework.modulith.ApplicationModule;
