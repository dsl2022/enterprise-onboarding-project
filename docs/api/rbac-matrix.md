# RBAC — role → permission matrix + state machines (v1, FROZEN — change via a new change request)

Roles are **bundles of permissions**, derived from the Entra **app-roles (`roles`) claim** in the ID
token — **not** the group-membership claim. App roles are app-scoped (only this app's roles appear, no
filtering), have no overage cutoff, and don't bloat the cookie. Checks at the **service layer** are
always **permission** checks (never role checks) — granted if the permission appears in **any** of the
principal's assigned app roles (union, most-permissive scope) — plus **ABAC ownership** (owners act only
on their own resources) and **separation of duties** (the requester of a request can never be its
approver). The frontend only hides UI; the server is authoritative.

> **App roles vs groups (explicit separation):**
> - **Entra app roles → portal RBAC** — what you can *do in the portal* (this matrix).
> - **Entra group membership → access governance** — what *downstream resources* you hold. The
>   `directory` module still reads groups, and access provisioning still adds/removes group members via
>   Graph. Groups are **not** used for portal-role resolution.

## Roles ↔ Entra app roles
| Role (app role value) | Assigned to (seed) |
|---|---|
| `APPLICATION_OWNER` | test owner user(s) |
| `SSO_OPERATIONS` | test ops user |
| `ADMIN` | test admin user |
| `AUDITOR` | test auditor user |
| `READ_ONLY` | test read-only user |
| `SUPER_ADMIN` | test super-admin user |

App roles are declared on the Entra **application registration** and assigned to **users** in the
enterprise app (via `azuread_app_role_assignment` or the portal). Seed by **app-role assignment**, not
group membership. (The old role-*groups* Admins/Read-Only/Super-Admins are dropped; access-governance
groups like `aws-prod-engineers` stay.)

**Multiple roles → union of permissions (not a single role).** The `roles` claim is an array and a user
may hold several app roles. Because the roles are **not a strict hierarchy** (e.g. `APPLICATION_OWNER`
and `AUDITOR` grant disjoint permissions), authorization is the **union** of every held role's
permissions — a permission is allowed if **any** assigned role grants it. **Scope resolves to the most
permissive:** when several held roles grant the same permission at different scopes, an unscoped `✔`
from any role overrides `✔(own)`; `✔(own)` applies only when **every** granting role is `✔(own)`.
(Example: `APPLICATION_OWNER`+`AUDITOR` → `app.read` becomes unscoped `✔` and they keep `app.create`
from the owner role; picking one "winning" role would drop one of those.)

A single **display role** is still computed for UI only — the most-privileged held role
(`SUPER_ADMIN` > `ADMIN` > `SSO_OPERATIONS` > `AUDITOR` > `APPLICATION_OWNER` > `READ_ONLY`) — returned
as `/me.role` for the header/banner. **It is never the basis for an access decision.**

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
- **Team membership = ABAC scope, never RBAC.** Being a member of a team grants **no role permissions** —
  portal RBAC comes only from the Entra app-roles claim. It confers only **ABAC co-ownership**, and only on
  resources that **opt in** by referencing the team. So `team.manage` is an **ownership-delegation surface**
  (adding a member co-owns the team for `team.read`/`team.manage(own)`). It can never confer an approver
  role: `app.decide`/`access.decide` are `ALL`-scoped, never team-granted, and SoD resolves the **real**
  principal. *(v1 scope, Pin A: this is active for `Team` resources only. Onboarding `Application.team[]`
  co-ownership is deferred — it would resolve team IDs to members via a future `authz`-owned
  `TeamMembershipResolver` port; `RequestEntity` keeps the empty `teamMemberIds()` default until then.)*

### Impersonation — permissions vs identity (the laundering guard)
While a Super Admin impersonates a role:
- **Permissions** come from the **impersonated role** (so the reduced view demos correctly).
- **Identity** — for **SoD** (`requester ≠ approver`), **ABAC ownership**, and **audit attribution** —
  always resolves to the **real Super Admin principal**, never the impersonated role. This prevents
  self-approval laundered through impersonation (submit as self → impersonate ops → approve = blocked,
  because SoD sees the same real principal on both sides).
- **Audit:** every impersonated action is written with `actor = <real super admin>` and
  `effectiveRole = <impersonated role>`.
- v1 ships **role-level** impersonation; the `POST /impersonation` body reserves an optional `user`
  field for future user-level impersonation (ignored in v1) so it won't be a contract change later.

### Two-person approval (future extension — noted, not built)
The state machine has a single `UNDER_REVIEW → decision`. If dual approval for high-risk catalog items
is wanted later, it's an additive state-machine change (e.g. `UNDER_REVIEW → PENDING_SECOND_APPROVAL →
APPROVED`); flagged now so it's a known extension, not a surprise.

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
not the API. The DB transition guard (`UPDATE … WHERE status = <expected>`) is the **authoritative
serializer** — it succeeds for exactly one caller, so two approvers can't both transition. `If-Match`
(ETag → 412) is the optimistic-concurrency courtesy layer on top; both are kept.

## Audit integrity (immutable means immutable)
- **Single-writer append.** The hash chain (`hash = H(prevHash ‖ row)`) requires serialized inserts, so
  audit rows are appended by the **single outbox relay consumer**, never directly by the N
  request-handling tasks. Cross-task serialization on append is enforced with a Postgres **advisory
  lock** (or `SELECT … FOR UPDATE` on the chain tail) so ≥2 Fargate tasks can't fork the chain.
- **DB-level immutability.** The application's DB role has **`INSERT` + `SELECT` only** on
  `audit_events`; **`UPDATE`/`DELETE` are revoked** (belt-and-suspenders: also a trigger that raises on
  update/delete). The app literally cannot rewrite history.
- **Verifiable.** `GET /audit/verify` recomputes the chain and returns `{valid, checkedThrough,
  brokenAt}`; a documented offline procedure does the same.
