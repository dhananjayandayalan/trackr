import type { components } from './schema'

// Short names for the generated schemas the screens use. They are aliases, not copies: regenerate the
// schema and these follow.
type Schemas = components['schemas']

export type Me = Schemas['MeResponse']
export type IssueRow = Schemas['IssueRow']
export type Stats = Schemas['Stats']
export type ActivityItem = Schemas['ActivityItem']
export type UserRef = Schemas['UserRef']
export type ProjectSummary = Schemas['ProjectSummary']
export type ProjectView = Schemas['ProjectView']
export type ProjectDetails = Schemas['ProjectDetails']
export type KeySuggestion = Schemas['KeySuggestion']
export type CreateProjectRequest = Schemas['CreateProjectRequest']
export type Template = CreateProjectRequest['template']
export type Access = CreateProjectRequest['access']
export type ProjectStatus = Schemas['Status']
export type ProjectTransition = Schemas['Transition']
export type MemberView = Schemas['MemberView']
export type ProjectRole = MemberView['role']

// board and backlog (phase 14)
export type BoardView = Schemas['BoardView']
export type BoardColumn = Schemas['BoardColumn']
export type BoardCard = Schemas['BoardCard']
export type BoardSprint = Schemas['BoardSprint']
export type BoardPerson = Schemas['Person']
export type EpicTag = Schemas['EpicTag']
export type BacklogView = Schemas['BacklogView']
export type SprintList = Schemas['SprintList']
export type BacklogRow = Schemas['BacklogRow']
export type EpicSummary = Schemas['EpicSummary']
export type SprintView = Schemas['SprintView']
export type SprintReport = Schemas['SprintReport']
export type IssueDetails = Schemas['IssueDetails']
export type MoveRequest = Schemas['MoveRequest']
export type MoveResult = Schemas['MoveResult']
export type IssueTypeValue = Schemas['CreateIssueRequest']['type']

// issue detail, comments, attachments (phase 15)
export type IssueRef = Schemas['IssueRef']
export type LinkView = Schemas['LinkView']
export type LinkType = LinkView['type']
export type HistoryItem = Schemas['HistoryItem']
export type WorklogView = Schemas['WorklogView']
export type StatusView = Schemas['WorkflowStatusView']
export type IssuePriority = IssueDetails['priority']
export type CreateIssueRequest = Schemas['CreateIssueRequest']
/**
 * A merge patch (03-api §1): any subset of the editable fields. The generated record marks some fields
 * required because the Java record documents the full shape; a patch sends only what changed.
 */
export type IssuePatch = Partial<Schemas['IssuePatchRequest']>
export type Comment = Schemas['Comment']
export type CommentPage = Schemas['CursorPageComment']
export type Attachment = Schemas['Attachment']
export type UploadTicket = Schemas['UploadTicket']

// search, timeline, reports, settings, notifications (phase 17)
export type SearchHit = Schemas['SearchHit']
export type SearchPage = Schemas['SearchPage']
export type FilterView = Schemas['FilterView']
export type Completion = Schemas['Completion']
export type Completions = Schemas['Completions']
export type Suggestions = Schemas['Suggestions']
export type TimelineView = Schemas['TimelineView']
export type TimelineEpic = Schemas['TimelineEpic']
export type TimelineChild = Schemas['TimelineChild']
export type Burndown = Schemas['Burndown']
export type BurndownDay = Schemas['BurndownDay']
export type VelocityEntry = Schemas['VelocityEntry']
export type SprintReportView = Schemas['SprintReportView']
export type ReportIssue = Schemas['ReportIssue']
export type Breakdown = Schemas['Breakdown']
export type StatusSlice = Schemas['StatusSlice']
export type AssigneeSlice = Schemas['AssigneeSlice']
export type WorkflowView = Schemas['WorkflowView']
export type WorkflowTransition = Schemas['TransitionView']
export type ReplaceWorkflowRequest = Schemas['ReplaceWorkflowRequest']
export type NotificationView = Schemas['NotificationView']
export type NotificationPage = Schemas['CursorPageNotificationView']
