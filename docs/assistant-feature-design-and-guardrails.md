# The Assistant: feature design + safety guardrails (research & opinion)

> **Status:** RESEARCH / DESIGN EXPLORATION — not a contract, not an ADR, not a build commitment.
> This doc exists to think through the *whole* assistant feature (the onboarding chatbot + any
> write-actions) and the guardrails it would need **before** we build anything beyond the v1 **stub**
> (`POST /assistant/chat` and `POST /assistant/actions/{id}/approve` → `501`, see the separate Phase 7 note).
> It synthesizes external best practice (OWASP, NIST, MITRE ATLAS, Anthropic/OpenAI/Google, and the
> prompt-injection literature) with **this** system's existing security primitives. Opinions are called out
> as **Recommendation**. _Author: backend/consultant agent. Date: 2026-06-29._

---

## 0. TL;DR (the opinionated version)

1. **Ship the assistant as strictly *advisory*, never authoritative.** It drafts and suggests; the existing
   governed request engine (RBAC + ABAC + separation-of-duties + the guarded state machine) remains the only
   thing that can actually change state. The LLM is a **typeahead with opinions**, not a decision-maker. If
   the LLM is down, wrong, or hijacked, the worst case must be "the user got a bad suggestion," not "an app
   got provisioned" or "data leaked."
2. **Treat every model output as untrusted input** — the same posture we already take toward user input
   (authorize-before-state, validate, encode). This single mental model collapses most of the OWASP LLM Top
   10 into controls we already have.
3. **Prompt injection is an unsolved, architectural problem.** Do not buy or build a "prompt-injection
   firewall" and call it safe — independent adaptive testing breaks every classifier-based filter. The only
   durable defense is to **deny the assistant the conditions that make injection consequential** (the "lethal
   trifecta") and to enforce all authority **deterministically outside the model**.
4. **The v1 tool set is already a guardrail.** `draftDescription`, `validateRedirectUris`, `recommendScopes`,
   `checkGroupOwnership` are all *advisory/read-only* — none writes, provisions, or grants. Keep it that way
   for as long as possible; earn write-actions later, gated behind the human-in-the-loop `approve` endpoint.
5. **Reuse, don't reinvent.** We already have the four hardest pieces most teams lack: per-user
   **RBAC/ABAC**, **separation-of-duties on the real principal**, an **immutable hash-chained audit log**
   (Phase 6a), and a **human-in-the-loop approval endpoint** in the contract. The assistant's safety story is
   mostly "wire the assistant *into* those, never *around* them."

---

## 1. What the assistant is for

The portal's job is **self-service enterprise onboarding**: a user requests an app registration (Entra) or
access to a catalogued resource, it's reviewed (SoD), approved, and provisioned (Graph over WIF). The wizard
forms are non-trivial — `ApplicationCreate` alone has `name`, `env`, `description`, `grants[]`, `scopes[]`,
`uris[]` (redirect URIs), `group`, `team[]`; access requests need a real `justification`. People get these
wrong (bad redirect URIs, over-broad scopes, weak justifications), which creates review friction and security
risk.

**The value proposition is reducing that friction without reducing governance.** Concretely, the four
contract tools map onto exactly the places users struggle:

| Contract tool | What it helps with | Risk class (v1) |
|---|---|---|
| `draftDescription` | Generate a first-draft app/access `description` or `justification` the user edits | **Advisory** — text only, no write |
| `validateRedirectUris` | Check `uris[]` against policy (https-only, no wildcards, registered domains) | **Read-only validation** |
| `recommendScopes` | Suggest least-privilege `scopes[]`/`grants[]` for the stated purpose | **Advisory** — recommendation only |
| `checkGroupOwnership` | Tell the user whether they can request a catalog resource's `mappedGroup` | **Read-only check** (must run under *their* ABAC) |

Note what's **absent**: no tool creates the app, submits the request, approves anything, or calls Graph. That
is deliberate and is the single most important guardrail in v1 — the assistant proposes *inputs to* the
governed flow; humans still drive the flow.

---

## 2. Design principles (the spine everything hangs off)

1. **Advisory, not authoritative.** The assistant never mutates governed state directly. It produces
   *suggestions* (form drafts, validations, recommendations) and, later, *proposed actions* that a human
   approves through the existing pipeline. (Anthropic frames the safe end of this spectrum as **workflows**
   — "LLMs and tools orchestrated through predefined code paths" — vs. open-ended **agents**; prefer the
   former for "well-defined tasks requiring predictability." <https://www.anthropic.com/engineering/building-effective-agents>)
2. **The model is not a security boundary.** All authorization, ownership, and SoD checks are enforced by
   our deterministic code *outside* the model, exactly as today. Google's SAIF states this directly:
   place "deterministic policy enforcement outside the AI reasoning loop … compensating for the
   non-deterministic, potentially manipulable nature of AI model outputs."
   <https://saif.google/focus-on-agents>
3. **Least privilege, bound to the *user*.** Any tool runs server-side **under the requesting user's
   identity and permissions** — never a broad service identity. The assistant can never do more than the
   human it's helping. (OWASP LLM06 Excessive Agency: "actions taken on behalf of a user are executed …
   in the context of that specific user." <https://genai.owasp.org/llmrisk/llm062025-excessive-agency/>)
4. **Defense in depth, no single magic control.** Layer input handling, output validation, least-privilege
   tools, HITL, audit, and rate limits — because each layer is individually bypassable. (OpenAI: "a single
   [guardrail] is unlikely to provide sufficient protection." <https://cdn.openai.com/business-guides-and-resources/a-practical-guide-to-building-agents.pdf>)
5. **Fail closed and degrade gracefully.** LLM unavailable/slow/over-budget → the assistant disables itself
   and the user completes the form manually. The assistant is never on the critical path of the governed
   workflow.
6. **Auditable and attributable.** Every AI suggestion, every human acceptance, and every executed action is
   distinguishable in the audit trail (reusing Phase 6a). "AI suggested X" ≠ "human Y decided X" ≠ "system
   executed X."

---

## 3. Threat model

### 3.1 OWASP Top 10 for LLM Applications (2025) mapped to *this* system

The 2025 list (published Nov 2024; <https://genai.owasp.org/llm-top-10/>), with how each lands here and what
already mitigates it:

| OWASP 2025 | Relevance here | Primary mitigation in this system |
|---|---|---|
| **LLM01 Prompt Injection** <br><https://genai.owasp.org/llmrisk/llm01-prompt-injection/> | **High.** The #1 risk; unsolved. Direct (user types an override) and indirect (malicious text in a catalog description, an uploaded doc, a request justification the assistant reads). | Deny the lethal trifecta (§4); deterministic authz outside the model; advisory-only tools; HITL on writes. **Not** a filter/classifier. |
| **LLM02 Sensitive Information Disclosure** <br><https://genai.owasp.org/llmrisk/llm022025-sensitive-information-disclosure/> | **High.** The assistant could surface another user's request, an oid, a justification, secrets. | Permission-aware retrieval (reuse RBAC/ABAC at retrieval time, §6); minimization; never put secrets/client-secrets in context; output scoping to the real principal. |
| **LLM03 Supply Chain** <br><https://genai.owasp.org/llmrisk/llm032025-supply-chain/> | **Medium.** Model provider, SDKs, any RAG components. | Vetted provider w/ no-train/zero-retention terms; pinned SDK versions; SBOM (we already gitignore/structure deps). |
| **LLM04 Data & Model Poisoning** <br><https://genai.owasp.org/llmrisk/llm042025-data-and-model-poisoning/> | **Low/Med.** We don't train models; RAG corpus poisoning is the realistic vector (e.g., a malicious catalog/description entry). | Trusted, reviewed RAG sources only; treat retrieved content as untrusted (§6); no fine-tuning on user data in v1. |
| **LLM05 Improper Output Handling** <br><https://genai.owasp.org/llmrisk/llm052025-improper-output-handling/> | **High.** Model output flows into our DB, the Angular UI, and (later) tool args. Markdown/HTML/script injection, or a malformed "draft" persisted verbatim. | Treat output as untrusted: schema-validate, context-encode (the SPA already renders; enforce CSP + safe rendering), parameterized writes, **never** `eval`. |
| **LLM06 Excessive Agency** <br><https://genai.owasp.org/llmrisk/llm062025-excessive-agency/> | **High (future).** The risk that grows when tools gain write power. Three roots: excessive functionality, permissions, autonomy. | Minimal tool set; tools run under the user's RBAC/ABAC; HITL approval for any consequential action; no open-ended tools. |
| **LLM07 System Prompt Leakage** <br><https://genai.owasp.org/llmrisk/llm072025-system-prompt-leakage/> | **Medium.** Attackers can extract the system prompt (MITRE ATLAS AML.T0056). | Put **no** secrets, no policy logic, no role lists in the system prompt — all controls live in code. Assume the prompt is public. |
| **LLM08 Vector & Embedding Weaknesses** <br><https://genai.owasp.org/llmrisk/llm082025-vector-and-embedding-weaknesses/> | **High (if RAG).** A shared vector index leaking across users/tenants; embedding inversion; retrieval-time access bypass. | Permission-partitioned retrieval; per-document ACL metadata filtering at query time; logical isolation (§6). |
| **LLM09 Misinformation** <br><https://genai.owasp.org/llmrisk/llm092025-misinformation/> | **High.** A confidently-wrong scope recommendation or a fabricated "this URI is fine." | Ground in real data (catalog, policy); cite sources; label as AI-generated; **never auto-apply** — the human and the reviewer remain. |
| **LLM10 Unbounded Consumption** <br><https://genai.owasp.org/llmrisk/llm102025-unbounded-consumption/> | **Medium.** Token/cost DoS ("denial of wallet"), MITRE ATLAS AML.T0034 Cost Harvesting. | Per-user rate limits + token/cost caps + request timeouts; input size limits; the assistant is best-effort. |

*(2025 reorganized the 2023 list: Sensitive Info Disclosure rose 6→2, Excessive Agency 8→6; System Prompt
Leakage and Vector/Embedding Weaknesses are new; DoS→Unbounded Consumption; Overreliance→Misinformation;
Insecure Plugin Design and Model Theft were merged away. <https://genai.owasp.org/llm-top-10-2023-24/>)*

### 3.2 The lethal trifecta (the lens that actually drives the architecture)

Simon Willison's framing (<https://simonwillison.net/2025/Jun/16/the-lethal-trifecta/>): an assistant becomes
acutely dangerous when it simultaneously has **(1) access to private data + (2) exposure to untrusted content
+ (3) the ability to externally communicate/exfiltrate.** Injection becomes catastrophic only when all three
are present, because the model "cannot reliably distinguish the importance of instructions based on where
they came from — everything gets glued together into a sequence of tokens."

**This is our single most useful design constraint.** For each assistant capability, ask which legs it adds:

- **Private data** — yes, intrinsically (the user's requests, the catalog, ownership). Hard to remove.
- **Untrusted content** — present the moment the assistant reads a `description`, a `justification`, an
  uploaded doc, or a catalog entry authored by someone else. **Indirect injection lives here.**
- **External communication / exfiltration** — *this is the leg we can and should deny.* The assistant has
  **no** tool that sends data outbound (no email, no web fetch, no arbitrary HTTP, no Graph write from the
  chat path). Its only "output channel" is a suggestion rendered back to the same authenticated user.

> **Recommendation:** Treat "no exfiltration leg" as an **invariant**, not a convenience. The fastest way to
> make this assistant dangerous is to give it a tool that can call out (web browsing, sending notifications
> to arbitrary addresses, writing to Graph from the chat loop). Any such tool is a design-review gate.

### 3.3 MITRE ATLAS (AI-specific TTPs worth threat-modeling against)

ATLAS (<https://atlas.mitre.org/>, AI counterpart to ATT&CK) names the LLM techniques directly. The
relevant chain for a tool-using assistant: **AML.T0051.001 Indirect Prompt Injection → AML.T0053 LLM Plugin
Compromise** (injected model abuses its connected tools), plus **AML.T0056 Meta/System Prompt Extraction**,
**AML.T0057 LLM Data Leakage**, **AML.T0054 Jailbreak**, and **AML.T0034 Cost Harvesting**. Use these as
named test cases for red-teaming (§7.5).

### 3.4 Standards backbone (governance)

**NIST AI RMF + Generative AI Profile (NIST AI 600-1, Jul 2024)** is the governance anchor:
GOVERN / MAP / MEASURE / MANAGE, with a verified-from-source mapping of the GenAI risks that matter here —
**Information Security** (names prompt injection + data poisoning explicitly; action `MS-2.7-007` = red-team
for prompt injection), **Human-AI Configuration** (automation bias / over-reliance; `GV-3.2-003` = define
which queries the app must *refuse*), **Confabulation** (`MS-2.5-003` = verify sources/citations in output;
`MS-2.6-004` = review generated code/actions), **Data Privacy** (`MP-4.1-009` = detect PII in output), and
**Value Chain** (`MG-3.1-001` = apply controls to third-party GAI + reassess after fine-tuning/RAG). Crucially
for an "advisory, killable" assistant, **MANAGE-2.4** requires a mechanism to "supersede, disengage, or
deactivate" a misbehaving AI system (`MG-2.4-004` = pre-defined deactivation criteria) — our kill-switch
(§2.5, §7) is a named NIST control, not just good hygiene.
<https://nvlpubs.nist.gov/nistpubs/ai/NIST.AI.600-1.pdf>

### 3.5 Real-world precedents (MITRE ATLAS case studies — this has already happened to comparable systems)

These are not hypotheticals; they're catalogued compromises of systems materially like ours. They're the
single best argument for the architecture in §4–§6:

- **AML.CS0026 — Financial-transaction hijacking with M365 Copilot (Zenity, 2024).** An attacker emails
  content crafted to be **RAG-retrieved**; when a user asks Copilot for banking details, it returns the
  *attacker's* bank info **with manipulated citations to look authentic**. *This is our scenario with the
  serial numbers filed off* — a governed assistant reading attacker-influenced data and confidently
  emitting a wrong, authoritative-looking answer. <https://atlas.mitre.org/studies/AML.CS0026> (techniques:
  AML.T0051.001 Indirect Injection, AML.T0070 RAG Poisoning, AML.T0067 Trusted-Output/Citation manipulation.)
- **AML.CS0035 — Slack AI data exfiltration via indirect injection (PromptArmor, 2024).** A malicious prompt
  posted in a public channel is ingested into the RAG store; a victim's later query retrieves and executes
  it, exfiltrating private data / API keys. <https://atlas.mitre.org/studies/AML.CS0035>
- **AML.CS0024 — Morris II RAG worm (2024).** A zero-click self-replicating prompt arrives by email; the RAG
  assistant ingests it, replicates it into its own output, and propagates to connected systems.
  <https://atlas.mitre.org/studies/AML.CS0024>
- **AML.CS0037 / CS0038 — agentic tool abuse & delayed invocation.** Copilot Studio agents exfiltrating data
  via tool definitions; and a Gemini case where injection **defers** a sensitive tool call to the *next*
  turn to bypass a same-turn safety control — a direct warning that "block tools in the turn untrusted data
  arrives" is insufficient. <https://atlas.mitre.org/studies/AML.CS0037> · <https://atlas.mitre.org/studies/AML.CS0038>

**Takeaways baked into this design:** (1) RAG retrieval is an injection ingress, not just a feature
(§6); (2) confident wrong output with fake citations is the *expected* failure (§ misinformation) — so
**never auto-apply** and keep the human + reviewer; (3) any tool that can exfiltrate or act is the whole
ballgame — hence "no exfiltration leg" + HITL (§3.2, §5). ATLAS's own relevant mitigations: **AML.M0021**
Generative AI Guidelines (input side), **AML.M0020** Guardrails (output side), **AML.M0019/M0005** Access
Control, **AML.M0024** AI Telemetry Logging. <https://github.com/mitre-atlas/atlas-data>

---

## 4. Guardrail: prompt injection (the hard one — get the posture right)

### 4.1 Why you cannot filter your way out

Prompt injection is **architectural**, not a bug to be patched. The model receives system instructions,
developer instructions, user input, and retrieved content as **one undifferentiated token stream** with no
trustworthy delimiter the model is *guaranteed* to respect (Willison, who coined the term:
<https://simonwillison.net/2024/Mar/5/prompt-injection-jailbreaking/>; OpenAI's CISO, Oct 2025: "prompt
injection remains a frontier, unsolved security problem" <https://simonwillison.net/2025/Oct/22/openai-ciso-on-atlas/>).

The evidence against relying on detectors/filters is strong and recent:

- **"The Attacker Moves Second"** (multi-lab adaptive-attack study, 2025): adaptive attackers achieve
  **>90% attack success** against PromptGuard, Protect AI's detector, and Google Model Armor; >95% against
  spotlighting; static-dataset robustness numbers "collapse under adaptive attackers."
  <https://arxiv.org/abs/2510.09023>
- Vendor disclaimers: Meta's Prompt-Guard model card states it "is not immune to adaptive attacks."
  <https://huggingface.co/meta-llama/Prompt-Guard-86M>
- Concrete bypasses: LlamaFirewall blocked ~50/100 payloads; bypassed via Turkish-language phrasing,
  leetspeak, and invisible Unicode. <https://medium.com/trendyol-tech/bypassing-metas-llama-firewall-a-case-study-in-prompt-injection-vulnerabilities-fb552b93412b>
- Microsoft's own stance: spotlighting is a *probabilistic* layer; "it is still possible that some
  injections might evade these defenses," so they rely on **deterministic** controls (exfiltration blocking,
  consent) as the real backstop. <https://www.microsoft.com/en-us/msrc/blog/2025/07/how-microsoft-defends-against-indirect-prompt-injection-attacks>

**In application security, 99% is a failing grade** — a motivated attacker finds the 1%. So filters are at
best a *noise-reduction* layer, never the control of record.

### 4.2 What actually helps (in priority order, for this system)

1. **Deny the exfiltration leg (architectural).** Covered in §3.2 — no outbound tool. This is worth more than
   every filter combined, because it removes the *consequence* of a successful injection.
2. **Enforce all authority deterministically outside the model.** Tool execution, ownership, SoD: our code,
   not the model's say-so. An injected model that "decides" to grant access still hits `authz.require(...)`
   and SoD on the **real** principal and fails. This is the OWASP LLM01 mitigation #4/#5 (least privilege +
   HITL for privileged ops) and the SAIF "deterministic enforcement outside the reasoning loop."
3. **Separate trusted instructions from untrusted data, and minimize untrusted data in context.** Mark
   retrieved/user content as data, not instructions ("spotlighting" — datamarking/encoding measurably lowers
   *simple* injection success, <https://arxiv.org/abs/2403.14720>) — but treat it as **probabilistic
   hardening, not a guarantee**. Pair with context-minimization: only retrieve what's needed.
4. **Architectural isolation for the high-value future (dual-LLM / CaMeL).** If/when the assistant gains any
   consequential capability, the durable pattern is to keep untrusted content away from the tool-calling
   model:
   - **Dual-LLM** (Willison, <https://simonwillison.net/2023/Apr/25/dual-llm-pattern/>): a *privileged* LLM
     with tools never sees untrusted content; a *quarantined* LLM processes untrusted content but has no
     tools; a non-LLM controller passes only symbolic references between them.
   - **CaMeL** (Google DeepMind/ETH, <https://arxiv.org/abs/2503.18813>): the strongest result — a
     **design-level, provable** defense. A privileged LLM emits code from the *trusted* query only; a custom
     interpreter tracks data lineage with **capabilities** and enforces policies at tool-call time, so
     "untrusted data … can never impact the program flow." Reduced GPT-4o attacks **233 → 0** on AgentDojo
     at ~7pp utility cost. *Cost: policy-authoring burden + approval fatigue; not "solved," but the right
     north star if we ever let the assistant act.*

> **Recommendation:** For v1–v2 (advisory + read-only RAG), we don't need dual-LLM/CaMeL machinery because
> there's no exfiltration leg and no write tool — injection can't do much. The moment we contemplate a
> *write* tool from the chat loop, the bar moves to a CaMeL-style "policy enforcement outside the model"
> design, and that is its own project with its own review.

---

## 5. Guardrail: agency, tools, and human-in-the-loop

### 5.1 Least-privilege tool execution (the confused-deputy problem)

The classic failure is the **confused deputy**: a high-privilege assistant tricked by a low-privilege user
(or by injected content) into doing something the *user* couldn't. The fix is non-negotiable and we already
have the machinery:

- **Every tool executes server-side under the requesting user's identity**, and re-runs the same
  `authz.require(principal, …)` + ABAC `owns()` + SoD checks the normal endpoints do. `checkGroupOwnership`
  must answer "can **this user** request this group," computed by our ABAC — never "does the group exist"
  answered by a privileged identity. (OWASP LLM06; OWASP AI Agent Security Cheat Sheet: "scope limited to the
  invoking user, and human confirmation for sensitive actions"
  <https://cheatsheetseries.owasp.org/cheatsheets/AI_Agent_Security_Cheat_Sheet.html>; Google SAIF:
  least-privilege as the "upper bound on agentic system permissions.")
- **Allow-list tools per role.** Only roles with `assistant.use` (APP_OWNER, SSO_OPS, ADMIN, SUPER_ADMIN)
  reach the assistant at all; within that, a tool a role can't benefit from isn't offered.
- **No open-ended tools.** No "run this query," no "fetch this URL," no shell. The four contract tools are
  narrow and purpose-built — keep it that way (OWASP LLM06 "Avoid open-ended extensions").

### 5.2 Treat the model's tool calls as untrusted input

The model proposing a tool call is **not** authorization to run it. Before any execution:

- **Strict allow-list of tool names** (reject anything not in the four) and **schema-validate every
  argument** (types, lengths, enums). A `recommendScopes` output must be validated against the real scope
  catalog; a `validateRedirectUris` argument must be parsed as a URI and policy-checked — the model's opinion
  that a URI is fine is an *input to* validation, not a substitute for it.
- **Never `eval`/interpolate model output into code or queries.** Parameterized writes only (OWASP LLM05).
- **Idempotency** on any state-changing path (reuse the existing `Idempotency-Key` machinery) so a retried or
  duplicated proposal can't double-act.
- **Argument-injection defense:** the model must not be able to smuggle an out-of-policy scope/URI/group
  past validation by phrasing — validation is structural, not trust-based.

### 5.3 Human-in-the-loop — make it a real boundary, not a rubber stamp

The contract already has `POST /assistant/actions/{id}/approve` — the right primitive. But HITL is only
worth something if the human can **meaningfully** review, and if we design against **automation bias**:

- **When to require approval:** any consequential, irreversible, or privileged action (anything that would
  write governed state, create a registration, grant access, or call Graph). Reversible advisory output
  (drafting text) needs none. *Don't* gate trivial steps — LangGraph's guidance: "a graph that interrupts on
  every LLM call trains humans to rubber-stamp approvals, defeating the purpose."
  <https://docs.langchain.com/oss/python/langgraph/interrupts> Use **risk-tiered** approval (OpenAI rates
  tools low/med/high on "read-only vs write, reversibility, permissions, financial impact").
- **Present the *exact* action for review** — actor, tool, target, parameters — a "preview vs execute"
  separation, and bind the approval to that exact action (so the parameters can't change between propose and
  execute). (OWASP AI Agent Cheat Sheet: bind approval to "actor, tool name, target, parameters, timestamp,
  expiry.")
- **Separation of duties still holds, and the AI is *never* an approver.** Our SoD already resolves the
  **real** principal (the impersonation-laundering guard). The AI's proposal does not count as a person; the
  human who approves must be SoD-eligible (≠ the requester), exactly as today. The AI proposing + the same
  human approving their own request is still self-approval and still blocked.
- **Design against automation bias.** This is a documented, structural human-factors problem, not a training
  gap: complacency and automation bias "cannot be prevented by training or instructions" (Parasuraman &
  Manzey 2010, <https://journals.sagepub.com/doi/10.1177/0018720810376055>), and **accountability** measurably
  reduces it (Skitka et al.). Practical implications: show *why* (cite the policy/source behind a
  recommendation), make the human's decision a first-class audited act with their name on it, and never
  pre-check the "approve" button or default to accept.

### 5.4 Auditability & non-repudiation (reuse Phase 6a)

We already have an **immutable, hash-chained audit log** with `actor` = the real principal and
`/audit/verify`. The assistant plugs straight in:

- Emit distinct audit events for **AI-proposed** (actor = the user, with a flag/effectiveRole noting AI
  assistance), **human-approved** (actor = the approver), and **system-executed** (actor = `system`). The
  three are already distinguishable in our schema (actor + action + detail).
- This gives non-repudiation: "the AI suggested scope X, user U accepted it, reviewer R approved the request,
  the system provisioned it" is a verifiable chain. (NIST AI 600-1 MANAGE/observability; SAIF observability
  principle; Google's own Claude-Code-style tooling logs tool name, identity, decision to a SIEM.)
- **Regulatory tailwind:** the EU AI Act mandates automatic **logging/record-keeping + traceability** for
  high-risk AI (Arts. 12 & 19) and **human oversight** (Art. 14). Our hash-chained log + three-identity
  attribution already satisfies the "who decided what, when" record-keeping bar — a differentiator, not a
  retrofit. <https://artificialintelligenceact.eu/>
- **Kill-switch (NIST MANAGE-2.4).** Because the assistant is advisory and decoupled, we can "supersede,
  disengage, or deactivate" it instantly (flag off, like our scheduler gates) without touching the governed
  workflow — a named NIST control we get nearly for free.

> **Recommendation:** Add an explicit `aiAssisted: true` (and which tool) into the request's audit `detail`
> when a field was AI-drafted, so audit/forensics can answer "was this justification AI-written?" later. Cheap
> now, valuable under scrutiny.

---

## 6. Guardrail: sensitive-data exposure (RAG done safely)

If the assistant ever retrieves data to ground its answers (catalog, policy docs, the user's own requests),
the cardinal rule is **enforce the user's permissions at retrieval time — don't retrieve what the user can't
see** (OWASP LLM08; "fine-grained, permission-aware vector stores with strict logical partitioning").

- **Permission-aware retrieval.** The retrieval query carries the user's identity and filters on per-document
  ACL metadata **before** results reach the model. Post-retrieval filtering (or, worse, "tell the model not
  to share it") is insufficient — by then the data is in the context and one injection away from the output.
  We are unusually well-positioned: our **RBAC/ABAC already answers "can this principal see this row,"** so
  retrieval reuses the same predicate (e.g., the access/request list queries already scope by owner). The
  vector/RAG layer must apply the *same* `owns()`/scope filter, not a looser one.
- **Tenant/boundary isolation.** A shared vector index is a cross-user leak waiting to happen (LLM08). Either
  partition the index by access scope or attach and enforce ACL metadata on every chunk; never let a
  similarity search cross an authorization boundary.
- **Minimize + redact what reaches the model.** Don't put **secrets** in context, ever — no client secrets
  (we keep those in Secrets Manager / WIF), no raw credentials, no full oids if a display handle suffices.
  Redact/tokenize PII before it reaches the provider (OWASP LLM02). Prefer summaries/IDs over raw records.
- **Output-side leakage.** The model can regurgitate context or over-share. Scope every response to the
  **real principal** and validate outputs don't contain another user's data (defense in depth on top of
  retrieval-time ACLs).
- **Logging privacy.** Prompts/completions contain sensitive data; if we log them for debugging/audit, that
  log is a new exposure surface — apply the same access controls + retention limits as the data itself, and
  keep them out of broad app logs. (Our audit log records *that* the assistant was used + the resulting
  governed action, **not** the raw conversation.)
- **Third-party LLM API data handling.** If using a hosted model, require **no-train + zero/short retention**
  contractual terms and acceptable data residency; otherwise prefer a self-hosted model. Decide deliberately
  what data classes may leave our boundary at all (§8).

---

## 7. Architecture: an autonomy ladder (earn each rung)

The safe path is to **start minimal and escalate only when justified** (Anthropic: "the simplest solution
possible … add agency only when it demonstrably improves outcomes"; OpenAI: "single agent first"). Proposed
rungs, each its own design-note → review → build gate:

- **Rung 0 — Stub (v1, Phase 7).** Both endpoints return `501` behind the `assistant.use` gate. No model, no
  data, no risk. Lets the FE render a clean "coming soon" state and locks the contract. *(This is the only
  rung we build now.)*
- **Rung 1 — Advisory, no retrieval.** `draftDescription` + `validateRedirectUris` + `recommendScopes` using
  only the data the user already typed into the form + static policy. **No private-data retrieval, no
  exfiltration tool** → the lethal trifecta is broken at two legs. Output is suggestion-only; the user edits
  and submits through the normal flow. Add rate/cost limits, output validation, audit-on-use. *Lowest-risk
  real value.*
- **Rung 2 — Grounded (permission-aware RAG).** Adds retrieval over the catalog/policy and the user's **own**
  requests, with retrieval-time ACLs (§6). `checkGroupOwnership` becomes real (runs the user's ABAC). Now
  "untrusted content" enters → injection matters, but still no exfiltration/write leg, so blast radius stays
  "bad suggestion." Requires the LLM02/LLM08 controls and red-teaming.
- **Rung 3 — Proposing write-actions (HITL).** The assistant proposes a concrete action (e.g., "submit this
  application," "request access to X") routed through `POST /assistant/actions/{id}/approve` and then the
  **existing** governed engine (RBAC/ABAC/SoD/guarded transitions). This is where Excessive Agency (LLM06)
  becomes live; gate behind §5 in full, and seriously consider a **CaMeL/dual-LLM** isolation design so
  untrusted content never reaches the action-proposing model. **Highest bar; may be out of scope for v1
  entirely.**

> **Recommendation:** Commit publicly only to Rung 0 now, and **Rung 1** as the first "real" increment when
> prioritized. Treat Rungs 2–3 as separate initiatives with their own threat reviews. Do **not** let the
> assistant call Graph or mutate governed state from the chat loop on any rung — writes always go through the
> human-approved governed pipeline.

### 7.5 Red-team / evaluation before any non-stub rung
Adopt an adversarial eval harness (AgentDojo-style, <https://arxiv.org/abs/2406.13352>) and a red-team pass
mapped to the MITRE ATLAS techniques in §3.3 (indirect injection via a poisoned catalog `description`,
system-prompt extraction, data-leakage prompts, cost-harvesting). Measure **attack-success-rate**, not just
benign utility, and against *adaptive* attempts — static test sets overstate robustness.

---

## 8. Data-handling & provider decision (needs a human call)

Open questions that determine the provider/architecture and must be answered before Rung 1:

1. **Which model, hosted where?** Hosted API (fast, capable, but data leaves our boundary — requires
   no-train/zero-retention/residency terms) vs. self-hosted (full control, more ops). Given this is an
   enterprise governance tool touching app-registration metadata, justifications, and oids, the bar for "what
   may leave the tenant" is high.
2. **What data class may ever reach the model?** Recommend a strict allow-list: form fields the user typed,
   the *public* catalog, static policy text. **Never:** secrets, another user's data, raw audit rows.
3. **Retention of prompts/completions** for debugging vs. privacy (§6 logging).
4. **Cost ceiling / rate policy** per user and global (LLM10).
5. **Human ownership** of the assistant's risk decisions (NIST GOVERN — "assign clear ownership").

---

## 9. How this reuses what we already built (the cheat code)

| Existing primitive | How the assistant reuses it |
|---|---|
| **RBAC (app-roles, union, `assistant.use`)** | Gates who can use the assistant at all; allow-lists tools per role. |
| **ABAC (`Ownable.owns()`, scope)** | The exact predicate for permission-aware retrieval and `checkGroupOwnership`; the assistant sees only what the user sees. |
| **Separation of duties (real principal, laundering guard)** | The AI is never an approver; AI-proposed + self-approved is still blocked; SoD resolves the real human. |
| **Guarded request engine (state machine, If-Match)** | The only thing that mutates governed state. The assistant proposes inputs; the engine enforces legality. |
| **Immutable audit log (Phase 6a, `/audit/verify`)** | Attributes AI-proposed vs human-approved vs system-executed; non-repudiation; `aiAssisted` flag. |
| **Human-in-the-loop `approve` endpoint** | The HITL boundary for any future write-action (Rung 3). |
| **Idempotency-Key machinery** | Prevents duplicated proposed actions from double-acting. |
| **WIF least-privilege (OwnedBy, not ReadWrite.All)** | The assistant gets **no** new cloud privilege; it can never out-scope the workload identity, and ideally touches no Graph at all. |
| **BFF session (realUserId from oid)** | Identity propagation into every tool execution — the "act as the user" requirement is free. |

---

## 10. Open decisions / recommendations summary

1. **Build only Rung 0 (the 501 stub) now.** ✔ Recommended; it's the Phase 7 note.
2. **Adopt the "advisory, never authoritative" + "no exfiltration leg" invariants** as hard design rules for
   every future rung. ✔ Strongly recommended.
3. **Rung 1 (advisory, no retrieval) as the first real increment** when prioritized — lowest risk, real
   value. ✔ Recommended.
4. **Defer Rungs 2–3** to dedicated threat-reviewed initiatives; require permission-aware retrieval (Rung 2)
   and a CaMeL/dual-LLM isolation design + full HITL (Rung 3). ✔ Recommended.
5. **Provider/data-class/retention/cost/ownership decisions** (§8) — **need the human.** Not an engineering
   call.
6. **No filter-as-control.** Any "prompt-injection firewall" is at most noise reduction; the controls of
   record are architectural (deny the trifecta) + deterministic authz + HITL + audit. ✔ Recommended.
7. Add an **`aiAssisted` audit flag** when a field is AI-drafted. ✔ Cheap, recommended.

---

## References (grouped)

**OWASP**
- OWASP Top 10 for LLM Applications 2025 — <https://genai.owasp.org/llm-top-10/> · per-risk pages
  `genai.owasp.org/llmrisk/...` (LLM01 Prompt Injection, LLM02 Sensitive Info Disclosure, LLM05 Improper
  Output Handling, LLM06 Excessive Agency, LLM07 System Prompt Leakage, LLM08 Vector & Embedding Weaknesses,
  LLM09 Misinformation, LLM10 Unbounded Consumption); 2023→2025 mapping
  <https://genai.owasp.org/llm-top-10-2023-24/>
- OWASP Agentic AI: Threats and Mitigations — <https://genai.owasp.org/resource/agentic-ai-threats-and-mitigations/>
- OWASP AI Agent Security Cheat Sheet — <https://cheatsheetseries.owasp.org/cheatsheets/AI_Agent_Security_Cheat_Sheet.html>
- OWASP LLM Prompt Injection Prevention Cheat Sheet — <https://cheatsheetseries.owasp.org/cheatsheets/LLM_Prompt_Injection_Prevention_Cheat_Sheet.html>

**Standards / taxonomy**
- NIST AI RMF 1.0 (AI 100-1) — <https://nvlpubs.nist.gov/nistpubs/ai/nist.ai.100-1.pdf> · GenAI Profile
  (AI 600-1, the 12 GenAI risks + suggested actions) — <https://nvlpubs.nist.gov/nistpubs/ai/NIST.AI.600-1.pdf>
- NIST AI 100-2e2025, Adversarial ML Taxonomy — <https://csrc.nist.gov/pubs/ai/100/2/e2025/final>
- MITRE ATLAS — <https://atlas.mitre.org/> · data <https://github.com/mitre-atlas/atlas-data> · **case studies**
  used in §3.5: M365 Copilot <https://atlas.mitre.org/studies/AML.CS0026>, Slack AI
  <https://atlas.mitre.org/studies/AML.CS0035>, Morris II <https://atlas.mitre.org/studies/AML.CS0024>,
  Copilot Studio <https://atlas.mitre.org/studies/AML.CS0037>, delayed tool invocation <https://atlas.mitre.org/studies/AML.CS0038>
- EU AI Act (logging Art. 12, human oversight Art. 14, record-keeping Art. 19) — <https://artificialintelligenceact.eu/>

**Prompt injection (root cause, defenses, evidence)**
- Willison: coinage <https://simonwillison.net/2022/Sep/12/prompt-injection/> · injection≠jailbreak
  <https://simonwillison.net/2024/Mar/5/prompt-injection-jailbreaking/> · lethal trifecta
  <https://simonwillison.net/2025/Jun/16/the-lethal-trifecta/> · dual-LLM
  <https://simonwillison.net/2023/Apr/25/dual-llm-pattern/> · design patterns
  <https://simonwillison.net/2025/Jun/13/prompt-injection-design-patterns/>
- Greshake et al., Indirect Prompt Injection — <https://arxiv.org/abs/2302.12173>
- CaMeL (Google DeepMind/ETH) — <https://arxiv.org/abs/2503.18813>
- Spotlighting (Microsoft) — <https://arxiv.org/abs/2403.14720>
- "The Attacker Moves Second" (adaptive attacks beat filters) — <https://arxiv.org/abs/2510.09023>
- AgentDojo benchmark — <https://arxiv.org/abs/2406.13352>
- Microsoft MSRC, defending against indirect injection — <https://www.microsoft.com/en-us/msrc/blog/2025/07/how-microsoft-defends-against-indirect-prompt-injection-attacks>

**Agentic guardrails / HITL (vendor + academic)**
- Anthropic, Building Effective Agents — <https://www.anthropic.com/engineering/building-effective-agents>
- OpenAI, A Practical Guide to Building Agents — <https://cdn.openai.com/business-guides-and-resources/a-practical-guide-to-building-agents.pdf> · Guardrails & human review <https://developers.openai.com/api/docs/guides/agents/guardrails-approvals>
- Google SAIF, Focus on Agents — <https://saif.google/focus-on-agents>
- MCP Security Best Practices (confused deputy, token passthrough) — <https://modelcontextprotocol.io/docs/tutorials/security/security_best_practices>
- LangGraph interrupts (approval fatigue) — <https://docs.langchain.com/oss/python/langgraph/interrupts>
- Automation bias: Parasuraman & Manzey 2010 — <https://journals.sagepub.com/doi/10.1177/0018720810376055>

> _Sourcing note: the OWASP Top 10, the NIST GenAI Profile (AI 600-1), and the MITRE ATLAS technique/case-study
> data were verified against primary sources (OWASP per-risk pages, the NIST PDF read directly, and MITRE's
> machine-readable `atlas-data` repo). A few vendor/blog secondary claims (e.g., a specific automation-bias
> statistic, some risk-tiering thresholds) are flagged in-line as secondary and should be re-checked before
> being treated as load-bearing. ATLAS display names are mid-migration ("ML"→"AI"); IDs are stable._
