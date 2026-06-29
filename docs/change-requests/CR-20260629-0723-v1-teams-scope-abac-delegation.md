# CR-20260629-0723 — v1 Phase 5c teams: portal-local scope (defer group-backing) + ABAC team-delegation clarification

- **ID:** CR-20260629-0723
- **Date:** 2026-06-29 07:23 EDT
- **Author:** Senior-architect review (Claude)
- **Target:** Issue #95 — "5c: teams (direct CRUD, no engine/approval)" / ADR-0017 (design note)
- **Status:** Applied (Phase 5c PR — ADR-0020; teams module + V7 + rbac-matrix ABAC line + Pin A regression guard)
- **Related:** [docs/api/rbac-matrix.md](../api/rbac-matrix.md) · [app/src/main/java/com/eop/authz/AuthorizationService.java](../../app/src/main/java/com/eop/authz/AuthorizationService.java) · [app/src/main/java/com/eop/authz/Ownable.java](../../app/src/main/java/com/eop/authz/Ownable.java) · [docs/api/openapi-v1.yaml](../api/openapi-v1.yaml) (`Application.team`, `Team`) · [docs/V1-PLAN.md](../V1-PLAN.md)

## Context

The 5c design note proposes the `/teams*` surface as direct CRUD (create / manage membership /
soft-delete, audited) — a standalone `teams` module depending on `authz` + `platform` only, **not**
over the `request` aggregate (no state machine, no SoD, no provisioning lifecycle). That framing is
endorsed. Two decisions need a durable record because they (a) **supersede the issue #95 stub**, which
assumed group-backed teams ("reuses the 5b `GroupMembershipProvisioner` for backing-group membership"),
and (b) change the **authorization surface** of existing onboarding applications.

## Requested changes

### 1. Scope = **Option A (portal-local teams)** for v1; defer group-backing

Teams + members live in DB tables; member add/remove is DB rows with soft-delete. **No Graph, no new
consent.** This supersedes the "reuses the 5b `GroupMembershipProvisioner`" line in issue #95.

- **(B) full Entra-group-backed teams — DEFERRED** (tracked, this CR). `POST /teams` creating a real
  Entra group needs **`Group.ReadWrite.All`** — a new, broad consent (create/delete *any* group) beyond
  what we hold. Out of scope for v1 on least-privilege grounds; if wanted later it is a simulated/real
  split like 4b/5b plus the new grant.
- **(C) optional group reflection — DEFERRED to ride the Phase 6 outbox relay.** C's true cost is **not**
  consent (it reuses 5b's already-granted `GroupMember.ReadWrite.All`) — it is **dual-write
  consistency.** A synchronous Graph call inside `TeamService.addMember` reintroduces exactly the
  partial-failure problem the access loop needs the async worker + reaper + projection-as-source-of-truth
  to absorb; doing it synchronously contradicts the "teams are simple CRUD, no engine" framing. Because
  5c already emits team-membership events to the outbox, the correct future mechanism is **team-member
  outbox event → Phase 6 relay → `GroupMembershipProvisioner`** — reusing 5b's consent, no new engine, no
  new grant. So group reflection is a natural post-Phase-6 follow-on, not v1 work.

Teams are still useful in v1 without group-backing: they populate the ABAC team-ownership dimension
(`Ownable.teamMemberIds()`, currently the empty default) and give meaning to onboarding
`Application.team[]`.

### 2. Define `Application.team[]` → `teamMemberIds()` resolution, and document the ABAC delegation it opens

This is a **change to the authorization behavior of existing applications** and must be deliberate.
[`AuthorizationService.owns()`](../../app/src/main/java/com/eop/authz/AuthorizationService.java) is:

```java
return real.equals(resource.ownerId()) || resource.teamMemberIds().contains(real);
```

Today `RequestEntity` never overrides `teamMemberIds()`, so it returns the empty default — **no
application has team co-owners.** Once 5c resolves an app's team members, *being added to a team becomes
`OWN`-scoped co-ownership of every application that references that team* (`app.read` / `app.update` /
`app.submit`). That is the intended feature, but it makes `team.manage` an ownership-delegation lever.

- **Pin the contract:** `Application.team[]` holds **team IDs**, and `teamMemberIds(app)` = the union of
  the **active** members of those teams. Confirm no existing data/tests put user IDs in `team[]` (that
  would silently change its meaning).
- **Threat model (verified bounded + SoD-safe — recorded so it is not re-derived):**
  1. Team membership grants **zero portal permissions** — portal RBAC comes only from Entra **app roles**
     (per `rbac-matrix.md`), never from team/group membership.
  2. It grants only `OWN`-scoped co-ownership, and only on apps that **explicitly** list the team in
     `team[]` — consensual both ways (the app owner opts in; the team owner controls membership).
  3. It **cannot** bypass separation of duties: `app.decide` is not `OWN`-scoped and not team-granted, so
     `requireDecision` still resolves the real principal as requester/approver. Team membership can never
     make a user an approver.
- **Document it:** add a line to `rbac-matrix.md` (ABAC section) stating that **team membership confers
  ABAC co-ownership on resources that reference the team**, and that `team.manage` is therefore an
  ownership-delegation surface. The matrix today says only "owners act only on their own resources."
- **Prove it:** test that a member of a team referenced by an app they do **not** own can read/update it;
  a non-member gets 403; **removing** the member revokes the access.

### 3. Team-level soft-delete — close the schema gap (build item)

The goal says "create teams, manage membership, **soft-delete** (audited)" and the surface is `/teams*`,
but the proposed schema only soft-deletes **members** (`team_members.removed_at`); the `teams` table has
no `deleted_at`. Clarify `DELETE /teams/{id}`: add `teams.deleted_at`, define the **member cascade** (do
active memberships drop when the parent is deleted?), and define what happens to **dangling
`Application.team[]` references** to a deleted team (resolve to empty members). `memberCount` and the
partial unique index must account for a deleted parent.

## Decisions confirmed (no change required — recorded for the build)

- **Dedicated `teams` schema** — consistent with per-module schema ownership.
- **Re-add reactivates the soft-deleted row** — acceptable **because** the outbox audit events carry the
  add/remove/re-add history; the row is current-state, the event log is history. Same idiom as
  `access_grant`'s `(team_id, user_id) WHERE removed_at IS NULL` partial unique index.
- **Emit audit now; Phase 6 consumes** — yes, via `platform.OutboxWriter` (preserves the single-writer
  hash-chain invariant), **using the same outbox event envelope** the onboarding/access modules emit.
- **Idempotency** — find-or-create/reactivate is the atomic retry unit (the unique index resolves the
  concurrent double-add race), so add-member is idempotent even without `Idempotency-Key`; the key is
  claim-first dedup on top. Same as access.
- **Minor:** `memberCount` via a single `GROUP BY` aggregate on `GET /teams` (no N+1); team **name
  uniqueness → 409**, consistent with the onboarding dup-name behavior.

## Resolution

**Accepted as the architect's 5c routing.** Build per **Option A**; address change #2 (team[] resolution
+ matrix doc + test) and #3 (team soft-delete schema) in the 5c PR — **as refined by the addendum below**.
(B) remains deferred on least-privilege grounds; (C) is deferred to ride the Phase 6 outbox relay. To be
marked **Applied** with the 5c PR commit; the V1-PLAN tick (Phases 1–5 + the `teams` line) lands in the
same PR.

## Addendum (2026-06-29, post-verification) — implementation pins

An engineer verification pass confirmed the two unconfirmed assertions this CR rested on, and a
code-grounded review pinned the build mechanics. Recorded here so they don't resurface as ambiguity
mid-PR.

**Verified (both true):**
- **§2 threat-model #3 (SoD-safe):** `app.decide` is `Scope.ALL`-only (SSO_OPERATIONS / ADMIN /
  SUPER_ADMIN), never `OWN`, never team-granted; `AuthorizationService.requireDecision` blocks
  requester==approver on the real principal. Team membership cannot manufacture an approver. ✅
- **§2 contract-pin:** `Application.team` is `array<string>` in Application / ApplicationCreate /
  ApplicationPatch, and nothing (fixtures, seeds, tests, `OnboardingService`) ever populates it with user
  IDs. The asked-for test is a **regression guard**, not a fix. ✅

**Pin A — v1 scopes team co-ownership to `TeamEntity` only; Application co-ownership is deferred.**
`Application.team[]` does **not** live on the request aggregate as a column — it sits inside the opaque
`payload` JSON (`OnboardingService`), and `RequestEntity`'s design invariant is to enforce ABAC ownership
*without parsing the payload*. Wiring team co-ownership onto onboarding Applications in v1 would therefore
either violate that invariant or require promoting `team` to a first-class column (engine + migration
change). Since `team[]` is currently never populated, that co-ownership is dormant today regardless. So:
- v1: `TeamEntity implements Ownable` — `ownerId()` = creator oid, `teamMemberIds()` = active member oids
  (loaded with the entity). This makes `team.read(own)` resolve to **creator OR active member** (closes
  the "a member can't GET their own team" gap). No resolver, no `Ownable` refactor, **no engine change.**
- `RequestEntity` keeps the empty `teamMemberIds()` default → **no Application co-ownership in v1.**
- This **supersedes the §2 framing** that team membership is an *active* authorization change to existing
  apps — for v1 it is active only for `TeamEntity`. The §2 threat-model and the rbac-matrix delegation
  line are still written (they document the mechanism), but flagged as taking effect for Applications only
  when co-ownership is wired.

**Pin B — the deferred Application-co-ownership mechanism (documented now, built later).** When it lands
(with the `team`→column promotion), it must be a **`TeamMembershipResolver` port defined in `authz`,
implemented in `teams`** (dependency inversion). This is the only boundary-legal shape: ArchUnit makes
`authz` a pure foundation and forbids `request → teams`, so `RequestEntity` exposes raw team IDs and
`AuthorizationService` expands them via the `authz`-owned port. A no-op default resolver
(`@ConditionalOnMissingBean`, in `authz`, returns empty) keeps onboarding/access working without the
teams module present.

**Pin C — freeze the event payloads, not just the envelope.** Via the existing
`OutboxWriter.append(aggregateType, aggregateId, eventType, payload)` (dot-namespaced types, matching
`request.created` / `request.submitted` …): aggregateType `team`; event types **`team.created` /
`team.member.added` / `team.member.removed`**; payload fields **`teamId, userId, actorId, occurredAt`**.
Frozen so Phase 6's audit relay needn't re-rev.

**Pin D — ArchUnit deliverable.** Add a positive `teams_module_boundary` rule: `teams` depends only on
`com.eop.authz` + `com.eop.platform` (+ framework/JDK), never `request`. (A `teams → request` leak already
fails `only_type_modules_use_the_request_engine`, which doesn't list `teams`; this makes the invariant
explicit.)

**Pin E — wording (§2 threat-model #1 vs #2).** Replace the "zero portal permissions" / "OWN-scoped
co-ownership" pair with a single clause: **"team membership grants no RBAC role permissions — only ABAC
resource scope, and only on resources that opt in by referencing the team."**
