# Integration coordination — backend ⇄ frontend

A shared, durable ledger so the backend agent and the Angular frontend agent stay grounded in the same
picture **without re-explaining**. This is **not** a chat channel — agents read files when they run, not
continuously. For anything urgent/breaking, the human relays it; this folder is the async source of record.

## Authority (read this first)
- **`docs/api/openapi-v1.yaml` (frozen, v1.0.1) is authoritative for the API itself** — shapes, status
  codes, params. Generate the frontend client from it. Don't re-describe endpoints here.
- **This folder is authoritative for coordination state only** — what's live vs. mocked, the BFF/session
  model, the semantic traps not visible in the schema, and open cross-agent questions.
- Contract changes go through `docs/change-requests/` (CR governance) and are **additive/non-breaking** by
  policy. A CR that touches the FE means "regenerate the client," not "rewrite."

## Files
- **[STATUS.md](STATUS.md)** — endpoint live / mock / not-built map by phase. **Backend agent owns it**;
  updated as each phase lands. The frontend agent reads it to decide what to hit for real vs. mock.
- **[INTEGRATION-NOTES.md](INTEGRATION-NOTES.md)** — the durable kickoff brief: BFF/session-cookie model,
  identity + impersonation, cross-cutting conventions (RFC-7807, Idempotency-Key, ETag/If-Match,
  pagination), and the semantic caveats the frontend must respect.
- **[QUESTIONS.md](QUESTIONS.md)** — append-only cross-agent Q&A / blocker log.

## The one discipline that makes this work
**Whoever changes reality updates the note in the same change.** A stale STATUS map is the main failure
mode — a confidently-wrong note is worse than no note. (Same rule as the CR governance and the backend
agent's own memory index.)
