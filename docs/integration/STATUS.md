# Endpoint status map — live / mock / not-built

**Backend agent owns this file.** Updated as each phase lands. Base path for all endpoints: **`/api/v1`**
(except the BFF `/auth/*` helpers). Authoritative shapes: `docs/api/openapi-v1.yaml` (v1.0.1).

Legend:
- 🟢 **LIVE** — implemented + merged to `main`; hit the dev deployment for real.
- 🟡 **LIVE (PR pending)** — built + CI-green, awaiting merge; live once its PR merges.
- 🔵 **SIMULATED** — endpoint is live, but the *side effect* is simulated until a consent gate flips (see note).
- ⚪ **MOCK** — contract-stable but backend not built yet; mock from the OpenAPI.
- 🛑 **501** — endpoint will exist but returns Not Implemented in v1.

_Last updated: 2026-06-29 (Phases 4b + 5a/5b live & verified; teams 5c in PR #123)._

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

## Not built yet — mock from the contract
| Area | Endpoint(s) | Status | Phase |
|---|---|---|---|
| Audit (6) | `GET /audit` | ⚪ MOCK | 6 |
| Notifications (6) | `GET /notifications`, `POST /notifications/{id}/read`, `POST /notifications/read-all` | ⚪ MOCK | 6 |
| Assistant (7) | `POST /assistant/chat` | 🛑 501 | 7 (stub only in v1) |

## Suggested frontend build order
Auth/identity → onboarding → access → teams are all 🟢/🟡 (real or merging-live) — build those for real.
Mock the ⚪ ones (audit, notifications), stub the 🛑 (assistant). Onboarding/access provisioning is now fully
real, so there's no longer a simulated-side-effect caveat for the FE.
