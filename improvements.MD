# Improvements — WillAIStealMyJob

**Reviewed:** 2026-07-07
**Scope:** Full application — controllers, services, models, configuration, templates, persistence, tests, docs, and build setup.
**Stack:** Spring Boot 3.5 / Java 21, Spring AI (OpenAI), Stripe, Thymeleaf, Flying Saucer (PDF), H2/JPA, Bucket4j.

This complements the earlier `prod-ready.MD` review (2026-05-15). Items already fixed since then (input caps, webhook existence check, metadata truncation logging) are not repeated. Items are grouped by area and tagged **[High]**, **[Medium]**, or **[Low]** priority.

---

## 1 · Business-Logic Integrity & Cost Exposure

### 1.1 [High] ✅ FIXED (2026-07-07) — The paid report's risk score is client-controlled
> Fixed: `ReportService.generateAndStoreReport()` now recomputes score/riskLevel via `RiskScoringService.score(...)` from the request's mode/profession/description and overwrites the client-supplied values before the AI call and persistence. A mismatch between client and server values is logged as a warning.

`GenerateReportRequest` carries `score`, `riskLevel`, `description`, and `mode` straight from the browser (`generating-report.html` replays them from `sessionStorage`). The server never recomputes or cross-checks them — `PremiumReportAiService.mapToReport()` copies `req.getScore()` and `req.getRiskLevel()` directly into the paid product, and `ReportService` persists them.

- Anyone can buy a report showing any score they choose, for any profession, with a fabricated description. That undermines the credibility of the product you're charging for.
- **Fix:** re-run `RiskScoringService.score(...)` server-side inside `ReportService.generateAndStoreReport()` (the inputs it needs — journey type, subject, details — are all in the request), or better, persist the free-assessment result server-side under a token and have `/generate-report` accept only that token.
- Files: `model/GenerateReportRequest.java`, `service/PremiumReportAiService.java:126-132`, `templates/generating-report.html:484-491`.

### 1.2 [High] Full premium report is generated before payment — cost gated only by a spoofable header
`POST /generate-report` runs the expensive premium AI call immediately and payment merely *unlocks* the stored result. The only guard is the Bucket4j limit keyed on the `X-Visitor-Id` **request header** (`ReportController.java:42-43`), which the client invents (`localStorage` UUID). A hostile script can rotate the header per request and generate unlimited premium AI calls, none of which will ever be paid for.

- **Fix (minimum):** rate-limit by IP address (or IP + visitor ID) instead of a client-supplied header; add a global concurrent/daily budget on premium generations.
- *Partially mitigated 2026-07-07:* the bounded `reportTaskExecutor` (§4.1) now caps concurrent premium generations globally (max 4 running + 20 queued → 503 beyond that), and the request-hash dedupe (§1.5) blocks identical replays. Rotating visitor IDs with varied payloads can still queue up to the cap without paying — the per-IP limit and/or pay-first flow remain open.
- **Fix (better):** restructure the flow to collect payment first (Stripe Checkout → webhook → generate report asynchronously → notify/poll). This eliminates the "pay for compute, hope they buy" exposure entirely and is the standard shape for paid-AI products.
- Files: `controller/ReportController.java`, `service/ReportRateLimiterService.java`.

### 1.3 [Medium] ✅ FIXED (2026-07-07) — Webhook marks report paid without checking the session's payment status
> Fixed: `WebhookController` now unlocks only when `payment_status` is `paid` (or absent, matching the redirect path); `checkout.session.async_payment_succeeded`/`async_payment_failed` are handled so delayed payment methods settle correctly. Instead of an amount check, `ReportService.markPaidFromWebhook()` rejects any session that doesn't match the one attached at checkout — a session created outside the flow can't unlock a report at any price. **Note:** subscribe the Stripe webhook endpoint to the two `async_payment_*` events. Tests added in `WebhookControllerTest`.

`WebhookController` trusts `checkout.session.completed` alone. For asynchronous payment methods (e.g. bank debits), a session can complete with `payment_status = "unpaid"`; Stripe's own docs say to check it. `ReportService.syncPaymentStatusIfNeeded()` already does this correctly on the redirect path — the webhook path should match:

```java
if (session.getPaymentStatus() == null || "paid".equals(session.getPaymentStatus())) { ... }
```

Also consider verifying `amount_total` against the expected price for the report's mode, so a session created outside your flow can't unlock a report cheaply.
- File: `controller/WebhookController.java:45-68`.

### 1.4 [Medium] ✅ FIXED (2026-07-07) — Webhook silently no-ops when Stripe's deserializer fails
> Fixed: both webhook handlers now share `deserializeSession()`, which falls back to `deserializeUnsafe()` on API-version mismatch (logged as WARN) and, if the session still can't be obtained, logs `PAYMENT EVENT DROPPED` at ERROR and returns 500 so Stripe retries and flags the endpoint instead of the payment being silently lost. Covered in `WebhookControllerTest`.

`event.getDataObjectDeserializer().getObject().orElse(null)` returns empty whenever the account's Stripe API version differs from the SDK's pinned version. When that happens the payment is *received but never applied*, with no log line (the `instanceof Session` check just falls through). Use `deserializeUnsafe()` as a fallback or at minimum log loudly when the object can't be deserialized, since this is a money-losing failure mode.
- File: `controller/WebhookController.java:46-48, 83-84`.

### 1.5 [Medium] ✅ FIXED (2026-07-07) — Refreshing the generating page silently burns a second AI call
> Fixed server-side (more robust than the sessionStorage option — covers multiple tabs, back/forward, and replayed requests): `ReportService.generateAndStoreReport()` hashes (visitorId, journey, profession, description) into a new `request_hash` column and returns the existing non-failed, non-expired report when an identical request arrives within `app.report.dedupe-window-minutes` (default 30). Reused deliveries skip the `generation_completed` analytics event so AI-cost metrics aren't double-counted. No template change needed.

`generating-report.html` fires `/generate-report` on every page load from `sessionStorage`. A refresh (or back/forward navigation) creates a brand-new report + reportId and a second premium AI call for the same user. Add an idempotency key (store `reportId` in `sessionStorage` after the first success and reuse it) or dedupe server-side on a request hash within a time window.
- File: `templates/generating-report.html`.

---

## 2 · Correctness Bugs

### 2.1 [High] ✅ FIXED (2026-07-07) — Error state never shown if generation fails while the animation is still running
> Fixed: `maybeRedirect()` replaced with `maybeFinish()`, the single resolution point for both the animation-done and fetch-done paths — once both complete it redirects when a `reportId` exists and shows the error state otherwise. All four completion orderings (fetch/animation × success/failure) verified; template test added.

In `generating-report.html`, when the fetch fails before the animation finishes, the catch block sets `apiDone = true` and waits. But the animation-complete handler only calls `maybeRedirect()`, which does nothing because `reportId` is null — `showError()` is never called. The user is stuck on a spinner forever. The animation-done path needs `if (apiDone && !reportId) showError();`.
- File: `templates/generating-report.html:449-456, 500-511`.

### 2.2 [Medium] ✅ FIXED (2026-07-07) — Duplicated log placeholder in analytics
> Fixed: removed the duplicate `ts={}`. Added `AnalyticsServiceTest`, which captures the ANALYTICS logger output and asserts no event line contains an unfilled `{}` — guards all five event types against future placeholder/argument mismatches.

`AnalyticsService.recordSummaryGenerated()` has `"... ts={} ts={}"` with only one `Instant` argument, so every `summary_generated` line ends with a literal `{}`. If anything downstream parses these logs (which is the entire analytics strategy today), this event is malformed.
- File: `service/AnalyticsService.java:23`.

### 2.3 [Medium] ✅ FIXED (2026-07-07) — JSON error bodies returned for HTML page failures
> Fixed: `GlobalExceptionHandler` is now a content-negotiating `@ControllerAdvice` — requests whose `Accept` header includes `text/html` (browsers) get a rendered `templates/error.html` with status 500; fetch/API callers keep the exact `{"error": ...}` JSON shape. The new `error.html` also serves as Spring Boot's default error view, so browser-facing 404s get a friendly page instead of the whitelabel. Covered by `GlobalExceptionHandlerTest`.

`GlobalExceptionHandler` is a `@RestControllerAdvice` with a catch-all `Exception` handler. When a Thymeleaf page request (e.g. `/report/{id}`) throws, the browser receives raw JSON `{"error": "..."}` instead of an error page. Split handling: return JSON for `/api/**` + REST endpoints, render a friendly error view for MVC routes (or add an `error.html` and let Spring Boot's default error handling do it).
- File: `controller/GlobalExceptionHandler.java`.

### 2.4 [Medium] ✅ FIXED (2026-07-07, via §4.1) — Transaction (and DB connection) held open across the AI call
> Fixed as part of the async restructure: the AI call now runs in `ReportService.runGeneration()` on the report executor with no enclosing transaction; the row insert at submit and the result write in `completeGeneration()` are each short, independent transactions.

`ReportService.generateAndStoreReport()` is `@Transactional` and calls `premiumReportAiService.generate()` *inside* the transaction. With a 60-second read timeout and up to 5 retries with exponential backoff, one report can pin a pooled connection for minutes. A handful of concurrent generations will exhaust the Hikari pool (default 10) and take down unrelated DB traffic. Generate the report first, then open a short transaction just for the save.
- File: `service/ReportService.java:37-64`.

### 2.5 [Low] Direct visit to `/result` renders a broken page
The result page depends entirely on flash attributes; refreshing or bookmarking `/result` gives an empty shell. Redirect to `/` when `success`/`score` attributes are absent.
- File: `controller/RiskAssessorController.java:105-108`.

### 2.6 [Low] `Retry-After` can be `0`
Integer division of `getNanosToWaitForRefill() / 1_000_000_000` floors sub-second waits to `0`, telling clients they can retry immediately. Round up.
- File: `controller/ReportController.java:45`.

---

## 3 · Security

### 3.1 [High] Unauthenticated, unvalidated analytics endpoint
`POST /analytics/event` accepts any `visitorId`, `eventType`, and `profession` string and writes them into the ANALYTICS log verbatim. Consequences:

- **Log forging / injection:** a `profession` containing `" riskScore=10 event=payment_completed` corrupts the very logs used as the analytics source of truth. Escape or strip quotes/newlines/`=` from all user-supplied values before logging (this applies to `AnalyticsService.safe()` generally — it trims but does not sanitize).
- **Log-volume DoS:** no rate limit, no `eventType` allowlist, no length caps. A loop can fill your disk.
- **Fix:** validate `eventType` against a known enum, cap string lengths, sanitize values, and rate-limit the endpoint.
- Files: `controller/AnalyticsController.java`, `service/AnalyticsService.java`.

### 3.2 [Medium] CSP still allows `'unsafe-inline'` scripts
Every template carries large inline `<script>` blocks, so the CSP whitelists `'unsafe-inline'` for `script-src` — which neutralizes most of CSP's XSS protection. Move the JS into static files under `/static/js/` (this also fixes the duplication in §6.1) or adopt nonces via a request filter. Same applies to `style-src`.
- File: `configuration/SecurityConfig.java:33-35`.

### 3.3 [Medium] Live-looking Stripe test keys sit in a local file that has been leaked into review docs
`application-dev.properties` is gitignored, but its full key values are reproduced in this repo's own committed history/docs and were pasted into review output. Even test-mode keys allow creating sessions against your account. Rotate the test keys, and check `git log --all -- src/main/resources/application-dev.properties` to confirm the file itself was never committed before the ignore rule was added.

### 3.4 [Medium] ✅ FIXED (2026-07-07) — No cap on Tika parsing work
> Fixed: Tika output capped via `setMaxStringLength(100_000)` (zip-bomb decompression can no longer exhaust memory), and parsing now runs on a dedicated 2-thread bounded pool with a wall-clock timeout (`app.cv.parse-timeout-seconds`, default 10 s). On timeout the upload stream is closed, unblocking a parser stuck mid-read; pool saturation returns a friendly "try again" error instead of queuing unbounded work. `DocumentParserServiceTest` added (truncation, hang-timeout, validation paths).

Uploads are capped at 2 MB, but a 2 MB crafted DOCX can decompress to hundreds of MB (zip bomb) or hang Tika. Parsing runs on the request thread with no time or output cap. Wrap `tika.parseToString` with `Tika#setMaxStringLength` (e.g. 100 KB — you only need ~800 words) and consider a parse timeout.
- File: `service/DocumentParserService.java:62-73`.

### 3.5 [Low] No bean validation on `GenerateReportRequest`
Inputs are capped inside `buildPremiumPrompt()` (good), but the raw values still flow elsewhere: `fullReport.getProfession()` is persisted to a `nullable=false` column unnormalized — a JSON body with `"profession": null` produces a 500 from a DB constraint instead of a 400. Add `@NotBlank`/`@Size` and `@Valid` on the controller parameter (test the flow that previously broke — the report says `@Valid` caused issues, which suggests a fixable mapping problem rather than a reason to avoid validation forever).
- Files: `model/GenerateReportRequest.java`, `controller/ReportController.java:41`, `service/ReportService.java:44`.

---

## 4 · Architecture & Resilience

### 4.1 [High] ✅ FIXED for the premium flow (2026-07-07) — Long synchronous AI calls on servlet request threads
> Fixed: `POST /generate-report` now creates a GENERATING row, enqueues the AI call on a bounded `reportTaskExecutor` (core 2 / max 4 / queue 20, configurable via `app.report.generation.*`), and returns `202` + reportId immediately; saturation returns `503` + `Retry-After`. New `GET /report/{id}/status` serves the previously unused `ReportStatusResponse` (legacy rows without a status column derive COMPLETED/FAILED from `reportJson`). `generating-report.html` polls every 2.5 s (~6 min cap) and resolves through `maybeFinish()`. Dedupe (§1.5) now also reuses in-flight GENERATING rows, so a refresh re-attaches to the running generation. **Still open:** `/assess` remains synchronous on the request thread.

Both `/assess` and `/generate-report` block a Tomcat thread for the entire AI round-trip — up to 60 s read timeout × 5 retries. Tomcat's default 200 threads sounds like a lot, but a burst of slow OpenAI responses can starve the pool and make the whole site (including cheap pages) unresponsive. The premium flow already has an async-shaped frontend (progress page + redirect); complete the pattern:

- `POST /generate-report` → enqueue job, return `202` + `reportId` immediately.
- Frontend polls `GET /report/{id}/status` (a `ReportStatusResponse` model already exists but appears unused).
- Files: `controller/ReportController.java`, `controller/RiskAssessorController.java`, `model/ReportStatusResponse.java`.

### 4.2 [Medium] ✅ FIXED (2026-07-07) — Resilience protections are inconsistent between the two AI paths
> Fixed by extracting the hand-rolled logic (chosen over Resilience4j to avoid a new dependency while §8.2's metrics infra doesn't exist yet): new `AiCallGuard` encapsulates the circuit breaker + bulkhead + half-open probe; `JobAiService` uses it with its existing `app.ai.circuit-breaker.*` properties (behavior unchanged), and `PremiumReportAiService` now has its own guard via `app.ai.report.circuit-breaker.*` (threshold 3, open 60 s, max 4 concurrent — matching the report executor). Separate instances so one path's failures never block the other. `AiCallGuardTest` covers open/fail-fast, reset-on-success, half-open recovery, and bulkhead rejection.

The cheap summary call (`JobAiService`) has a hand-rolled circuit breaker + bulkhead; the *expensive* premium call (`PremiumReportAiService`) has neither — the path where runaway retries cost the most money is the unprotected one. Extract the circuit-breaker logic (or replace it with Resilience4j, which is battle-tested and gives you metrics for free) and apply it to both clients.
- Files: `service/JobAiService.java:145-207`, `service/PremiumReportAiService.java:62-94`.

### 4.3 [Medium] Expired-report purge runs inline on user requests
`purgeExpiredUnpaidReports()` executes a `DELETE` scan at the start of every report generation and every report view (`ReportController.java:55,73`). That adds latency to hot paths and does nothing when traffic is idle. Replace with `@Scheduled(fixedDelay = ...)` + `@EnableScheduling`, and add an index on `(payment_status, expires_at)` for the delete predicate.
- Files: `controller/ReportController.java`, `service/ReportService.java:161-167`, `model/ReportRequest.java`.

### 4.4 [Medium] Keyword-based risk scoring is brittle and hard-coded
`RiskDimensionCalculator` embeds per-profession special cases ("choir singer", "electrician", "nurse", "data entry"…) with magic clamps, and `RiskAdjustmentService` layers keyword bonuses on top. Any profession outside the hard-coded list gets near-baseline scores regardless of reality, and the docs (`docs/scoring-investigation.md`) show this has already needed firefighting. Improvements, in increasing ambition:

1. Move keyword tables and clamps into a config file (YAML) so tuning doesn't require redeploys.
2. Add a golden-dataset regression test (a table of profession → expected score band) — `RiskScoringBenchmarkTest` is a start; make it the contract.
3. Longer term: let the AI produce the dimension estimates (it already sees the full description) and use the deterministic layer only for clamping/sanity — the current design discards the model's judgment and substitutes ~40 keywords.
- Files: `service/RiskDimensionCalculator.java`, `service/RiskAdjustmentService.java`.

### 4.5 [Medium] ✅ FIXED (2026-07-07) — Significant code duplication between the two AI services
> Fixed: `extractJson`/`cleanJsonResponse` (unified on the array-aware variant), `extractContent`, `clampScore`, `elapsedMillis`, and `loadResource` extracted to `AiResponseUtils`; the AI_COST log line moved to `GenerationMetricsService.logAiCost()` (single format — the summary path now logs `reportId=-`, note for anything grepping AI_COST lines). Both services now take the Spring-managed `ObjectMapper` in their `@Autowired` constructors (test convenience ctors default one). `AiResponseUtilsTest` pins the unified cleaner behavior.

`extractJson`/`cleanJsonResponse`, `extractContent`, `clampScore`, `elapsedMillis`, `loadResource`, and the AI_COST log line are duplicated (with slight drift — the two JSON cleaners already behave differently on arrays). Extract an `AiResponseUtils`/`PromptLoader` component. Also inject the Spring-managed `ObjectMapper` instead of `new ObjectMapper()` in both services so JSON config stays consistent app-wide.
- Files: `service/JobAiService.java`, `service/PremiumReportAiService.java`.

### 4.6 [Low] Dead code and unused beans
- `gpt54ChatClient` bean (`AIModelConfiguration.java:25`) — never injected anywhere.
- `exception/ParsingException.java` — unused.
- `CheckoutController.truncate()` — unused (superseded by `truncateMeta`).
- `AnalyticsService.summaryUsage` map + `FREE_SUMMARY_LIMIT` + commented-out method — dead.
- `PremiumReport` "retained fields" block (`overallExposure`, `resilienceScorecard`, `careerLevelAnalysis`, `trackComparison`, …) — mapped from the AI response but never rendered; they inflate every stored JSON blob and the prompt/response token bill. Either render them or stop requesting/mapping/storing them.

### 4.7 [Low] Bean and class names encode the model generation
`gpt54MiniChatClient`, `gpt54ReportChatClient` will be lies the day you bump `AI_MODEL_MINI`. Name by role: `summaryChatClient`, `reportChatClient`.
- File: `configuration/AIModelConfiguration.java`.

---

## 5 · Data & Persistence

### 5.1 [High] H2 file database with `ddl-auto=update` and no migrations
For a system that stores *purchases*, this combination is fragile: no schema history, no rollback, single-file storage that doesn't survive container replacement, and `update` can silently diverge across environments. Adopt Flyway (one baseline migration reflecting today's schema), switch `ddl-auto` to `validate`, and target PostgreSQL in production (`DATABASE_URL` already supports it). Keep H2 for tests only.
- Files: `application.properties:14-20`, `pom.xml`.

### 5.2 [Medium] Missing indexes
`findByStripeSessionId` (webhook expiry path) and the purge delete both run on unindexed columns. Trivial at today's scale, but they're on payment paths — add `@Index` on `stripe_session_id` and `(payment_status, expires_at)`.
- File: `model/ReportRequest.java`.

### 5.3 [Low] Timezone type inconsistency
The entity uses `OffsetDateTime` (UTC) but `PremiumReport.generatedAt` is a `LocalDateTime` with server-default zone. Standardize on `OffsetDateTime`/`Instant` end-to-end, and render the user-facing expiry with an explicit zone (carried over from prod-ready item 4 — still open).
- Files: `model/PremiumReport.java`, `templates/premium-report.html`.

### 5.4 [Low] Local dev artifacts not ignored
`data/` (H2 files) and `prod-ready.MD` sit untracked at repo root; `.DS_Store` files exist under `src/`. Add `data/` to `.gitignore`; decide whether the review docs belong in the repo.

---

## 6 · Frontend & Templates

### 6.1 [Medium] ~5,600 lines of templates with heavy copy-paste
The visitor-ID bootstrap script is duplicated in 4 templates; CSRF helper functions, analytics `fetch` wrappers, and large CSS blocks are repeated per page. Any fix (e.g. the §2.1 bug) must be applied in several places. Extract:
- shared JS → `/static/js/visitor.js`, `/static/js/analytics.js` (also unblocks removing `'unsafe-inline'`, §3.2);
- shared markup (head, nav, footer, CSRF meta) → Thymeleaf fragments;
- shared CSS → the existing `static/css/style.css`.

### 6.2 [Medium] `sample-report.html` (1,294 lines) appears to be a fully hard-coded page
It duplicates the premium-report layout with static content. When the real template evolves, the sample will silently drift from what customers actually receive. Consider rendering the sample through the same `premium-report` template with a canned `PremiumReport` fixture object.

### 6.3 [Low] Free-assessment result lives only in flash attributes + sessionStorage
The handoff chain (flash attrs → inline JS → `sessionStorage` → replay to server) is the root cause of §1.1 and §2.5, and loses the user's result on refresh. Persisting the free assessment server-side with a short TTL and passing only an ID through the browser would simplify all three pages.

---

## 7 · Testing & CI

### 7.1 [High] No CI pipeline
There is no `.github/workflows` (or other CI config). The good test suite (~24 test classes, including the `NoRealAiSafetyTest` guard) only helps if it runs on every push. Add a GitHub Actions workflow: `./mvnw verify` on PRs + main.

### 7.2 [Medium] Payment-critical code has no tests
Untested classes, notably including the money paths:
- `WebhookController` — signature failure, completed/expired events, missing metadata, deserialization failure (would have caught §1.3/§1.4).
- `ReportService` payment transitions — `markPaidFromWebhook`, `syncPaymentStatusIfNeeded`, `markFailedBySessionId`, purge behavior.
- `ReportRateLimiterService`, `AnalyticsController`/`AnalyticsService`, `DocumentParserService`, `RiskAdjustmentService`, `RiskSanityValidator`, `PdfService` (only exercised indirectly via the download test).

### 7.3 [Low] No end-to-end flow test
A single `@SpringBootTest` walking assess → generate → locked view → simulated webhook → unlocked view → PDF download would protect the core funnel against regressions better than any unit test.

---

## 8 · Observability & Operations

### 8.1 [Medium] ✅ FIXED (2026-07-08) — Analytics strategy is grep-on-logs
> Fixed with the "better" option: every event is now persisted to a new `analytics_events` table (indexed on `eventType, occurredAt`) via `AnalyticsEventRepository`; the ANALYTICS log lines remain as a tail-time aid. "Reports sold this week" is now `SELECT count(*) FROM analytics_events WHERE event_type='payment_completed' AND occurred_at > ...`. Frontend-supplied event types are sanitized to `[a-zA-Z0-9_.-]` and capped at 40 chars before persisting; all string columns are length-capped; persistence failures are swallowed (logged) so analytics never breaks a user flow. Also removed the dead `summaryUsage`/`FREE_SUMMARY_LIMIT` code (§4.6).

All funnel/revenue events are unstructured log lines (with injectable content, §3.1, and a malformed event, §2.2). At minimum switch the ANALYTICS logger to structured JSON (logstash-logback-encoder); better, write events to an `analytics_events` table or a real product-analytics tool. Right now answering "how many reports sold this week" requires shell archaeology.

### 8.2 [Medium] ✅ FIXED (2026-07-08) — No metrics or alerting
> Fixed: Micrometer instrumentation added via the global registry — `jobai.ai.generation` timer (reportType/model tags) + `jobai.ai.tokens` + `jobai.ai.cost.usd` counters (fed from `logAiCost`, so metrics and AI_COST lines can never disagree), `jobai.ai.circuit.opened`/`.rejected` (client/reason tags), `jobai.report.generation` (outcome tag), `jobai.payment.completed` + `jobai.payment.amount.cents`, and `jobai.webhook.failure` (reason: invalid_signature/deserialization/processing_error/session_mismatch). `micrometer-registry-prometheus` added; `/actuator/metrics` + `/actuator/prometheus` exposed but require basic auth as the ACTUATOR role, configured via `ACTUATOR_USER`/`ACTUATOR_PASSWORD` env vars — endpoints stay locked when unset; `/actuator/health` remains public. Alerting itself still needs a scraper (Prometheus/Grafana or hosted) pointed at the endpoint.

Only `/actuator/health` is exposed. Micrometer is already on the classpath via Actuator — expose `metrics`/`prometheus` (secured) and add counters/timers for: AI call latency + token cost, circuit-breaker state, report generations, payments completed, webhook failures. The AI_COST log lines you're hand-rolling are exactly what a `Timer`/`Counter` with tags would give you queryable.

### 8.3 [Low] No request correlation
Multi-step flows (generate → checkout → webhook) are stitched together in logs only by reportId at best. Add an MDC filter putting `visitorId`/`requestId` into the logging context.

### 8.4 [Low] No deployment artifacts
No Dockerfile, no compose file, no documented deploy procedure. The pre-deployment checklist in `prod-ready.MD` lists ~10 required env vars; codify them (a `.env.example`, a Dockerfile, and a startup fail-fast check for missing critical config).

---

## 9 · Documentation & Housekeeping

- **[Medium] ✅ FIXED (2026-07-08) No README.** ~~A new contributor (or you in six months) has no build/run/env-var instructions. `technical-document.md` is empty — either write it or delete it.~~ `README.md` written (quickstart, env-var tables, flow, observability, layout); empty `technical-document.md` deleted; `data/` added to `.gitignore` (part of §5.4).
- **[Low] Stale files:** `HELP.md` (Spring Initializr boilerplate), `old_journey.MD`, empty `technical-document.md`, and `.idea/` files tracked in git (`.gitignore` has `.idea/` but several `.idea/*.xml` files exist — verify they're untracked).
- **[Low] `pom.xml` metadata** — empty `<license/>`, `<developer/>`, `<scm/>` stubs; artifact is named `WillAIStealMyFuture` while the repo is `WillAIStealMyJob`. Align naming.
- **[Low] Prompt-file versioning** — `prompts/` contains V1/V2/V3 variants with the active one only discoverable by reading `JourneyConfigRegistry`. Delete superseded versions (git keeps history) or document which is live.

---

## Suggested Order of Attack

| # | Item | Why first |
|---|------|-----------|
| 1 | §1.2 IP-based rate limiting (+ global budget) | Open cost exposure, small fix |
| 2 | §1.1 Server-side score recomputation | Product integrity of the paid item |
| 3 | §2.4 Move AI call out of the transaction | One slow burst can take the site down |
| 4 | §2.1 Stuck-spinner bug + §2.2 log typo | Paying-funnel UX, trivial fixes |
| 5 | §1.3/§1.4 Webhook payment-status + deserialization handling | Money correctness |
| 6 | §7.1 CI pipeline | Locks in everything else |
| 7 | §5.1 Flyway + PostgreSQL | Do before real traffic, painful after |
| 8 | §4.1 Async report generation | Larger refactor; frontend is already shaped for it |

Everything else can follow incrementally.
