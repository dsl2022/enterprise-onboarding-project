# CR-20260628-1020 — v1 contract pre-freeze review (#1–#5)

- **ID:** CR-20260628-1020
- **Date:** 2026-06-28 10:20 EDT
- **Author:** Senior-architect review (Claude)
- **Target:** PR #11 — `v1-phase1-contract` ("v1 Phase 1: freeze API contract")
- **Status:** Applied — commit `d0a0834` ("v1 contract: address pre-freeze change request (#1-#5 + flags + nits)")
- **Related:** [PROJECT_BRIEF_V1.md](../../PROJECT_BRIEF_V1.md) · [docs/api/openapi-v1.yaml](../api/openapi-v1.yaml) · [docs/api/rbac-matrix.md](../api/rbac-matrix.md) · [docs/V1-PLAN.md](../V1-PLAN.md)

## Context

PR #11 is the **contract-freeze gate** for v1 (no module code ships until the OpenAPI
contract + RBAC matrix + state machines are frozen). Freeze decisions are baked into
the API surface and the security model, so they are expensive to change once the
Angular frontend and the auditors pin to them. This CR captures the review raised
before freeze. Overall assessment: the plan was solid and the contract-first approach
correct; freeze was conditional on the items below.

## Requested changes

### Freeze-blocking (contract / security model)

1. **RBAC source: app roles, not the group-membership claim.** The group claim emits a
   user's *entire* tenant group set as GUIDs → cookie bloat, the ~200-group **overage
   cliff** (claim silently dropped → user logs in with no permissions), and security
   coupled to directory GUIDs others own. **Switch RBAC to Entra app roles** (`roles`
   claim) — app-scoped, no overage, no bloat. **Keep groups for access governance only**
   (the downstream resource holdings the portal grants/revokes via Graph).

2. **Idempotency semantics under-specified.** `Idempotency-Key` was required but
   behavior undefined. Specify: replay (same key) → **original** response (never a
   duplicate side effect, never a 409); same key + different body → **422**; a genuine
   business conflict (e.g. duplicate name) → **409** (distinct); scope = per-principal +
   per-endpoint; define retention (24h).

3. **Optimistic concurrency on state transitions.** `POST /{id}/decision` (and
   `/submit`) mutate a versioned resource but lacked `If-Match`. Two approvers could both
   pass the `UNDER_REVIEW` guard. Add `If-Match`/`412`; keep the DB transition guard
   (`UPDATE … WHERE status=<expected>`) as the **authoritative** serializer, with
   `If-Match` as the courtesy layer.

4. **Audit "immutable, hash-chained" needs an enforcement story that survives ≥2 tasks.**
   A hash chain needs **serialized inserts** (concurrent writers fork the chain), and
   "immutable" in Postgres is only a promise unless enforced. Require: single-writer
   append (outbox relay + advisory lock); DB role `INSERT`/`SELECT` only with
   `UPDATE`/`DELETE` revoked; and a `GET /audit/verify` chain check.

### Flag-now, resolve-in-phase (not freeze blockers)

- **Outbox poller concurrency** — `FOR UPDATE SKIP LOCKED` / leader election so ≥2 tasks
  don't double-dispatch; `notify` idempotent for the residual.
- **Impersonation is role-level, not user-level** — can't reproduce ABAC-ownership bugs;
  reserve a `user` field now so user-level isn't a breaking change later.
- **"HA" Redis is single-node** — a session SPOF; don't call it HA without a replica.
- **Blue/green is a destructive ECS cutover** — flipping `deploymentController` recreates
  the service and replaces the SSM rolling spine; the module must own the cutover.
- **Single-approver, no quorum** — note two-person approval as an additive future state.

### Nits

- `info.version` `1.0.0-draft` → `1.0.0` at the freeze commit.
- `/decision` 403 defined inline while a `Forbidden` component exists — use the component.
- Add a `/audit/verify` endpoint (covered by #4).
- Add an OpenAPI **Spectral** lint as a CI gate so the frozen contract can't drift invalid.

## Resolution

**Accepted in full; applied in commit `d0a0834`.** Verified against the diff:

- **#1** App roles adopted across brief/plan/matrix/OpenAPI; `Me.role` sourced from the
  `roles` claim; `group` marked "NOT the RBAC source"; groups retained for access
  governance. Role precedence for multi-role users added.
- **#2** Idempotency semantics fully specified (info block + `IdempotencyKey` param +
  per-endpoint description: replay→original, key+different-body→422, business→409, 24h).
- **#3** `If-Match`/`412` added to both `/decision` endpoints and `/submit`; DB guard
  kept authoritative.
- **#4** Single-writer + advisory lock, DB-level immutability (`UPDATE`/`DELETE` revoked +
  trigger), `GET /audit/verify` with `AuditVerifyResult` schema.
- **#5 / flags / nits** all addressed: outbox `SKIP LOCKED`/leader; reserved `user` field
  on `ImpersonationRequest`; Redis SPOF documented; blue/green destructive cutover noted;
  two-person approval flagged; `Forbidden` component used; Spectral gate added
  (`.spectral.yaml` + `ci.yml`).
- **Bonus (reviewer-endorsed):** impersonation "laundering guard" — SoD/ABAC/audit resolve
  to the real Super Admin, blocking self-approval via impersonation.

**Follow-on:** the multi-role precedence rule introduced here is lossy for orthogonal
roles → raised as **[CR-20260628-1056](CR-20260628-1056-v1-contract-rbac-union.md)**.
