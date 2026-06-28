# CR-20260628-1056 — RBAC multi-role union + nits

- **ID:** CR-20260628-1056
- **Date:** 2026-06-28 10:56 EDT
- **Author:** Senior-architect review (Claude)
- **Target:** PR #11 — `v1-phase1-contract`, re-review of commit `d0a0834`
- **Status:** Open (pending agent apply, then freeze)
- **Related:** [docs/api/rbac-matrix.md](../api/rbac-matrix.md) · [docs/api/openapi-v1.yaml](../api/openapi-v1.yaml) · supersedes the precedence rule introduced by [CR-20260628-1020](CR-20260628-1020-v1-contract-prefreeze.md)

## Context

Re-review of the `d0a0834` amendments confirmed #1–#5 were all applied correctly
(app roles, idempotency, `If-Match` on decisions, audit integrity, flags/nits). The
**Spectral lint passes** locally against the PR's contract + ruleset
(`No results with a severity of 'error' found`, exit 0) — the freeze gate is green.

One **new substantive issue** surfaced from the multi-role handling added in #1, plus
two minor nits. These are the only items left before freeze.

## Requested changes

### 1. Multi-role permission resolution must be a **union**, not a single role (substantive)

`d0a0834` resolves multiple app roles to the **most-privileged** single role and checks
permissions against it. The 6 roles are **not a clean hierarchy** — some are orthogonal
(`APPLICATION_OWNER` and `AUDITOR` grant disjoint permissions). "Most-privileged wins"
then **drops** permissions: an `APPLICATION_OWNER`+`AUDITOR` resolves to `AUDITOR` and
silently loses `app.create`. Fix: **authorization = union of all held roles' permissions**;
the single most-privileged value is **display-only**.

**`docs/api/rbac-matrix.md`** — replace the "Role precedence" paragraph with:

> **Multiple roles → union of permissions (not a single role).** The `roles` claim is an
> array and a user may hold several app roles. Because the roles are **not a strict
> hierarchy** (e.g. `APPLICATION_OWNER` and `AUDITOR` grant disjoint permissions),
> authorization is evaluated as the **union of every held role's permissions** — a
> permission is allowed if **any** assigned role grants it (with `✔(own)` ownership still
> applied per-resource). Picking one "winning" role would silently drop permissions a user
> legitimately has (an `APPLICATION_OWNER`+`AUDITOR` would lose `app.create`).
>
> A single **display role** is still computed for UI only — the most-privileged held role
> (`SUPER_ADMIN` > `ADMIN` > `SSO_OPERATIONS` > `AUDITOR` > `APPLICATION_OWNER` >
> `READ_ONLY`) — and returned as `/me.role` for the header/banner. **It is never the basis
> for an access decision;** the service layer always checks the permission against the union.

And tighten the intro line — change
`… are always **permission** checks (never role checks) …` to:

> … are always **permission** checks (never role checks) — a permission is granted if it
> appears in **any** of the principal's assigned app roles (union) …

### 2. `Me.role` description: mark display-only (nit, supports #1)

**`docs/api/openapi-v1.yaml`** — replace the `Me.role` block with:

```yaml
        role:
          allOf: [ { $ref: "#/components/schemas/Role" } ]
          description: "Display role only (most-privileged of the held app roles). NOT the basis for authorization — the server checks permissions as the union of all assigned roles."
        roles:
          type: array
          items: { $ref: "#/components/schemas/Role" }
          description: "All app roles held by the principal (the `roles` claim); authorization is the union of these."
```

(The `roles` array is optional but cheap — lets the frontend reason about the full set.
The `role` description change alone closes the correctness gap if you want minimal surface.)

### 3. Restore `required: [role]` on `ImpersonationRequest` (nit)

When the optional `user` field was added, the `required` block was dropped, so `{}` is now
a valid body. Re-add it:

```yaml
    ImpersonationRequest:
      description: >
        v1 ships role-level impersonation (set `role`). The optional `user` is reserved for future
        user-level impersonation so adding it later is not a contract change; ignored in v1.
      type: object
      required: [role]
      properties:
        role: { $ref: "#/components/schemas/Role" }
        user: { type: string, nullable: true, description: "Reserved (future user-level impersonation); ignored in v1" }
```

### Freeze step

After 1–3: bump `info.version` `1.0.0-draft → 1.0.0` and change the "DRAFT for review"
marker to frozen, as the final commit. Re-run Spectral (the added `roles` array should
still lint clean).

## Resolution

_Pending. Record the applying commit here and set Status → Applied once the agent pushes._
