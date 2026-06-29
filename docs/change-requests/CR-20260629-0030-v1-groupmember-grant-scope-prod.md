# CR-20260629-0030 — constrain/monitor the tenant-broad GroupMember.ReadWrite.All grant (prod)

- **ID:** CR-20260629-0030
- **Date:** 2026-06-29 00:30 EDT
- **Author:** Architect review of the Phase 5b implementation note (relayed by the engineer)
- **Target:** `deploy/terraform/modules/entra` (app permission) — **prod hardening, not a v1 blocker**
- **Status:** Open (candidate — v1 dev accepts the broad grant; revisit for prod)
- **Related:** Phase 5b, ADR-0019, the OwnedBy/least-privilege spirit of ADR-0016

## Context

5b grants the workload app `GroupMember.ReadWrite.All` so it can add/remove access-grant members. **Microsoft
Graph has no per-group app-only scope for membership writes** — this app-permission is **tenant-broad**: the
workload can modify the membership of *any* group in the tenant, not just the catalog-mapped ones. Same
shape as the `Application.ReadWrite.OwnedBy` least-privilege note (ADR-0016), but broader in blast radius.

Acceptable for v1 **dev** (throwaway tenant, throwaway groups). For prod it's more access than the portal
needs and should be constrained and/or monitored.

## Requested change (candidate — if/when prod hardening is scheduled)

One or more of:
- **Scope the writes:** use directory **administrative units** (or RBAC-for-Applications role scoping) so the
  app's membership writes are limited to a defined set of catalog-mapped groups, rather than the whole
  tenant.
- **Monitor:** alert on group-membership changes made by the workload SP (audit/sign-in logs), so any write
  outside the catalog-mapped set is detectable.
- **Reconcile:** periodic check that the workload only ever touched catalog-mapped groups.

## Resolution

_Open (candidate). v1 dev ships the broad grant (documented in ADR-0019 + RUNBOOK); prod hardening tracked
here so the least-privilege gap is deliberate, not forgotten._
