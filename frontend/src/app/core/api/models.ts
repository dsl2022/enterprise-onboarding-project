/**
 * Typed mirror of the FROZEN API contract (docs/api/openapi-v1.yaml, v1.0.1).
 *
 * The OpenAPI document is authoritative — these types track it 1:1. Change them
 * only when a change-request (docs/change-requests/) lands and the contract bumps.
 * Field-level traps to respect (from docs/integration/INTEGRATION-NOTES.md) are
 * called out inline.
 */
import type { components } from './contract';

// ---- Enums (string unions + value arrays for filters) ----------------------

export type Role =
  | 'APPLICATION_OWNER'
  | 'SSO_OPERATIONS'
  | 'ADMIN'
  | 'AUDITOR'
  | 'READ_ONLY'
  | 'SUPER_ADMIN';

export type OnboardingStatus =
  | 'DRAFT'
  | 'SUBMITTED'
  | 'UNDER_REVIEW'
  | 'CHANGES_REQUESTED'
  | 'REJECTED'
  | 'APPROVED'
  | 'PROVISIONING'
  | 'ACTIVE';

export type AccessStatus =
  | 'SUBMITTED'
  | 'UNDER_REVIEW'
  | 'CHANGES_REQUESTED'
  | 'REJECTED'
  | 'APPROVED'
  | 'PROVISIONING'
  | 'GRANTED';

export type RequestStatus = OnboardingStatus | AccessStatus;

export type Decision = 'APPROVE' | 'REJECT' | 'REQUEST_CHANGES';

export type ResourceType = 'AWS' | 'WORKDAY' | 'ROLE' | 'TEAM';

export type Risk = 'LOW' | 'MEDIUM' | 'HIGH';

export type AccessRequestKind = 'grant' | 'removal';

export type GrantType =
  | 'authorization_code'
  | 'client_credentials'
  | 'implicit'
  | 'device_code';

export type RequestKind = 'onboarding' | 'access';

export const ROLES: readonly Role[] = [
  'APPLICATION_OWNER',
  'SSO_OPERATIONS',
  'ADMIN',
  'AUDITOR',
  'READ_ONLY',
  'SUPER_ADMIN',
];

export const RESOURCE_TYPES: readonly ResourceType[] = ['AWS', 'WORKDAY', 'ROLE', 'TEAM'];
export const RISKS: readonly Risk[] = ['LOW', 'MEDIUM', 'HIGH'];
export const GRANT_TYPES: readonly GrantType[] = [
  'authorization_code',
  'client_credentials',
  'implicit',
  'device_code',
];

// ---- Error envelope (RFC-7807) ---------------------------------------------

export interface Problem {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  correlationId?: string;
}

// ---- Identity / impersonation ----------------------------------------------

export interface Me {
  id: string;
  name: string;
  email: string;
  /** Display role only (most-privileged held role). NEVER the basis for auth. */
  role: Role;
  /** All held app roles; authorization is the union of these. */
  roles: Role[];
  /** Informational access-governance/team group; not the RBAC source. */
  group: string | null;
  isSuperAdmin: boolean;
  impersonating: { role: Role } | null;
}

export interface ImpersonationRequest {
  role: Role;
  /** Reserved for future user-level impersonation; ignored in v1. */
  user?: string | null;
}

// ---- Applications (onboarding) ---------------------------------------------

export interface Application {
  id: string;
  name: string;
  env: string;
  description: string;
  status: OnboardingStatus;
  owner: string;
  team: string[];
  grants: GrantType[];
  scopes: string[];
  /** Read projection of create-time `uris` (deliberate contract rename). */
  redirectUris: string[];
  group: string;
  /** Set after provisioning; `sim-*` while provisioning is simulated (4b gate). */
  clientId: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ApplicationCreate {
  name: string;
  env: string;
  description?: string;
  grants?: GrantType[];
  scopes?: string[];
  /** Projects to `redirectUris` on read. */
  uris?: string[];
  group?: string;
  team?: string[];
}

/** `name`/`env` are immutable; PATCH merges the rest. */
export interface ApplicationPatch {
  description?: string;
  grants?: GrantType[];
  scopes?: string[];
  uris?: string[];
  group?: string;
  team?: string[];
}

export interface DecisionBody {
  decision: Decision;
  reason?: string;
}

export interface TimelineEntry {
  id: string;
  status: string;
  actor: string;
  reason: string | null;
  at: string;
}

// ---- Access governance ------------------------------------------------------

export interface CatalogResource {
  id: string;
  name: string;
  type: ResourceType;
  risk: Risk;
  description: string;
  /** Entra group an approval grants membership to. */
  mappedGroup: string;
}

export interface AccessRequest {
  id: string;
  resourceId: string;
  resourceName: string;
  kind: AccessRequestKind;
  status: AccessStatus;
  requester: string;
  justification: string;
  /** ISO-8601 duration or null. Informational only — v1 does NOT auto-expire. */
  duration: string | null;
  approver: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface AccessRequestCreate {
  resourceId: string;
  justification: string;
  duration?: string | null;
}

/** SOURCE OF TRUTH for "currently held" — not any request's status. */
export interface MyAccessItem {
  resourceId: string;
  resourceName: string;
  grantedAt: string;
  requestId: string;
}

// ---- Review queue (unified across both request types) ----------------------

export interface ReviewItem {
  id: string;
  kind: RequestKind;
  title: string;
  requester: string;
  submittedAt: string;
}

// ---- Teams ------------------------------------------------------------------

export interface Team {
  id: string;
  name: string;
  description: string;
  memberCount: number;
}

export interface TeamCreate {
  name: string;
  description?: string;
}

export interface TeamMember {
  userId: string;
  name: string;
  addedAt: string;
}

export interface TeamMemberAdd {
  userId: string;
}

// ---- Audit ------------------------------------------------------------------

export interface AuditEvent {
  id: string;
  seq: number;
  /** Identity — the real Super Admin even while impersonating. */
  actor: string;
  effectiveRole: Role;
  action: string;
  resourceType: string;
  resourceId: string;
  at: string;
  prevHash: string;
  hash: string;
  detail: Record<string, unknown>;
}

export interface AuditVerifyResult {
  valid: boolean;
  checkedThrough: number;
  brokenAt: number | null;
}

// ---- Notifications ----------------------------------------------------------

export interface Notification {
  id: string;
  type: string;
  title: string;
  body: string;
  resourceRef: string | null;
  read: boolean;
  createdAt: string;
}

export interface NotificationFeed {
  items: Notification[];
  unreadCount: number;
  nextCursor: string | null;
}

// ---- Assistant (stubbed → 501 in v1 core) ----------------------------------

export interface AssistantChatRequest {
  message: string;
  context?: Record<string, unknown>;
}

export interface ProposedAction {
  id: string;
  tool: 'draftDescription' | 'validateRedirectUris' | 'recommendScopes' | 'checkGroupOwnership';
  args: Record<string, unknown>;
  requiresApproval: boolean;
}

export interface AssistantChatResponse {
  reply: string;
  proposedActions: ProposedAction[];
}

// ---- Cursor-paginated page wrappers ----------------------------------------

export interface Page<T> {
  items: T[];
  nextCursor: string | null;
}

export type ApplicationPage = Page<Application>;
export type CatalogPage = Page<CatalogResource>;
export type AccessRequestPage = Page<AccessRequest>;
export type ReviewQueuePage = Page<ReviewItem>;
export type AuditPage = Page<AuditEvent>;

// ===========================================================================
// Contract conformance — compile-time drift guard.
//
// The hand-authored types above are the ergonomic surface; `contract.ts` is
// generated from the frozen OpenAPI by `npm run gen:contract` (and CI fails on
// any diff). These assertions bridge the two: if a hand type names a field the
// contract no longer has, or types it incompatibly, or an enum value drifts,
// the project STOPS COMPILING. So `models.ts` cannot silently diverge from the
// contract even though it's hand-written. Type-only — zero runtime/bundle cost.
//
// Note: generated schema props are all optional (the contract marks few as
// `required`), so the helpers compare via Partial<> to ignore the required-vs-
// optional axis while still catching renamed/removed fields and type changes.
// ===========================================================================

type Schemas = components['schemas'];

/** Fails (resolves to the offending keys) if M has a field absent from G. */
type ExtraKeys<M, G> = Exclude<keyof M, keyof G>;
/** True when every shared field of M is assignable to the contract's type. */
type ValuesConform<M, G> = M extends Partial<{ [K in keyof M & keyof G]: G[K] }> ? true : false;
/** M conforms to contract schema G: no extra keys AND compatible value types. */
type Conforms<M, G> = [ExtraKeys<M, G>] extends [never] ? ValuesConform<M, G> : false;
/** Bidirectional equality, for enums (catches added/removed enum values). */
type Equal<A, B> = [A] extends [B] ? ([B] extends [A] ? true : false) : false;
/** Compiles only for `true`; anything else is a contract-drift error. */
type Assert<T extends true> = T;

/* eslint-disable @typescript-eslint/no-unused-vars */
// Enums
type _Role = Assert<Equal<Role, Schemas['Role']>>;
type _OnboardingStatus = Assert<Equal<OnboardingStatus, Schemas['OnboardingStatus']>>;
type _AccessStatus = Assert<Equal<AccessStatus, Schemas['AccessStatus']>>;
type _Decision = Assert<Equal<Decision, Schemas['Decision']>>;
type _ResourceType = Assert<Equal<ResourceType, Schemas['ResourceType']>>;
type _Risk = Assert<Equal<Risk, Schemas['Risk']>>;
type _AccessRequestKind = Assert<Equal<AccessRequestKind, Schemas['AccessRequestKind']>>;
type _GrantType = Assert<Equal<GrantType, Schemas['GrantType']>>;

// Objects
type _Problem = Assert<Conforms<Problem, Schemas['Problem']>>;
type _Me = Assert<Conforms<Me, Schemas['Me']>>;
type _ImpersonationRequest = Assert<Conforms<ImpersonationRequest, Schemas['ImpersonationRequest']>>;
type _Application = Assert<Conforms<Application, Schemas['Application']>>;
type _ApplicationCreate = Assert<Conforms<ApplicationCreate, Schemas['ApplicationCreate']>>;
type _ApplicationPatch = Assert<Conforms<ApplicationPatch, Schemas['ApplicationPatch']>>;
type _DecisionBody = Assert<Conforms<DecisionBody, Schemas['DecisionBody']>>;
type _TimelineEntry = Assert<Conforms<TimelineEntry, Schemas['TimelineEntry']>>;
type _CatalogResource = Assert<Conforms<CatalogResource, Schemas['CatalogResource']>>;
type _AccessRequest = Assert<Conforms<AccessRequest, Schemas['AccessRequest']>>;
type _AccessRequestCreate = Assert<Conforms<AccessRequestCreate, Schemas['AccessRequestCreate']>>;
type _MyAccessItem = Assert<Conforms<MyAccessItem, Schemas['MyAccessItem']>>;
type _Team = Assert<Conforms<Team, Schemas['Team']>>;
type _TeamCreate = Assert<Conforms<TeamCreate, Schemas['TeamCreate']>>;
type _TeamMember = Assert<Conforms<TeamMember, Schemas['TeamMember']>>;
type _TeamMemberAdd = Assert<Conforms<TeamMemberAdd, Schemas['TeamMemberAdd']>>;
type _ReviewItem = Assert<Conforms<ReviewItem, Schemas['ReviewItem']>>;
type _AuditEvent = Assert<Conforms<AuditEvent, Schemas['AuditEvent']>>;
type _AuditVerifyResult = Assert<Conforms<AuditVerifyResult, Schemas['AuditVerifyResult']>>;
type _Notification = Assert<Conforms<Notification, Schemas['Notification']>>;
type _NotificationFeed = Assert<Conforms<NotificationFeed, Schemas['NotificationFeed']>>;
type _AssistantChatRequest = Assert<Conforms<AssistantChatRequest, Schemas['AssistantChatRequest']>>;
type _AssistantChatResponse = Assert<Conforms<AssistantChatResponse, Schemas['AssistantChatResponse']>>;
type _ProposedAction = Assert<Conforms<ProposedAction, Schemas['ProposedAction']>>;

// Page wrappers
type _ApplicationPage = Assert<Conforms<ApplicationPage, Schemas['ApplicationPage']>>;
type _CatalogPage = Assert<Conforms<CatalogPage, Schemas['CatalogPage']>>;
type _AccessRequestPage = Assert<Conforms<AccessRequestPage, Schemas['AccessRequestPage']>>;
type _ReviewQueuePage = Assert<Conforms<ReviewQueuePage, Schemas['ReviewQueuePage']>>;
type _AuditPage = Assert<Conforms<AuditPage, Schemas['AuditPage']>>;
/* eslint-enable @typescript-eslint/no-unused-vars */
