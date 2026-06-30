# CR-20260630-0007 — v1 assistant (Phase 7 → Rung 1 track): reconcile frozen chat contract, re-derive tools per request-type, split audit routing

- **ID:** CR-20260630-0007
- **Date:** 2026-06-30 00:07 EDT
- **Author:** Senior-architect review of the assistant Rung 1 plan set (backend / frontend / test) (Claude)
- **Target:** ADR-0023 (Phase 7 assistant stub) / [docs/assistant-feature-design-and-guardrails.md](../assistant-feature-design-and-guardrails.md) / the three Rung 1 plan docs (`ASSISTANT_BACKEND_PLAN`, `ASSISTANT_FRONTEND_PLAN`, `ASSISTANT_TEST_STRATEGY`)
- **Status:** Accepted — items 1–7 folded into the 2026-06-30 plan revision (see **Disposition** below). **One residual open item** (R1: provenance-correlation mechanism) must be pinned before Rung 1 build; items 2 & 3 remain **contract-freeze** changes to route through the contract gate.
- **Related:** [docs/api/openapi-v1.yaml](../api/openapi-v1.yaml) (`AssistantChatRequest`/`AssistantChatResponse`/`ProposedAction` L608–625; `ApplicationCreate`/`AccessRequestCreate`/`CatalogResource`) · [app/src/main/java/com/eop/assistant/AssistantController.java](../../app/src/main/java/com/eop/assistant/AssistantController.java) (Rung 0 stub) · [app/src/main/java/com/eop/audit/](../../app/src/main/java/com/eop/audit/) (Phase 6a — projection target) · [app/src/main/java/com/eop/platform/OutboxWriter.java](../../app/src/main/java/com/eop/platform/OutboxWriter.java) · [app/src/main/java/com/eop/authz/Permission.java](../../app/src/main/java/com/eop/authz/Permission.java) (`ASSISTANT_USE`) · [DECISIONS.md](../../DECISIONS.md) (ADR-0023)

## Context

The Rung 1 plan set is well-founded — it sits on the reviewed threat model
(`assistant-feature-design-and-guardrails.md`: OWASP LLM Top 10 / NIST AI RMF / MITRE ATLAS, the lethal-trifecta
lens, the autonomy ladder) and its safety thesis (deterministic authority in Java around an untrusted model,
proven by mocking the model malicious) is correct. Rung 0 already ships: the `assistant.use` gate, the 501
stub (`AssistantController`), and the chat contract are live. **Direction endorsed.**

But the plan is written as "assemble pre-built reusable parts," and review against the *built* code shows two
integration points where the plan diverges from what is already frozen/shipped, plus a tool set that does not
cleanly map to the real request types. These must be reconciled **before** a Rung 1 design-note is cut, because
two of them touch the **frozen v1 contract** and one re-architects how the assistant touches the Phase 6a audit
chain. Scope decisions (per the review): **Rung 1 only** for the first real build; **split** advisory telemetry
out of the governance audit chain.

## Requested changes

### 1. Scope = **Rung 1 only** for the first real build; Rung 2 strictly additive

Four advisory tools on typed input + static policy; **no retrieval, no write** (trifecta broken at two legs;
worst case = a suggestion the user rejects). Architect Rung 1 so Rung 2 (permission-aware RAG) slots in
**additively** — but **defer to the Rung 2 increment**: the retrieval ACL-staleness problem (the
`PermissionAwareRetriever` "reuses the `owns()` predicate" claim is *not* free — `owns()` is live over a hydrated
`Ownable`, retrieval filters on ACL metadata frozen at ingest; now that 5c made `teamMemberIds()` dynamic, a
chunk's stamped ACL goes stale on any membership change → needs an explicit re-index / invalidation answer), the
ingestion ETL, and the gating nightly adversarial eval. None of these are Rung 1 work; the plan should say so.

### 2. Reconcile the plan's chat shape with the **already-frozen** contract (CONTRACT CHANGE)

Both plan docs say "freeze the `/assistant/chat` contract when Rung 1 is built." **It is already frozen** (the
stub shipped against it). The plan's shape diverges from `openapi-v1.yaml` L608–625 on four axes:

| Aspect | Frozen contract (today) | Plan docs (§4) |
|---|---|---|
| Transport | **synchronous** `AssistantChatResponse` JSON | **SSE token stream** |
| Input | `message: string` (single) + `context: object` (freeform) | `messages: [{role,content}]` + typed `context{surface,formType,step,fieldValues}` |
| Output | `proposedActions: [{id, tool, args, requiresApproval}]` | `suggestions: [{id, tool, field?, value?, rationale, sources?}]` |
| Field binding | **no `field`** on `ProposedAction` | FE accept-into-form-control needs `field` |

Decide and record **one** of:
- **(2a) Align the plan to the frozen synchronous shape** (recommended for Rung 1: advisory, no-write, low
  latency; dodges SSE error-framing + the Idempotency-Key-on-a-stream mismatch — `IdempotencyService.execute`
  returns a buffered `ResponseEntity<String>`, not a stream). Add only the missing `field` binding + a
  `rationale` to `ProposedAction` as a minor `1.x` contract bump.
- **(2b) Amend to SSE streaming** — then it is a **breaking** change to a frozen contract and must specify the
  wire protocol: a terminal `event: done` vs `event: error` discriminator so the FE can distinguish
  "clean end" from "model died mid-stream" (the whole fail-closed story depends on it), and an explicit
  Idempotency-Key exemption for chat (acceptable — it is no-write).

Either way: drop "freeze when built" from both plan docs; the contract is the existing seam.

### 3. Re-derive the tool set **per request-type**, mapped to real fields (CONTRACT CHANGE to the `tool` enum)

The four tools all map to real fields, but they span **two different request types** and the set has a gap. The
frozen `ProposedAction.tool` enum (L623) hard-codes exactly four names — so this is a **contract amendment**, not
just code.

| Tool | Request type | Real contract field | Note |
|---|---|---|---|
| `draftDescription` | onboarding | `ApplicationCreate.description` | ✓ |
| `validateRedirectUris` | onboarding | `ApplicationCreate.uris` (**not** `redirectUris`) | create/patch field is `uris`; response is `redirectUris` — tool must target `uris` |
| `recommendScopes` | onboarding | `ApplicationCreate.scopes` | ✓ |
| `checkGroupOwnership` | **access-request** | runs the user's ABAC vs `CatalogResource.mappedGroup` | belongs to the access flow, not onboarding |
| `draftJustification` | **access-request** | `AccessRequestCreate.justification` (required) | **GAP — not in the enum today; net-new** |

Actions: (a) document each tool's owning **request type** (3 onboarding, 2 access) so the FE wizard-panel binds
the right tool chips per surface; (b) fix `validateRedirectUris` to write `uris`; (c) add `draftJustification`
to the `ProposedAction.tool` enum (the access justification is required free-text — the obvious missing assist);
(d) confirm the per-tool→field mapping is the allow-list the validation layer enforces.

### 4. Audit routing = **split**, and wire it as a projection (not a direct write)

Phase 6a built the audit log as a **derived projection**: the single advisory-locked `OutboxRelay` →
`AuditProjector` is the *only* appender to `audit.audit_events` (hash-chained, immutability trigger,
`/audit/verify`). The plan's `AssistantAudit` must therefore:
- **Emit `aiAssisted` to `messaging.outbox` via `OutboxWriter`** — never write `audit_events` directly (would
  violate the single-writer invariant and hit the immutability trigger).
- **Split the stream:** only **consequential** assists — a suggestion **accepted into a submitted request** —
  go on the governance chain (carrying `actor`, `effectiveRole`, and a defined `resource_type`/`resource_id`:
  the target onboarding application or access request). **Raw chat telemetry and refusals go to ordinary
  observability (Micrometer), not the hash chain** — keeping the tamper-evident governance ledger free of
  high-volume advisory noise and off the single serialized writer.
- Define the **`resource_type`/`resource_id` binding** for an assist event (unmodeled today).

### 5. `aiAssisted` provenance: backend-correlated, not client-asserted

FE §5 marks a field "AI-assisted" in the submit payload — a client-controlled audit attribute, weak for a
governance portal. The backend already audits every tool invocation (`actor = user`); derive the `aiAssisted`
provenance by **correlating the assist event to the submit server-side**, not by trusting a submit-time flag.

### 6. RBAC: resolve the auditor/read-only contradiction; name the per-role tool gate as new code

Backend §3 / FE §7 say "auditor/read-only get read/explain tools only," but those roles **do not hold
`assistant.use`** (granted only to APPLICATION_OWNER, SSO_OPERATIONS, + admin tiers) — they are gated out (403 →
`AssistantUnavailable`). Pick one: **(a)** drop the auditor/read-only tool-subset language (recommended — they
have no assistant), or **(b)** a separate CR grants them `assistant.use` for a read-only assistant. Also: the
"per-role **tool** allow-list" is **new authorization logic in the assistant module**, not RBAC-matrix reuse
(the matrix is permission-level, not per-tool) — state it as such.

### 7. Smaller hardening (fold into the design-note)

- **Wording:** Rung 1 is injection-**contained** (blast radius = bad suggestion), not injection-**proof** — it
  still ingests untrusted user text; only the write + exfil legs are removed.
- **`checkGroupOwnership` is the leak-prone tool** (exists-but-not-yours vs doesn't-exist via error-shape /
  timing) — add that distinction explicitly to the adversarial corpus, not just the value.
- **Global** cost ceiling trips the **kill-switch** (not only per-request caps) — a streaming/long-poll endpoint
  behind a session is a real wallet-DoS vector (LLM10).
- **Assistant diagrams are net-new** (only portal + request-lifecycle SVGs exist in `docs/diagrams/`) — the
  four diagrams the backend plan promises are build work, not a render of existing assets.
- **Pre-check F1:** confirm `bedrock:InvokeModel*` is actually on the ECS task role and Claude model
  availability in `us-east-1` before committing Bedrock.

## Resolution

**Open (candidate).** Routed to the architect for the Rung 1 design-note. Items **2 and 3 are contract-freeze
changes** and must go through the contract gate (pick 2a/2b; amend the `tool` enum + add the `field` binding).
Items 4–6 are correctness/governance must-addresses for the Rung 1 build. Item 1 fixes the scope; item 7 is
hardening. The threat-model doc, the `assistant.use` gate, the 501 stub, and the Phase 6a audit chain are all
real and reused — this CR aligns the plan to them. Build Rung 1 after these are folded in.

## Disposition — 2026-06-30 plan revision

The three plan docs (backend / frontend / test) were revised against this CR. Per-item disposition:

| Item | Disposition | Evidence in the revised plan |
|---|---|---|
| 1 — Rung 1 only; Rung 2 additive | ✅ Resolved | BE §4 Rung 2 block now owns ACL-staleness + re-index/invalidation, ingestion ETL, gating eval; F2 cites this CR |
| 2 — reconcile frozen chat contract | ✅ Resolved — **chose 2a (synchronous)** | BE §4 + FE §1/§4 align to `AssistantChatResponse` JSON (not SSE); "freeze when built" removed; `field`+`rationale` as a minor `1.x` bump; streaming parked as a Rung 2 amendment (FE §10) |
| 3 — re-derive tools per request-type | ✅ Resolved | BE §4 5-tool table (3 onboarding / 2 access); `validateRedirectUris`→`uris`; `draftJustification` added to the `tool` enum; FE §2/§7 per-surface chips |
| 4 — audit split + projection wiring | ✅ Resolved | BE §3 emits via `OutboxWriter`, never `audit_events`; consequential-only on the governance chain; raw chat/refusals → Micrometer; ArchUnit rule added (test §3.3) |
| 5 — backend-correlated provenance | ⚠️ Partial — see **R1** | FE §5 drops the client flag and test asserts its absence, but the *correlation mechanism* is unspecified |
| 6 — auditor/read-only + per-role gate is new code | ✅ Resolved — **chose (a) drop** | BE §3 + FE §7: AUDITOR/READ_ONLY gated out (no read-only assistant); per-role tool allow-list named as new module logic |
| 7 — hardening (5 items) | ✅ Resolved | injection-**contained** wording, `checkGroupOwnership` oracle in the adversarial corpus, global ceiling→kill-switch, diagrams net-new, Bedrock IAM pre-check — all present |

### Residual open items (pin before Rung 1 build)

- **R1 (substantive) — name the provenance-correlation mechanism.** Removing the client-asserted `aiAssisted`
  flag (item 5) is correct, but "backend-correlated, consequential-only" is currently an assertion, not a
  mechanism. At submit time the backend must know which submitted field came from which earlier assist, and
  every option has a catch: (i) submit references a `proposedAction.id` → **still client-asserted**, just
  renamed; (ii) **diff submitted value vs proposed value** → silently misses once the user edits the field
  (which the plan encourages); (iii) **server-side assist log** keyed to the session, matched at submit →
  soundest, but unbuilt and needs a retention/TTL story. **Decision needed:** pick one (recommend iii, with
  honest *AI-touched* — not *AI-verbatim* — semantics) and record it in the Rung 1 design-note. This also
  defines what `resource_type`/`resource_id` the consequential audit event binds to.
- **R2 (concrete) — fix the tool-count slip in the test strategy.** §3.1's validation-layer bullet and the §4
  matrix "Tool allow-list" row still say "**four** tool names"; the set is now **five** (incl.
  `draftJustification`). The allow-list count is itself a safety assertion — correct both to five.
- **R3 (minor) — `checkGroupOwnership` is a Q&A result, not a field-fill.** It returns a yes/no, so its
  `ProposedAction.field`/`args` are vestigial; `SuggestionChipComponent` (FE §3) assumes accept-fills-a-field
  for all proposals and needs a second render mode for the answer-only tool. No contract change required.

**Net:** items 1–4, 6, 7 resolved; item 5 resolved in principle, pending **R1**. Build Rung 1 once R1 is pinned
and R2/R3 corrected; route the contract additions (items 2 & 3 — `field`, `rationale`, `draftJustification`)
through the contract-freeze gate.
