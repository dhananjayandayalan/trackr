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
