# CR-20260628-2240 — time-bound access: scheduled expiry de-provision sweep

- **ID:** CR-20260628-2240
- **Date:** 2026-06-28 22:40 EDT
- **Author:** Architect review of the Phase 5 design note (relayed by the engineer)
- **Target:** application behavior (`access` module worker) — **future phase, no contract change**
- **Status:** Open (candidate — deferred; Phase 5a persists `expires_at` so this is a pure add)
- **Related:** Phase 5a (`access_grant` projection), Phase 4b reaper/backoff infra, ADR-0017 (Phase 5a)

## Context

`AccessRequest.duration` (ISO-8601, nullable = permanent) is captured in v1 but **not enforced** — a
granted access never auto-expires. "Time-bound access that doesn't actually expire" is a classic audit
finding **if enforcement is implied**. The risk is not the deferral; it is promising expiry we don't do.

So Phase 5a does two cheap things to make the deferral safe and the future build trivial:

1. **Persist `expires_at`** (= `granted_at` + `duration`, NULL for permanent) on the `access_grant`
   projection now — even though nothing reads it yet. No migration needed later.
2. **State non-enforcement explicitly** in ADR-0017 / the DoD and ensure the contract + UI semantics say
   **"duration is informational in v1"** (no auto-expiry promised).

## Requested change (candidate — if/when scheduled)

A scheduled **expiry sweep** (mirrors the Phase 4b stuck-provisioning reaper): rows where
`expires_at < now() AND removed_at IS NULL` are pushed through the **existing removal / de-provision path**
(remove the Entra group member via `GroupMembershipProvisioner`, set `removed_at`). Reuses the worker,
the lease/backoff, and the type-scoped poll already built for access. Because `expires_at` is persisted in
5a, this is **a pure add — no schema migration**.

## Resolution

_Open (candidate). Deferred by architect + engineer agreement; Phase 5a persists `expires_at` and
documents v1 duration as informational so this lands cleanly as a small later phase._
