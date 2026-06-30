# Observability — OpenTelemetry → Grafana Cloud (design note / plan)

**Status:** Plan (not yet built). Proposed as a **Phase 8.5** increment, pairs with Phase 8 (HA).
**Scope:** vendor-neutral instrumentation (OpenTelemetry) exporting to **Grafana Cloud free tier**
(Tempo traces · Prometheus metrics · Loki logs). No contract change; Dockerfile + ECS task-def + a small
relay tweak. Chosen over a vendor SDK / time-boxed APM trial because the free tier never expires and
"I used OpenTelemetry" is portable across backends (point the same OTLP at Datadog/Dynatrace/CloudWatch
without re-instrumenting).

## Why this shape
- The app already has `spring-boot-starter-actuator` (health/info exposed today) and ships logs to
  CloudWatch (`/eop-dev/app`). The foundation is present; this adds traces + metrics export and (later) logs.
- The **OTel Java agent** auto-instruments Spring MVC, JDBC (Postgres), and the outbound HTTP client with
  **zero code** — so the cross-cloud **WIF → Entra → Microsoft Graph** calls get traced for free.
- One **Grafana Alloy** sidecar fans all signals to Grafana Cloud, so the backend is swappable later.

## Architecture
```
[ app container ]                         [ alloy sidecar ]            [ Grafana Cloud (free) ]
 Spring Boot 3 / Java 21                    Grafana Alloy
   + OTel Java agent  --OTLP localhost-->     OTLP receiver  --remote_write/OTLP-->  Tempo (traces)
   (auto spans + Micrometer->OTel metrics)    (+ optional scrape                     Prometheus (metrics)
                                               /actuator/prometheus)                 Loki (logs, phase 2)
 stdout logs --(phase 2: FireLens/Fluent Bit)--------------------------------------> Loki
```
- Token (one Access Policy token, scopes `metrics:write,logs:write,traces:write`) lives in **Secrets
  Manager**; endpoints/instance-IDs are plain env.

## Step 1 — Grafana Cloud account (one-time human step)
1. Sign up at grafana.com → free Cloud stack (no credit card). Note the three endpoints + IDs: Tempo
   (OTLP), Prometheus (`remote_write` URL + user), Loki (push URL + user).
2. Create one **Access Policy token** (`metrics:write, logs:write, traces:write`).
3. Free-tier ceilings (ample for a demo): ~10k active metric series · 50 GB logs · 50 GB traces ·
   ~14-day retention. Keep metric cardinality reasonable.
*(This is the human-in-the-loop part, same shape as SES identity verification — the rest is agent work.)*

## Step 2 — App instrumentation (Dockerfile + env, mostly no code)
- **Dockerfile (runtime stage):** download `opentelemetry-javaagent.jar`, start the JVM with
  `-javaagent:/otel/opentelemetry-javaagent.jar`.
- **Env (no code):** `OTEL_SERVICE_NAME=eop-app`, `OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318`,
  `OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf`, `OTEL_RESOURCE_ATTRIBUTES=deployment.environment=dev,service.version=<git-sha>`,
  plus a sampling ratio (e.g. `OTEL_TRACES_SAMPLER=parentbased_traceidratio`, `OTEL_TRACES_SAMPLER_ARG=1.0`
  in dev).
- **Metrics:** the agent bridges Micrometer→OTel, so JVM / HTTP / **Hikari pool** metrics flow with no extra
  dependency (ties into the Phase 8 HA story — per-task pool usage). Optional alternative: add
  `micrometer-registry-prometheus` and expose `/actuator/prometheus` (keep internal; the sidecar scrapes
  localhost) if a pull model is preferred.

## Step 3 — Collector sidecar (Terraform task-def)
- Add one **Grafana Alloy** sidecar container to the ECS task definition: OTLP receiver on
  `localhost:4317/4318`; exporters → Tempo (traces) + Prometheus `remote_write` (metrics). Small config file.
- **Token** from **Secrets Manager** via the task-def `secrets` block (execution role gets read access).
- **Deploy path:** Dockerfile change → `app-deploy` rebuilds the image; task-def change → gated `infra`
  apply (dev approval). Same flow as every other backend change.

## Step 4 — Logs (second pass)
App logs already reach CloudWatch. To land them in **Loki**, add a **FireLens (Fluent Bit)** sidecar as the
log router → Loki. More task-def plumbing, so **ship traces + metrics first** (the demo wow), add Loki after
— correlated by `trace_id` for log↔trace jumps.

## The hard/impressive bit — async trace continuity across the outbox
Provisioning is **asynchronous**: request approved → event written to `messaging.outbox` → a **poller on
another thread/task** does the WIF→Graph work; audit + notify relays are separate consumers again. The OTel
agent traces each hop, but **trace context does not auto-propagate across the outbox boundary**, so by
default you get a "request" trace and *separate* "provisioning / audit / notify" traces.

To get the full **approve → provision → WIF → Graph → audit/notify** waterfall as ONE trace:
- On emit, capture the current `traceparent` and store it in the outbox event payload (it's opaque jsonb).
- In the relay/worker, restore that context and start the provisioning/projection span as a **child or
  span-link** of the originating request span.
- ~20 lines, and it's exactly the distributed-tracing detail that demonstrates real depth.
*(Note: the audit `detail` is coerced to strings for hash stability — a `traceparent` string is fine, but
keep it OUT of the audit pre-image if it would change the hash; carry it as an outbox/transport field, not
an audited field.)*

## Recommended cut
1. **Phase 8.5a:** OTel Java agent → Alloy sidecar → Grafana Cloud **Tempo + Prometheus**; async
   trace-context-through-the-outbox so the cross-cloud waterfall is one trace. JVM/Hikari dashboards
   (Grafana has prebuilt Micrometer/JVM dashboards).
2. **Phase 8.5b:** Loki via FireLens, `trace_id`-correlated.

## Verification
- Drive a real onboarding approval → in Tempo, see the single waterfall: HTTP `approve` → outbox → worker →
  **WIF mint → Entra token → Graph** → audit relay + notify relay.
- Prometheus: JVM, HTTP server, and Hikari pool metrics present per task (with ≥2 tasks from Phase 8, both
  task instances visible).
- (8.5b) Loki: app logs queryable and jumpable from a trace by `trace_id`.

## Backend options (same OTLP, swappable)
- **Grafana Cloud free** (recommended here) — no expiry, vendor-neutral.
- **Datadog 14-day / Dynatrace 15-day** trial — point the same OTLP at them for a flashier fixed-window demo.
- **CloudWatch Container Insights + X-Ray (ADOT)** — native, logs already flow; cheapest, least flashy.

## Open decisions (when this is picked up)
- Metrics via the OTel-agent Micrometer bridge (no extra dep) **vs.** `micrometer-registry-prometheus` +
  `/actuator/prometheus` scrape. (Lean: agent bridge — fewer moving parts.)
- Sidecar = **Grafana Alloy** (all-in-one) **vs.** ADOT/OTel Collector. (Lean: Alloy — one agent for all
  three signals.)
- Sampling in dev (100%) vs. a ratio; revisit for prod/cost.
- Logs now (FireLens→Loki) vs. defer and stay on CloudWatch for the first cut. (Lean: defer.)
