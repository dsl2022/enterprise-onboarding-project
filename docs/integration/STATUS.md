# Endpoint status map — live / mock / not-built

**Backend agent owns this file.** Updated as each phase lands. Base path for all endpoints: **`/api/v1`**
(except the BFF `/auth/*` helpers). Authoritative shapes: `docs/api/openapi-v1.yaml` (v1.0.1).

Legend:
- 🟢 **LIVE** — implemented + merged to `main`; hit the dev deployment for real.
- 🟡 **LIVE (PR pending)** — built + CI-green, awaiting merge; live once its PR merges.
- 🔵 **SIMULATED** — endpoint is live, but the *side effect* is simulated until a consent gate flips (see note).
- ⚪ **MOCK** — contract-stable but backend not built yet; mock from the OpenAPI.
- 🛑 **501** — endpoint will exist but returns Not Implemented in v1.

_Last updated: 2026-06-28 (Phase 5a built, PR #98 pending merge)._

## Identity / session (Phase 3a) — 🟢 LIVE
| Endpoint | Status | Notes |
|---|---|---|
| `GET /me` | 🟢 | Identity for the SPA: roles[], display role, impersonation state. **Start here.** |
| `POST /impersonation` `{role}` | 🟢 | SUPER_ADMIN only. Switches the *effective* role for permissions; real identity unchanged. |
| `DELETE /impersonation` | 🟢 | Clears impersonation. |
| `GET /auth/login` | 🟢 | BFF login entry (see INTEGRATION-NOTES — browser does no OIDC). |

## Onboarding (Phase 4a 🟢 / 4b 🔵) — `/applications*`, `/review-queue`
| Endpoint | Status | Notes |
|---|---|---|
| `POST /applications` | 🟢 | Idempotency-Key required. |
| `GET /applications`, `GET /applications/{id}` | 🟢 | List is role-scoped (owners see own). ETag on single GET. |
| `PATCH /applications/{id}` | 🟢 | If-Match required. Merge, not replace; `name`/`env` immutable. |
| `POST /applications/{id}/submit` | 🟢 | Idempotency-Key + If-Match. |
| `POST /applications/{id}/decision` | 🟢 | Idempotency-Key + If-Match. SoD enforced. |
| `GET /applications/{id}/timeline` | 🟢 | |
| `GET /review-queue` | 🟢 | Reviewers only (`review.read`); type-agnostic. |
| → resulting **client ID** | 🔵 | Provisioning reaches ACTIVE with a **`sim-<id>`** client ID until 4b consent flips (`Application.ReadWrite.OwnedBy`). FE behavior identical; only the real Entra object is pending. |

## Access governance (Phase 5a) — 🟡 LIVE (PR #98 pending merge)
| Endpoint | Status | Notes |
|---|---|---|
| `GET /catalog`, `GET /catalog/{id}` | 🟡 | Read-only; filters `type`/`risk`. Dev-seeded (5 resources). |
| `POST /access-requests` | 🟡 | Idempotency-Key. Auto-advances to UNDER_REVIEW (no separate submit). |
| `GET /access-requests`, `GET /access-requests/{id}` | 🟡 | Role-scoped (owners see own). `kind` filter is best-effort (see caveats). |
| `POST /access-requests/{id}/decision` | 🟡 | Idempotency-Key + If-Match. SoD enforced. |
| `GET /my-access` | 🟡 | **Source of truth for "currently held"** (see caveats). |
| `POST /my-access/{resourceId}/removal` | 🟡 | Idempotency-Key. Creates a `kind=removal` request. |
| → resulting **group membership** | 🔵 | Simulated (no Graph) until 5b consent flips (`GroupMember.ReadWrite.All`). |

## Not built yet — mock from the contract
| Area | Endpoint(s) | Status | Phase |
|---|---|---|---|
| Teams (5c) | `GET/POST /teams`, `GET/POST /teams/{id}/members`, `DELETE /teams/{id}/members/{userId}` | ⚪ MOCK | 5c (not started) |
| Audit (6) | `GET /audit` | ⚪ MOCK | 6 |
| Notifications (6) | `GET /notifications`, `POST /notifications/{id}/read`, `POST /notifications/read-all` | ⚪ MOCK | 6 |
| Assistant (7) | `POST /assistant/chat` | 🛑 501 | 7 (stub only in v1) |

## Suggested frontend build order
Build the 🟢/🟡 screens first (auth/identity → onboarding → access), mock the ⚪ ones, and stub the 🛑.
The 🔵 note is invisible to the FE (the API contract is identical) — it only matters for "is the real
Entra object there yet" during demos.
