# CR-20260628-2235 — add `/access-requests/{id}/submit` (access resubmit after REQUEST_CHANGES)

- **ID:** CR-20260628-2235
- **Date:** 2026-06-28 22:35 EDT
- **Author:** Architect review of the Phase 5 design note (relayed by the engineer)
- **Target:** `docs/api/openapi-v1.yaml` (frozen contract) — **candidate, NOT applied**
- **Status:** Open (candidate — deliberate deferral; v1 ships "open a new request")
- **Related:** Phase 5a (`access` module), [[CR-20260628-2116]] precedent (contract-CR governance), ADR-0017 (Phase 5a)

## Context

The frozen contract gives **onboarding** a resubmit path — `POST /applications/{id}/submit`
(DRAFT/CHANGES_REQUESTED → SUBMITTED → UNDER_REVIEW) — but **access requests have no equivalent**: the
contract has `POST /access-requests` (create) and `POST /access-requests/{id}/decision` (decide), and
nothing else. So when an approver returns an access request with `REQUEST_CHANGES`, the request lands in
`CHANGES_REQUESTED` with **no endpoint to move it forward** — it dead-ends.

`CHANGES_REQUESTED` is a valid `AccessStatus` enum value, so the state is reachable but unservable. This
is a real freeze gap, not a nit: a self-service-access product whose changes-requested feedback dead-ends
is a product gap. We want the resolution **deliberate, not discovered at build time**.

## v1 behavior (5a, no contract change)

A changes-requested access request is **abandoned**; the requester **opens a new `POST /access-requests`**.
Low-friction and contract-faithful for lightweight access requests. The access UI may choose not to offer
`REQUEST_CHANGES` for access decisions at all (UI-side, no contract impact).

## Requested change (candidate — if/when scheduled)

Add `POST /access-requests/{id}/submit` mirroring the onboarding submit: `CHANGES_REQUESTED → SUBMITTED →
UNDER_REVIEW`, carrying `Idempotency-Key` + `If-Match`, returning the `AccessRequest`. Additive,
non-breaking; **`version` bump** (e.g. 1.0.1 → 1.0.2). The engine already supports the transition shape
(`RequestTransitions`); only the contract surface + a thin controller route would be new.

## Resolution

_Open (candidate). Deferred from Phase 5a by architect + engineer agreement — v1 ships "open a new
request." Schedule (via the contract-CR governance: edit on a branch, Spectral-green, human freeze/merge)
if the product wants in-place resubmit._
