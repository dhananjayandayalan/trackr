# Phase 15 · Issue detail, create issue, comments, attachments

**Goal:** the issue page/side panel and the Create Issue dialog from the canvas, backed by new `comment` and `attachment` modules; uploads go straight from the browser to S3 (SeaweedFS) with presigned URLs.
**Spec:** 00-product §3.7–3.8, §4.9; 02-data-model (`comment`, `comment_mention`, `attachment`); 03-api §3.4–3.6; 09-security §4. **Time:** 6 days.

## Concepts you'll learn

- **HTML sanitisation with an allow-list** — never trust rich text.
- **Presigned URLs** — temporary, signed permission to PUT/GET one object, so file bytes never pass through your api.
- **CORS for object storage** — why the browser needs the bucket to allow your origin.
- **Merge patch from the UI** — sending only what changed, with `If-Match`.
- **Popover API + anchor positioning** for inline field editors.
- **Side panel routing** — search-param driven panel over a mounted page.

## Backend files

1. `V011__comments_attachments.sql`
2. `comment/package-info.java`, `comment/internal/Comment.java`, `CommentMention.java`, repositories, `comment/CommentService.java`, `comment/web/CommentController.java`, payloads `CommentAdded/Edited/Deleted` in contracts
3. `shared/web/HtmlSanitizer.java` (OWASP Java HTML Sanitizer policy from 03-api §3.5) + `HtmlSanitizerTest.java`
4. `comment/internal/MentionExtractor.java` — reads `span[data-mention-id]` from sanitised HTML → user ids, keeps only project members
5. Catalog/build: AWS SDK v2 `s3` (BOM), OWASP sanitizer
6. `attachment/internal/S3Config.java` — `S3Client` + `S3Presigner` with `endpointOverride(trakr.s3.endpoint)`, `forcePathStyle(true)`, static credentials from env, region `us-east-1`
7. `attachment/internal/BucketInitializer.java` — `ApplicationRunner`: create bucket if missing, apply CORS rules
8. `attachment/AttachmentService.java` (`presignUpload`, `confirm`, `presignDownload`, `delete`) + `attachment/web/AttachmentController.java`
9. Pending uploads in Redis: `upload:{uploadId}` → `{issueId, objectKey, fileName, contentType, sizeBytes, userId}` TTL 10 min

### `AttachmentService.presignUpload`

| #   | Cue                                   | What                                                                                                                                                          |
| --- | ------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | `// BLOCK 1: validate`                | member of project; size ≤ 25 MB; content type in allow-list; file name sanitised (strip path separators, limit 120 chars)                                     |
| 2   | `// BLOCK 2: object key`              | `ws/{workspaceId}/issues/{issueId}/{uuidv7}/{safeFileName}`                                                                                                   |
| 3   | `// BLOCK 3: presign PUT`             | `presigner.presignPutObject(r -> r.signatureDuration(Duration.ofMinutes(5)).putObjectRequest(p -> p.bucket(b).key(key).contentType(ct).contentLength(size)))` |
| 4   | `// BLOCK 4: remember pending upload` | Redis hash with TTL; return `{uploadId, url, headers}`                                                                                                        |

`confirm(uploadId)`: load pending → `headObject` to verify the object exists and its size matches → insert `attachment` → publish `AttachmentAdded`.
**Ask yourself:** what would an attacker gain if `confirm` trusted the size sent by the browser?

## Frontend files

10. `src/features/issue/queries.ts`, `mutations.ts` (patch with `If-Match`, transition, watch, star, link, worklog)
11. `src/features/issue/IssuePanel.tsx` (side panel, `?issue=` param, view-transition name) and `IssuePage.tsx` (full page) sharing `IssueView.tsx`
12. `IssueHeader.tsx` (breadcrumb, watch, copy link, close) · `StatusButton.tsx` (Menu with allowed transitions) · `DetailsPanel.tsx` (each field is an inline editor in a popover) · `Subtasks.tsx` · `Links.tsx` · `TimeTracking.tsx`
13. `Activity.tsx` with tabs Comments / History / Work log; `CommentComposer.tsx` (textarea with `field-sizing: content`; mention picker via popover anchored to the caret position — v1 can anchor to the textarea), `CommentItem.tsx` rendering sanitised HTML through `SafeHtml`
14. `Attachments.tsx` + `useUpload.ts` (presign → `fetch(url, { method: 'PUT', body: file, headers })` with progress via `XMLHttpRequest` `upload.onprogress` — explain why `fetch` can't report upload progress yet in all browsers → confirm)
15. `src/features/issue/CreateIssueDialog.tsx` — `<dialog>` opened by the `c` shortcut and the top-nav Create button; form action; type radio group; "Create another" switch; on success: toast with link + optimistic insert into board/backlog caches
16. Description area renders `description_html` read-only for now (collaborative editing replaces it in phase 16)

### `StatusButton`

| #   | What                                                                                                                                                                                   |
| --- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | `<button popovertarget="status-menu" style={{ anchorName: '--status' }}>` showing the current status lozenge                                                                           |
| 2   | `<div id="status-menu" popover="auto" className={styles.menu}>` with `position-anchor: --status; position-area: block-end span-inline-end; position-try-fallbacks: flip-block;` in CSS |
| 3   | items from `GET /issues/{key}/transitions` (prefetched with the issue)                                                                                                                 |
| 4   | selecting → optimistic transition mutation, close popover (`hidePopover()`)                                                                                                            |

## Tests

- Backend: `HtmlSanitizerTest` (script tags, `javascript:` hrefs, event attributes removed; mentions kept), `CommentServiceIT` (mentions only for members; edit by non-author → 403), `AttachmentIT` (presign → real PUT with `HttpClient` to SeaweedFS container → confirm → download redirect works; oversize rejected).
- Frontend: `CreateIssueDialog.test.tsx` (required summary, server errors on fields, create another keeps project/type), `StatusButton.test.tsx` (only allowed transitions listed), `useUpload.test.ts` (MSW).

## Done when

- From the board: click a card → side panel slides in (view transition) → change status, assignee, points inline → add a comment with a mention → upload a screenshot → close → the board reflects every change.
- `c` opens Create Issue from any screen.

Commit: `phase-15: issue detail, create, comments, attachments`.
