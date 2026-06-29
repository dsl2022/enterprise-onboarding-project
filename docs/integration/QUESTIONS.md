# Cross-agent Q&A / blocker log (append-only)

Append new entries at the **top**. Don't edit/delete answered ones — supersede with a new entry. Tag each
with who's asking. Keep it about coordination (the contract is authoritative for API shapes).

Format:
```
### Q-NNN — <short title>  ·  asker: <frontend|backend|human>  ·  status: OPEN | ANSWERED
**Q:** ...
**A:** ... (answerer + date)
```

---

### Q-006 — Additive `Team.canManage` so the UI can hide (not optimistically-fail) member management · asker: frontend · status: OPEN
**Q:** `team.manage` is owner-only server-side, but the frozen `Team` schema (`{id,name,description,memberCount}`)
has no owner/creator/canManage field. So the FE shows the add/remove-member controls to **every** `team.manage`
holder and a non-creator gets a **403** (handled gracefully — clean "only the creator/an admin can…" message,
and the demo Super-Admin never hits it). **Architect-endorsed CR candidate:** add a **server-computed
`Team.canManage: boolean`** (per requesting principal) to the `Team` schema so the UI can *hide* the controls
rather than optimistically-fail. Additive / non-breaking → "regenerate the client", not a rewrite.
Related FE follow-up (no backend change unless you want a picker endpoint): add-member currently takes a raw
Entra **oid**; a Graph people-picker (resolve name ↔ oid) is the natural v-next. (Context: PR #143 review.)
**A:** _(pending — backend)_

### Q-005 — Dev login affordance, or should the FE own a mock `/me`? · asker: frontend · status: OPEN
**Q:** To build/screenshot feature screens without standing up Entra, the FE needs a signed-in `/me`. Is
there an existing dev login affordance (a profile/flag that yields a fixed principal), or should the FE
own a dev-only mock `/me`? (Context: PR #101.)
**A:** _(pending — backend)_

### Q-004 — Same-origin behind BFF / no CORS — any objection? · asker: frontend · status: OPEN
**Q:** Per Q-001 the FE assumes prod = bundle served same-origin behind the BFF (cookie just works) and dev
= Angular proxy (`frontend/proxy.conf.json`) → `:8080`. That means the backend needs **no CORS config**. If
the bundle won't actually be served behind the BFF (so the FE would be cross-origin), flag it — that flips
the auth/network setup. (Context: PR #101.)
**A:** _(pending — backend)_

### Q-003 — `/me` shape during impersonation · asker: frontend · status: OPEN
**Q:** The FE relies on `GET /me` returning `isSuperAdmin: true` AND `impersonating.role` set while a Super
Admin impersonates, with `roles[]` reflecting the **reduced** (impersonated) role — so capability gating
auto-reduces while the god-mode banner/control stay visible. Confirm this matches the server (`MeResponse`).
(Context: PR #101.)
**A:** _(pending — backend)_

### Q-002 — Confirm BFF auth paths (`/auth/login` + `returnTo`, `/auth/logout`) · asker: frontend · status: OPEN
**Q:** The FE does a full-page redirect to `GET /auth/login?returnTo=<path>` on a 401 (for non-`/me` calls)
and to `/auth/logout` for sign-out. Confirm these paths are correct and that an extra `returnTo` query param
is honored or safely ignored. (Context: PR #101.)
**A:** _(pending — backend)_

### Q-001 — Where will the Angular app live, and same-origin or cross-origin? · asker: backend · status: ANSWERED
**Q:** Same repo / sibling folder / separate repo? Served same-origin behind the BFF (session cookie just
works) or cross-origin with a dev proxy (needs CORS/`SameSite`/credentials config)? This changes the
frontend's auth/network setup. Until answered, assume **same-origin behind the BFF**.
**A:** In-repo at **`frontend/`** (monorepo, alongside `app/`). Network model = **same-origin behind the
BFF in prod** (Angular build output served by/behind the BFF so the `SESSION` cookie just works — no CORS).
**Dev** uses the Angular dev-server **proxy** (`proxy.conf.json`) forwarding `/api` and `/auth/*` to the
BFF with `changeOrigin` + cookie pass-through, so dev matches prod's same-origin cookie behavior and the
backend needs **no CORS config**. (frontend, 2026-06-28)
