# RBAC — role → permission matrix + state machines (v1, draft for freeze)

Roles are **bundles of permissions**, derived from the Entra **group-membership claim** in the ID
token (group GUID → role). Checks at the **service layer** are always **permission** checks (never role
checks), plus **ABAC ownership** (owners act only on their own resources) and **separation of duties**
(the requester of a request can never be its approver). The frontend only hides UI; the server is
authoritative.

## Roles ↔ Entra groups
| Role | Entra group (seed) |
|---|---|
| `APPLICATION_OWNER` | App-Owners |
| `SSO_OPERATIONS` | SSO-Operations |
| `ADMIN` | Admins |
| `AUDITOR` | Auditors |
| `READ_ONLY` | Read-Only |
| `SUPER_ADMIN` | Super-Admins |

## Permission matrix
✔ = allowed · ✔(own) = only resources the principal owns · — = denied. `SUPER_ADMIN` = everything,
**plus** impersonation; while impersonating, the effective permissions are the impersonated role's, but
identity + audit stay Super Admin.

| Permission | APP_OWNER | SSO_OPS | ADMIN | AUDITOR | READ_ONLY | SUPER_ADMIN |
|---|:--:|:--:|:--:|:--:|:--:|:--:|
| `app.read` | ✔(own) | ✔ | ✔ | ✔ | ✔ | ✔ |
| `app.create` / `app.update` / `app.submit` | ✔(own) | — | ✔ | — | — | ✔ |
| `app.decide` (approve/reject/changes) | — | ✔ | ✔ | — | — | ✔ |
| `app.provision` (triggered by approve) | — | ✔ | ✔ | — | — | ✔ |
| `catalog.read` | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ |
| `access.request` | ✔ | ✔ | ✔ | — | — | ✔ |
| `access.read` | ✔(own) | ✔ | ✔ | ✔ | ✔(own) | ✔ |
| `access.decide` | — | ✔ | ✔ | — | — | ✔ |
| `myaccess.read` / `myaccess.removal.request` | ✔ | ✔ | ✔ | — | ✔/— | ✔ |
| `review.read` (queue) | — | ✔ | ✔ | ✔ | — | ✔ |
| `team.read` | ✔(own) | ✔ | ✔ | ✔ | ✔ | ✔ |
| `team.manage` (create / add / remove member) | ✔(own) | — | ✔ | — | — | ✔ |
| `secret.rotate` (registry) | — | ✔ | ✔ | — | — | ✔ |
| `audit.read` | — | ✔ | ✔ | ✔ | — | ✔ |
| `notifications.read` (own feed) | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ |
| `impersonate` | — | — | — | — | — | ✔ |
| `assistant.use` (wizard) | ✔ | ✔ | ✔ | — | — | ✔ |

**Cross-cutting rules**
- **Separation of duties:** `*.decide` is rejected (403) if the principal is the request's requester
  or `submittedBy`, regardless of role.
- **ABAC ownership:** `✔(own)` permissions check the resource's owner/team against the principal.
- **Impersonation audit:** every action while impersonating is written to `audit_events` with
  `actor = <super admin>` and `effectiveRole = <impersonated role>`.

## State machines (one timeline component drives both)
**Onboarding (application):**
```
DRAFT ──submit──▶ SUBMITTED ──▶ UNDER_REVIEW ──decision──▶ ┌─ REQUEST_CHANGES ─▶ CHANGES_REQUESTED ──submit──▶ SUBMITTED
                                                           ├─ REJECT          ─▶ REJECTED (terminal)
                                                           └─ APPROVE         ─▶ APPROVED ─▶ PROVISIONING ─(Graph: create app reg)─▶ ACTIVE
```
Provisioning is async; status sits at `PROVISIONING` then flips to `ACTIVE`, recording the real client ID.

**Access request:**
```
SUBMITTED ──▶ UNDER_REVIEW ──decision──▶ ┌─ REQUEST_CHANGES ─▶ CHANGES_REQUESTED ──resubmit──▶ SUBMITTED
                                         ├─ REJECT          ─▶ REJECTED (terminal)
                                         └─ APPROVE         ─▶ APPROVED ─▶ PROVISIONING ─(Graph: add group member)─▶ GRANTED
```
Removal is an access request with `kind=removal`; on approve+provision it removes the group member.

**Transition guards:** only legal transitions allowed (illegal → 409); `decision` requires the
`*.decide` permission + passes the SoD check; provisioning transitions are emitted by the async worker,
not the API.
