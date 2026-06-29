# SSO Onboarding Portal — frontend (Angular 18)

The production Angular client for the enterprise onboarding portal. Talks to the
Spring **BFF** over a same-origin session cookie; the browser holds no tokens.

- **Source of truth for the API:** [`../docs/api/openapi-v1.yaml`](../docs/api/openapi-v1.yaml) (frozen, v1.0.1).
- **What's live vs. mocked:** [`../docs/integration/STATUS.md`](../docs/integration/STATUS.md).
- **The traps not in the schema:** [`../docs/integration/INTEGRATION-NOTES.md`](../docs/integration/INTEGRATION-NOTES.md).

## Prerequisites

- **Node 20** (see `.nvmrc`). Angular 18 doesn't support Node 24.
  ```bash
  nvm use            # picks up .nvmrc → 20.18.3
  ```

## Run (dev)

The dev server proxies `/api`, `/auth/*`, and `/healthz` to the BFF on
`http://localhost:8080` (see `proxy.conf.json`), so the session cookie behaves
exactly as it will in prod (same-origin). Start the backend first, then:

```bash
npm install
npm start            # ng serve with the proxy → http://localhost:4200
```

Visiting the app calls `GET /api/v1/me`; a 401 redirects you to the BFF login.

## Build

```bash
npm run build        # production bundle → dist/frontend
```

In production the bundle is served **same-origin behind the BFF**, so no CORS.

## Architecture

```
src/app/
  core/
    api/         models.ts (typed mirror of the frozen contract) + api.config.ts
    auth/        AuthService (/me signal), permission matrix, guards, impersonation
    http/        credentials + RFC-7807 interceptors, Idempotency-Key/If-Match helpers
    theme/       light/dark ThemeService (data-theme on <html>)
    services/    cross-cutting services (notifications — mock until Phase 6)
  shared/        status/risk mapping + presentational components (chips, timeline, …)
  layout/        app shell: top bar, role-aware nav, notification bell, impersonation
  features/      dashboard + per-route screens (placeholders today)
src/styles/      design tokens (_tokens.scss) + chip tones (_chip.scss)
```

### Key conventions

- **Authorization is capability-based, server-authoritative.** The UI hides what
  `AuthService.can(permission)` is false for (matrix in `core/auth/permissions.ts`),
  but a `403` from the API is the real answer. Never gate on the display role name.
- **Theming** flows entirely from CSS custom properties in `_tokens.scss`; flipping
  `data-theme` on `<html>` reskins Material and every component.
- **Write requests** carry an `Idempotency-Key` (one per intent, reused on retry)
  and, for transitions/edits, `If-Match` from the resource's last `ETag`.
