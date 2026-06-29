# CR-20260629-1610 — v1 notify: SES email + oid→email resolution + reviewer-queue fan-out (deferred together)

- **ID:** CR-20260629-1610
- **Date:** 2026-06-29 16:10 EDT
- **Author:** Senior-architect review of the Phase 6b design note (Claude)
- **Target:** Phase 6 issue #74 / Phase 6b PR / ADR-0022
- **Status:** Open (candidate) — deferred follow-up; in-app notifications ship in 6b without it
- **Related:** [app/src/main/java/com/eop/notify/](../../app/src/main/java/com/eop/notify/) · [app/src/main/java/com/eop/directory/](../../app/src/main/java/com/eop/directory/) (Graph plumbing to reuse) · [DECISIONS.md](../../DECISIONS.md) (ADR-0022) · SES chore #75

## Context

Phase 6b ships the **in-app** notification feed as the guaranteed channel — no external dependency, no
consent, always-on. Three related capabilities were **deliberately deferred** (decisions 2/3/4 of the 6b
review) because each adds an external dependency, a consent, or a human verification step that should not gate
the in-app feature:

1. **SES email** — an out-of-band copy of each notification. Needs a verified SES domain/email identity (a
   **human** must confirm verification / add DNS), `ses:SendEmail` IAM (Terraform), and a per-notification
   `email_status` (PENDING→SENT/FAILED→DEAD) with a retry sweep + dead-letter.
2. **oid→email resolution** — the outbox events carry **oids, not emails** (same gap as `TeamMember.name`).
   SES can't send without resolving the recipient's email, which needs a Graph directory lookup
   (`User.Read.All` — a **new admin consent**) reusing the WIF app-token plumbing in the `directory` module.
3. **Reviewer-queue fan-out** — "notify all reviewers when a request enters review" needs the **same** Graph
   directory capability (role→user enumeration). v1 only notifies individuals already named in the event.

## Requested changes (when undeferred)

- Add the SES Terraform module (domain/email identity + IAM) with the human verification step documented;
  gate the sender behind an activation flag (mirror of the provisioning `simulate` flags) so it's dark until
  the identity is verified.
- Add `notify.notifications.email_status` (+ a retry/dead-letter sweep) — the in-app row stays the guaranteed
  unit; SES is best-effort layered on top and **must never** block `notified_at` or touch audit.
- Add a `directory`-backed oid→email resolver + the reviewer-set resolver behind the `User.Read.All` consent;
  `notify` would then gain a `directory` dependency (update `notify_module_boundary`).

## Resolution

**Open (candidate).** Carded for tracking. Build when the `User.Read.All` consent + SES identity verification
are scheduled — **light all three together** (they share the one Graph directory capability), not piecemeal.
In-app notifications (ADR-0022) are unaffected and ship in 6b now.
