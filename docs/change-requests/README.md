# Change Requests

Architecture / contract **change requests** (CRs) — reviewer-raised changes against a
PR or design artifact, captured as durable records so decisions and their rationale
survive the chat that produced them. Especially important for **contract-freeze**
gates, where "why did we change this field?" must be answerable months later.

## Convention

- **One file per CR.** Never edit a CR's substance after it's `Applied`/`Rejected`;
  supersede it with a new CR instead (link both).
- **Filename:** `CR-YYYYMMDD-HHMM-<slug>.md` — timestamp is **local time** at the
  moment the CR was raised, so the folder sorts chronologically. The **slug** is
  `v<version>-<area>-<detail>` (e.g. `v1-contract-rbac-union`, `v1-phase2-prod-hardening`)
  — always lead with the product version so CRs group by release.
- **Every CR carries a header** (id, date, author, target PR/artifact, status, related
  files) followed by Context → Requested changes → Resolution.

## Status lifecycle

`Open` → `Accepted` → `Applied` (record the commit) · or `Rejected` · or `Superseded by <id>`.

## Index

| CR | Date | Target | Title | Status |
|---|---|---|---|---|
| [CR-20260628-1020](CR-20260628-1020-v1-contract-prefreeze.md) | 2026-06-28 10:20 EDT | PR #11 | v1 contract pre-freeze review (#1–#5) | Applied (d0a0834) |
| [CR-20260628-1056](CR-20260628-1056-v1-contract-rbac-union.md) | 2026-06-28 10:56 EDT | PR #11 | RBAC multi-role union + nits | Applied (09728ea) |
| [CR-20260628-1416](CR-20260628-1416-v1-phase2-prod-hardening.md) | 2026-06-28 14:16 EDT | PR #13 | v1 Phase 2 data-layer prod-hardening (deferred) | Open |
