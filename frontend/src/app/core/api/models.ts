/**
 * Typed mirror of the FROZEN API contract (docs/api/openapi-v1.yaml, v1.0.1).
 *
 * The OpenAPI document is authoritative — these types track it 1:1. Change them
 * only when a change-request (docs/change-requests/) lands and the contract bumps.
 * Field-level traps to respect (from docs/integration/INTEGRATION-NOTES.md) are
 * called out inline.
 */

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
  /** Not in the contract schema, but harmless if the backend omits it. */
  requiresApproval?: boolean;
  owner?: string;
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
