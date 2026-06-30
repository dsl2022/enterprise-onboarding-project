# Application User Guide

A hands-on guide to **using** the Enterprise Onboarding Platform — every screen, what each role can do,
and step-by-step demo & testing scenarios. For architecture and the zero-credential auth story see the
[README](../README.md); for the API see the [OpenAPI contract](api/openapi-v1.yaml) and
[RBAC matrix](api/rbac-matrix.md).

> **▶ Live app:** https://d3919zy3gh57yu.cloudfront.net/app — use the `/app` path directly (the bare
> root is a legacy status page). Sign in with **Microsoft**.

---

## Contents
- [Getting a demo account](#getting-a-demo-account) — **contact the maintainer to be provisioned**
- [Signing in](#signing-in)
- [Roles at a glance](#roles-at-a-glance)
- ["View as" — impersonation](#view-as--impersonation)
- [Feature tour](#feature-tour) — dashboard · applications · access · review queue · teams · audit · notifications
- [Demo scenarios](#demo-scenarios) — seven end-to-end walkthroughs
- [Testing without Entra (local dev)](#testing-without-entra-local-dev)
- [Known limitations](#known-limitations)

---

## Getting a demo account

Sign-in is real **Microsoft Entra ID** SSO, and portal capabilities come from **Entra app-roles** that
must be assigned to your account. You can't self-register — accounts are provisioned per tenant.

> ### 🔑 To get a demo account (including elevated / Super Admin access)
> **Contact the project maintainer ([@dsl2022](https://github.com/dsl2022) — open a GitHub issue on this
> repo or reach out via the email on the maintainer's GitHub profile).** Tell them which capability level
> you need (read-only, application owner, or **Super Admin** to drive the full demo). The maintainer will
> assign the appropriate Entra app-role to your account and confirm when it's live.

Why this is manual: app-roles are assigned to **users** on the Entra enterprise app — there is no
open signup, by design (this is an internal-governance tool). A **Super Admin** account is the best one
for a self-guided demo because it can do everything **and** use ["View as"](#view-as--impersonation) to
preview every other role without extra logins.

Passwords are never retrievable (one-way hashed) — only resettable by the maintainer. Guest accounts
(e.g. a Gmail identity invited into the tenant) authenticate at their home identity provider.

---

## Signing in

1. Open **`/app`** and click **Sign in with Microsoft**.
2. You're redirected to Entra (Authorization Code + PKCE). Complete MFA if prompted.
3. You land back in the portal. **The browser never holds a token** — the server keeps the session and
   hands the browser only a `SESSION` cookie (BFF pattern). Closing the tab keeps you signed in until the
   session expires; **Sign out** (top-right menu) clears the session and signs you out of Entra.

What you see after sign-in is **driven by your role** — the side nav only shows sections you can use, and
the server re-checks every action (the UI only *hides* what you can't do; it never *grants*).

---

## Roles at a glance

Roles are bundles of permissions from your Entra app-roles claim. If you hold several, you get the
**union** (most-permissive). Full detail in the [RBAC matrix](api/rbac-matrix.md); the practical summary:

| Role | What it's for | Can do |
|---|---|---|
| **READ_ONLY** | Auditor-lite / observer | Browse catalog, see teams, view own access |
| **APPLICATION_OWNER** | App teams onboarding their app | Create/submit **their** app onboarding, request access, manage **teams they created**, see own resources |
| **SSO_OPERATIONS** | The approval desk | Review & **decide** (approve/reject/request-changes) onboarding + access, rotate registry secrets |
| **ADMIN** | Operations + management | Everything SSO_OPS can, plus create/manage apps & teams org-wide |
| **AUDITOR** | Compliance | Read everything + the review queue + the **audit log**; cannot change anything |
| **SUPER_ADMIN** | Demo / break-glass | **Everything**, plus **"View as"** impersonation |

**Two rules that shape every flow:**
- **Separation of duties (SoD)** — the person who *requested* something can **never approve it**, no
  matter their role. Self-approval returns 403.
- **ABAC ownership** — "own"-scoped permissions only apply to resources you own (your apps, teams you
  created). E.g. an Application Owner sees and edits **their** apps, not everyone's.

> **Heads-up for demos:** in the live tenant only **two** accounts are seeded with roles — a
> **Super Admin** and an **Application Owner**. To show the *other* roles' views, sign in as Super Admin
> and use ["View as"](#view-as--impersonation) rather than asking for five separate logins.

---

## "View as" — impersonation

A **Super Admin** sees a **"View as"** control in the top bar. Pick a role (e.g. READ_ONLY, AUDITOR) and
the whole UI **reduces to that role's capabilities** — nav items disappear, buttons grey out — so you can
demo exactly what that persona experiences.

The important guarantee: impersonation changes **capabilities only, never identity**. For separation of
duties, ownership, and the **audit trail**, you are still the real Super Admin. So you *cannot* launder a
self-approval by submitting as yourself, impersonating Ops, and approving — SoD sees the same real
principal on both sides and blocks it. Every impersonated action is audited as
`actor = <you>, effectiveRole = <impersonated role>`. Click **Stop** / exit to return to full power.

---

## Feature tour

### Dashboard
Your landing page after sign-in — a role-aware summary and quick links into the sections you can use.

### Applications (app onboarding)
Register an internal application for SSO. Lives under **Applications**.
- **List** — your applications (owners see their own; admins see all), each with a **status chip**
  (Draft, Submitted, Under review, Changes requested, Approved, **Provisioning**, **Active**, Rejected).
- **Create** — a guided form to register a new app (name, description, sign-in/redirect details, risk).
  Saves as **Draft**; you can edit until you submit.
- **Detail + lifecycle timeline** — the full state history rendered as a timeline. From here an owner
  **Submits** for review; a reviewer **Approves / Rejects / Requests changes**.
- **Real provisioning** — on approval the status moves to **Provisioning**, an async worker calls
  Microsoft Graph to create a **real Entra app registration**, and the status flips to **Active**,
  recording the **client ID**. (This is genuine, not simulated.)

State machine: `DRAFT → SUBMITTED → UNDER_REVIEW → {APPROVED → PROVISIONING → ACTIVE | REJECTED |
CHANGES_REQUESTED → (resubmit)}`.

### Access (catalog, request, My Access)
Browse and request access to downstream resources. Under **Access**.
- **Catalog** — searchable list of requestable resources (e.g. groups/apps). Each has a **Request
  access** action that opens a dialog (duration, justification).
- **My Access** — what you currently **hold** (granted) plus anything **in progress**, with a
  **Request removal** action. Held access is the source of truth, projected from real **Entra group
  membership**.
- **Real provisioning** — on approval, a worker **adds a real Entra group membership** via Graph; the
  request moves to **Granted** and appears in *My Access*. Removal is the same flow in reverse.

### Review queue
The approver's worklist (SSO_OPS / ADMIN / AUDITOR-read / SUPER_ADMIN). Under **Review queue**.
- **One unified queue** for **both** onboarding and access requests awaiting a decision.
- A shared **decision dialog**: **Approve**, **Reject**, or **Request changes** with a comment.
- **SoD enforced** — you won't be allowed to decide your own request (the action is blocked server-side).
- Decisions are **idempotent** and concurrency-safe (two approvers can't both transition one request).

### Teams
Group ownership for delegation. Under **Teams**.
- **List** — teams you can see (creators/members see theirs; admins see all), with member counts.
- **Detail** — members with names, an **"added" date**, and a **"You"** marker for yourself.
- **Manage** — the team's **creator** (or an org-wide Admin/Super Admin) can **add / remove members**.
  Membership is **ABAC scope, not a role**: being added to a team grants **no** portal permissions — only
  read co-ownership of that team. Members can't grow the team (creator-only), keeping delegation
  non-viral.

### Audit log
A tamper-evident record of every action. Under **Audit** (SSO_OPS / ADMIN / AUDITOR / SUPER_ADMIN).
- **Hash-chained** entries (`hash = H(prevHash ‖ row)`) — each row seals the previous, so any edit or
  deletion breaks the chain. The app's DB role can only **INSERT/SELECT** audit rows; it literally cannot
  rewrite history.
- **Chain-integrity badge** — a **Re-verify chain** button calls `/audit/verify`, which recomputes the
  chain and shows **intact** (through which sequence) or **broken at** which sequence.
- **Filter** by **actor**, **type** (resource type, e.g. `request` / `team`), and **resource**; expand a
  row to see detail, hash, and prevHash.
- The **actor is always the real principal** — even under impersonation, with `effectiveRole` recorded
  separately. `seq` is an ordering key (may have gaps), never a count. The log is
  eventually-consistent — a just-taken action appears within a moment.

### AI Assistant (preview)
An **advisory** onboarding helper under **AI Assistant** (visible to roles with `assistant.use`), also
reachable from the **floating chat button** in the lower-right of every page. It's a chat surface meant
to help you draft the wizard forms — descriptions, redirect-URI checks, least-privilege scope
suggestions, group-ownership checks. Two things to know:
- It is **advisory, never authoritative** — it can only *suggest* inputs; it can't submit, approve, or
  provision anything. The governed request engine (RBAC/ABAC/SoD) stays the only thing that changes state.
- In v1 the backend is a **stub**, so the screen is an honest **preview**: send a message and it tells you
  the assistant isn't enabled yet (rather than faking an answer). It activates here automatically when the
  assistant track ships. See
  [assistant-feature-design-and-guardrails.md](assistant-feature-design-and-guardrails.md) for the design.

### Notifications
The top-bar **bell** with an unread badge. Click it for your feed (newest first), each with a per-type
icon; click an item to mark it read, or **Mark all read**.
- Driven by the **live `/notifications` feed**; the badge loads on sign-in and the panel re-fetches each
  time you open it (so a just-taken action shows up).
- **You're never notified of your own actions** (self-suppression). Notifications are **cross-actor**:
  you get one when *someone else* acts on *your* request or team — see
  [Scenario 5](#scenario-5--notifications-cross-actor).

---

## Demo scenarios

These assume a **Super Admin** account (best for a full demo) plus the seeded **Application Owner**
(`testuser`). If you only have one account, use **"View as"** to switch personas where a second role is
needed — except where **SoD** requires two genuinely different identities (Scenarios 1, 2, 5).

### Scenario 1 — App onboarding, end to end
1. Sign in as the **Application Owner** → **Applications → New** → fill in the app → **Save** (Draft).
2. Open the app → **Submit**. Status → **Submitted → Under review**.
3. Sign in as **Super Admin** (or SSO_OPS) → **Review queue** → open the request → **Approve**.
4. Watch the app status go **Approved → Provisioning → Active**, with a real **client ID** recorded.
   *(Provisioning is async — give it a few seconds and refresh the detail page.)*

### Scenario 2 — Access request, end to end
1. As the **Application Owner**: **Access → Catalog** → pick a resource → **Request access** (add a
   justification) → submit.
2. As **Super Admin**: **Review queue** → **Approve**.
3. The request goes **Approved → Provisioning → Granted** (a real Entra group membership is added).
4. Back as the Owner: **Access → My Access** shows the newly held access. Try **Request removal** to run
   the reverse flow.

### Scenario 3 — Separation of duties (the guardrail)
1. As **Super Admin**, submit any request (an app or access request) yourself.
2. Go to the **Review queue** and try to **Approve your own request**.
3. It's **blocked (403)** — the requester can never be the approver, even as Super Admin. This is the SoD
   guarantee; have a *second* identity approve it (Scenario 1/2).

### Scenario 4 — "View as" (role-reduced views)
1. As **Super Admin**, open **"View as"** in the top bar → choose **READ_ONLY**.
2. Notice the nav collapse to read-only sections and action buttons disappear — this is exactly what a
   read-only user sees.
3. Try **AUDITOR** to reveal the **Audit** section. **Stop** to return to full Super Admin.
4. (Bonus) Take an action while impersonating, then check the **Audit log**: it's attributed to **you**
   (the real Super Admin) with the impersonated role recorded as `effectiveRole`.

### Scenario 5 — Notifications (cross-actor)
Because self-actions are suppressed, you need two identities:
1. As the **Application Owner** (`testuser`), submit an access or onboarding request (Scenario 1/2).
2. As **Super Admin**, **Approve** it.
3. Switch back to the **Application Owner** and open the **bell** → you'll see **"Access granted"** /
   **"Request approved"**.
4. Alternatively: as Super Admin, **add `testuser` to a team** (Teams → detail → add member) → as
   `testuser`, the bell shows **"Added to a team."**
   *(Only actions taken **after** you're set up generate notifications — there's no historical backfill.)*

### Scenario 6 — Teams & delegation
1. As **Super Admin** or **Admin**: **Teams → New** → create a team.
2. Open it → **add a member**. Note the member gets **no new portal powers** — only co-read of the team.
3. Sign in as that member: they can **see** the team but **cannot** add/remove members (creator-only).

### Scenario 7 — Audit trail & tamper-evidence
1. As **Auditor** (or Super Admin / via "View as"): open **Audit**.
2. Click **Re-verify chain** → the badge confirms the chain is **intact** through the latest sequence.
3. **Filter** by actor or type (`request`, `team`) and **expand** a row to see its hash / prevHash and
   detail. Point out that the actor is the real principal and the records are append-only & immutable.

---

## Testing without Entra (local dev)

For UI development you don't need a live tenant. The dev build ships a **mock identity** so the whole
shell is browsable locally:

```bash
cd frontend && npm ci && npm start   # http://localhost:4200
```

- `GET /me` and impersonation are **faked** by a dev-only interceptor (tree-shaken out of prod builds).
- **Switch the mock role at runtime** via the browser console / localStorage, then reload:
  - `localStorage['eop.mockRole']` — your "logged-in" role (default `SUPER_ADMIN`).
  - `localStorage['eop.mockImpersonate']` — a role to "View as" (only honored when the real role is
    `SUPER_ADMIN`).
- The **notifications** bell uses a small seeded feed in dev (with the same event types the backend
  emits) so the panel is populated without a BFF. In production it uses the **real** `/notifications`
  feed (and degrades to an empty feed if the backend is briefly unreachable — it never breaks the shell).

CI runs lint, a **contract-drift gate** (the typed client must match the frozen OpenAPI), build, and
headless unit tests on every PR. See the [README](../README.md#running-it) for the backend container and
[RUNBOOK.md](../RUNBOOK.md) for full bootstrap.

---

## Known limitations

- **Assistant** ships as a **preview** — the UI is live but the backend is an intentional **stub**
  (returns `501`), so it tells you it isn't enabled yet rather than answering; the full RAG/tools track is
  deferred and lights up the existing screen automatically when it lands.
- **Notification click-through** marks the item read but does **not yet deep-link** to the underlying
  resource — the frozen `Notification` schema has no routable discriminator yet (a future contract
  change + detail routes).
- **Only two roles are seeded** in the live tenant (Super Admin + Application Owner); use **"View as"**
  for the rest.
- Provisioning is **asynchronous** — statuses pass through **Provisioning** before going **Active /
  Granted**; refresh after a few seconds. The audit/notification feeds are **eventually-consistent**
  (sub-second), so don't treat them as instant write-confirmations.
