# CR-20260628-2116 — add 422 to the idempotent POSTs' response lists

- **ID:** CR-20260628-2116
- **Date:** 2026-06-28 21:16 EDT
- **Author:** Architect review of PR #80 (relayed by the engineer)
- **Target:** `docs/api/openapi-v1.yaml` (frozen contract, was 1.0.0)
- **Status:** Open (proposed — awaiting human freeze/merge)
- **Related:** PR #80 (Phase 4a), ADR-0015, [[CR-20260628-1020]] precedent

## Context

Phase 4a implements the contract's idempotency semantics: an `Idempotency-Key` reused with a **different
request body** returns **422**. That rule is documented globally in the contract's `info` block, and
`POST /applications` already lists `422` per-endpoint — but the other idempotent POSTs (`/submit`,
`/decision`, and the Phase-5 `/access-requests*`, `/my-access/.../removal`, `/teams`, `/teams/{id}/members`,
`/assistant/chat`) enumerate only their happy/condition statuses, **not 422**. So the implementation can
legitimately return a status the per-endpoint response list doesn't declare — a per-endpoint precision gap.
The architect flagged it as a non-blocking follow-up; this CR closes it.

## Requested change (applied on branch `v1-cr-idempotency-422`)

Additive, non-breaking (it only *documents* a status the layer already returns; no client built against
1.0.0 breaks). **`version` 1.0.0 → 1.0.1.** Add `"422": { $ref: "#/components/responses/Unprocessable" }`
to every POST that carries `Idempotency-Key` and didn't already list it:

- `POST /applications/{id}/submit`
- `POST /applications/{id}/decision`
- `POST /access-requests`
- `POST /access-requests/{id}/decision`
- `POST /my-access/{resourceId}/removal`
- `POST /teams`
- `POST /teams/{id}/members`
- `POST /assistant/chat`

(`POST /applications` already listed 422 — unchanged.) No schema, parameter, or behavior changes; the
Spectral gate must stay green.

## Resolution

_Open. Applied on `v1-cr-idempotency-422` (PR pending); Spectral green expected. Human freezes/merges per
the contract-governance rule, then record the merge commit here._
