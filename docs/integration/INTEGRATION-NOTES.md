# Frontend integration notes (the kickoff brief)

The durable version of "what the frontend agent needs to know that isn't in the OpenAPI schema." Read
alongside `STATUS.md` (what's live) and `docs/api/openapi-v1.yaml` (the shapes).

## 1. It's a BFF — not a token-in-browser SPA
The backend is a **Backend-for-Frontend**: Spring Security `oauth2Login` with a **server-side session**
stored in **Redis**, carried by a **session cookie**.

- **The browser does no OIDC and handles no tokens.** No MSAL, no bearer headers, no token refresh in
  Angular. Authentication = "do I have a valid session cookie."
- **Login:** navigate the browser to the BFF login entry (`GET /auth/login` → redirects to Entra → back to
  the app). It's a full-page redirect, not an XHR.
- **Identity:** `GET /api/v1/me` returns the current user, `roles[]`, the display `role`, and impersonation
  state. Call it on app load; a 401 means "not logged in" → send the user to login.
- **Logout:** RP-initiated (clears the session + signs out of Entra). Confirm the exact path against the
  auth module / contract.
- **Hosting implication:** simplest is to **serve the Angular bundle same-origin behind the BFF** so the
  session cookie just works. If the SPA is served from a different origin in dev, you need a dev proxy (or
  CORS + `SameSite`/credentials config) — **see the open decision at the bottom; confirm with the human.**

## 2. Impersonation (SUPER_ADMIN only)
- `POST /api/v1/impersonation {"role":"READ_ONLY"}` → subsequent requests use that role's **permissions**;
  `GET /me` then shows `impersonating.role`. `DELETE /api/v1/impersonation` restores.
- Permissions follow the impersonated role; **identity, separation-of-duties, and audit always use the real
  principal.** Render a clear "impersonating X" banner when `/me` reports it.

## 3. Authorization model (drives what UI to show)
- RBAC is a **union of permissions** across the user's roles, most-permissive scope. The display `role` from
  `/me` is for the header/banner only — **never gate UI on the role name; gate on capability.**
- The backend enforces everything server-side; the FE should mirror it for UX (hide actions the user can't
  do) but treat 403 as the real authority.
- **Scope `OWN`** means owners see only their own rows — list endpoints already filter server-side, so just
  render what you get.

## 4. Cross-cutting conventions
- **Errors:** RFC-7807 `application/problem+json` (`type`, `title`, `status`, `detail`). 401 (no session),
  403 (permission/ownership/SoD), 404, 409 (conflict / illegal transition), 412 (stale ETag), 422
  (validation / idempotency-key reuse with a different body). Render `detail`.
- **Idempotency-Key:** **required** on the creating/transition POSTs (`/applications`, `/submit`,
  `/decision`, `/access-requests`, access `/decision`, `/my-access/.../removal`). Generate a UUID per
  user-intent, **reuse it on retry** of that same intent. Replay → the original response; same key + a
  *different* body → 422.
- **ETag / If-Match (optimistic concurrency):** single-resource responses carry a quoted `ETag` (an integer
  version). On `submit`/`decision`/`PATCH`, send it back as `If-Match`. A `412` means "someone changed it —
  re-fetch and retry." Keep the latest ETag per open resource.
- **Pagination:** opaque cursor — `?cursor=&limit=` (default 20, max 100); responses are
  `{ items, nextCursor }`. Treat the cursor as opaque; `nextCursor: null` = last page.

## 5. Semantic caveats (the traps not visible in the schema)
1. **`/my-access` is the source of truth for "currently held"** — NOT a request's status. A **removal**
   request ends in status `GRANTED` meaning *"removal completed."* Never infer "the user has access" from a
   request reaching `GRANTED`; read `/my-access`.
2. **Access `REQUEST_CHANGES` dead-ends** — there is no `/access-requests/{id}/submit` (unlike onboarding's
   `/applications/{id}/submit`). v1 behavior = the requester **opens a new request**. Either don't surface
   `REQUEST_CHANGES` for access decisions, or guide the user to re-request. (Tracked: CR-20260628-2235.)
3. **`duration` on an access request is informational** — v1 does **not** auto-expire grants. Don't render a
   live "expires in N days" countdown as if enforced. (Tracked: CR-20260628-2240.)
4. **An onboarded app returns a client ID only** — `Application.ReadWrite.OwnedBy` creates a registration,
   **not a sign-in-capable app** (no service principal in v1). Don't imply "the app is live / can sign in."
5. **Onboarding `name`/`env` are immutable** after create; `PATCH` merges the other fields only.
6. **`uris` (create) projects to `redirectUris`** (read) — a deliberate contract rename; the generated
   client handles it, just don't be surprised.
7. **`TeamMember.name` is `null` in v1** — portal-local teams store only the member's `userId` (oid); the
   display name isn't resolved (no directory lookup yet). Render from your own identity source (e.g. `/me`
   for the current user) or show the id; don't expect a populated `name`.
8. **Teams: `team.read` is broad, `team.manage` is owner-only.** A user sees a team if they created it OR
   are an active member (`GET /teams` is scoped that way; members can read the roster). But **only the
   creator** (or ADMIN/SUPER_ADMIN) can add/remove members — a non-creator member gets **403** on
   `POST/DELETE …/members`. Gate the "manage members" UI on being the team's creator, not just a member.
   Team **name** is tenant-unique (dup `POST /teams` → 409); there is **no `DELETE /teams/{id}`** (teams
   aren't deletable via the API in v1).

## 6. Open decision (confirm with the human)
- **Where does the Angular app live?** Same repo / sibling folder / separate repo — and is it served
  **same-origin behind the BFF** (recommended; cookie just works) or **cross-origin with a dev proxy**?
  This decides whether the FE needs CORS/proxy config. Until confirmed, assume same-origin behind the BFF.
