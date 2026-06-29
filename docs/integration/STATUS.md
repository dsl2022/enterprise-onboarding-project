# Endpoint status map — live / mock / not-built

**Backend agent owns this file.** Updated as each phase lands. Base path for all endpoints: **`/api/v1`**
(except the BFF `/auth/*` helpers). Authoritative shapes: `docs/api/openapi-v1.yaml` (v1.0.1).

Legend:
- 🟢 **LIVE** — implemented + merged to `main`; hit the dev deployment for real.
- 🟡 **LIVE (PR pending)** — built + CI-green, awaiting merge; live once its PR merges.
- 🔵 **SIMULATED** — endpoint is live, but the *side effect* is simulated until a consent gate flips (see note).
- ⚪ **MOCK** — contract-stable but backend not built yet; mock from the OpenAPI.
- 🛑 **501** — endpoint will exist but returns Not Implemented in v1.

_Last updated: 2026-06-29 (Phase 6a audit merged + live-verified; Phase 6b in-app notifications built, PR pending)._

## Identity / session (Phase 3a) — 🟢 LIVE
| Endpoint | Status | Notes |
|---|---|---|
| `GET /me` | 🟢 | Identity for the SPA: roles[], display role, impersonation state. **Start here.** |
| `POST /impersonation` `{role}` | 🟢 | SUPER_ADMIN only. Switches the *effective* role for permissions; real identity unchanged. |
| `DELETE /impersonation` | 🟢 | Clears impersonation. |
| `GET /auth/login` | 🟢 | BFF login entry (see INTEGRATION-NOTES — browser does no OIDC). |

## Onboarding (Phase 4a + 4b) — `/applications*`, `/review-queue` — 🟢 LIVE (real provisioning)
| Endpoint | Status | Notes |
|---|---|---|
| `POST /applications` | 🟢 | Idempotency-Key required. |
| `GET /applications`, `GET /applications/{id}` | 🟢 | List is role-scoped (owners see own). ETag on single GET. |
| `PATCH /applications/{id}` | 🟢 | If-Match required. Merge, not replace; `name`/`env` immutable. |
| `POST /applications/{id}/submit` | 🟢 | Idempotency-Key + If-Match. |
| `POST /applications/{id}/decision` | 🟢 | Idempotency-Key + If-Match. SoD enforced. |
| `GET /applications/{id}/timeline` | 🟢 | |
| `GET /review-queue` | 🟢 | Reviewers only (`review.read`); type-agnostic. |
| → resulting **client ID** | 🟢 | **Real** — 4b activated + consented; approval creates a real Entra app registration and returns its client ID (verified live). |

## Access governance (Phase 5a + 5b) — `/catalog*`, `/access-requests*`, `/my-access*` — 🟢 LIVE (real provisioning)
| Endpoint | Status | Notes |
|---|---|---|
| `GET /catalog`, `GET /catalog/{id}` | 🟢 | Read-only; filters `type`/`risk`. Dev-seeded (5 resources). |
| `POST /access-requests` | 🟢 | Idempotency-Key. Auto-advances to UNDER_REVIEW (no separate submit). |
| `GET /access-requests`, `GET /access-requests/{id}` | 🟢 | Role-scoped (owners see own). `kind` filter is best-effort (see caveats). |
| `POST /access-requests/{id}/decision` | 🟢 | Idempotency-Key + If-Match. SoD enforced. |
| `GET /my-access` | 🟢 | **Source of truth for "currently held"** (see caveats). |
| `POST /my-access/{resourceId}/removal` | 🟢 | Idempotency-Key. Creates a `kind=removal` request. |
| → resulting **group membership** | 🟢 | **Real** — 5b activated + consented; approval adds the requester to the resource's Entra group (verified live). |

## Teams (Phase 5c) — `/teams*` — 🟡 LIVE (PR #123, merges live)
| Endpoint | Status | Notes |
|---|---|---|
| `GET /teams` | 🟡 | Role-scoped: `team.read(own)` = teams you created OR are an active member of. `memberCount` included. |
| `POST /teams` | 🟡 | `team.manage`. Idempotency-Key. Tenant-unique name → **409** on dup. |
| `GET /teams/{id}/members` | 🟡 | `team.read(own)` (creator or member). `TeamMember.name` is **null** in v1 (see caveats). |
| `POST /teams/{id}/members` | 🟡 | Idempotency-Key. **Owner-only** (`team.manage(own)` = creator; members are read-only). Re-add reactivates. |
| `DELETE /teams/{id}/members/{userId}` | 🟡 | Owner-only soft-delete (audited). 404 if not an active member. |

Portal-local in v1 (no Entra group backing); no `DELETE /teams/{id}` exists.

## Audit (Phase 6a) — `/audit*` — 🟢 LIVE
| Endpoint | Status | Notes |
|---|---|---|
| `GET /audit` | 🟢 | `audit.read` (SSO_OPS/ADMIN/AUDITOR/SUPER_ADMIN). Filters `actor`/`type`(=resourceType)/`resource`(=resourceId)/`from`/`to`; cursor-paged, newest first. |
| `GET /audit/verify` | 🟢 | Recomputes the hash chain → `{valid, checkedThrough, brokenAt}`. `audit.read`. |

Audit is **derived**, not written by the API: a single leader-elected relay projects every domain event
(`request.*`, `team.*`) from the outbox into a hash-chained, append-only log (actor = the **real** principal,
even while impersonating). `actor`/`seq`/`prevHash`/`hash` are exposed so a client can re-verify. **`seq` is
monotonic but may have gaps** (don't assume contiguity); integrity is the `prevHash` linkage. Rows are
DB-immutable (UPDATE/DELETE denied). Events from before 6a are **not** backfilled — the chain started clean.

## Notifications (Phase 6b) — `/notifications*` — 🟡 LIVE (PR pending, merges live)
| Endpoint | Status | Notes |
|---|---|---|
| `GET /notifications` | 🟡 | The caller's **own** feed (scoped to the real principal, even while impersonating). `{items, unreadCount, nextCursor}`, newest-first. `notifications.read` (every role). |
| `POST /notifications/{id}/read` | 🟡 | Mark one read. Owner-only → **404** if it isn't yours (no existence leak). 204. |
| `POST /notifications/read-all` | 🟡 | Mark all the caller's read. 204. |

Notifications are **derived** (no create endpoint): a SEPARATE outbox consumer (decoupled from audit so it
can never stall the hash chain) fans events out to the people in them. **v1 recipients = the individual named
in the event** — the **requester** on a decision/provisioning outcome (`approved`/`rejected`/
`changes_requested`/`active`/`granted`/`provisioning_failed`), the **affected member** on a team add/remove.
Self-actions are suppressed (you're not told about your own action). `createdAt` = the event time (causal
order), not insert time. **Deferred:** reviewer-queue fan-out (notify all reviewers — needs role→user
resolution) and **email/SES** (in-app is the v1 channel; SES + oid→email is a tracked follow-up,
CR-20260629-1610). Eventually-consistent: an action shows in the feed sub-second after it commits, not within
the same request.

## Not built yet — mock from the contract
| Area | Endpoint(s) | Status | Phase |
|---|---|---|---|
| Assistant (7) | `POST /assistant/chat` | 🛑 501 | 7 (stub only in v1) |

## Suggested frontend build order
Auth/identity → onboarding → access → teams → audit → notifications are all 🟢/🟡 (real or merging-live) —
build those for real. Only the 🛑 assistant is stubbed. Onboarding/access provisioning is fully real, so
there's no longer a simulated-side-effect caveat for the FE. Audit + notifications are eventually-consistent
(derived from the event stream) — don't poll them to confirm an action; use the action's own response.
