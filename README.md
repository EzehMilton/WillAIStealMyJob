# WillAIStealMyJob

A web app that assesses how exposed a job, degree course, or set of A-Level choices is to AI — a free instant assessment, with an optional paid in-depth report (Stripe checkout, PDF download).

## Stack

- Java 21, Spring Boot 3.5 (Maven, `./mvnw` wrapper included)
- Spring AI → OpenAI (two models: a cheap "mini" for free summaries, a report model for paid reports)
- Thymeleaf server-rendered frontend
- Stripe Checkout + webhooks for payment
- H2 (file-based) via JPA — PostgreSQL supported through `DATABASE_URL`
- Flying Saucer for PDF rendering, Bucket4j for rate limiting, Micrometer/Actuator for metrics

## Quickstart

Prerequisites: JDK 21. No local install of Maven needed.

1. Create `src/main/resources/application-dev.properties` (gitignored) with the required secrets:

   ```properties
   spring.ai.openai.api-key=sk-...
   stripe.secret-key=sk_test_...
   stripe.publishable-key=pk_test_...
   stripe.price-id.profession=price_...
   stripe.price-id.course=price_...
   stripe.price-id.a-level-undecided=price_...
   stripe.webhook-secret=whsec_...
   app.base-url=http://localhost:8081
   ```

2. Run:

   ```bash
   ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
   ```

   The app starts on http://localhost:8081 (override with `PORT`). H2 data files are written to `./data/` (gitignored).

3. Test:

   ```bash
   ./mvnw test
   ```

   The suite never calls real AI or Stripe — `NoRealAiSafetyTest` guards this. Test config lives in `src/test/resources/application.properties`.

### Stripe webhooks locally

```bash
stripe listen --forward-to localhost:8081/stripe/webhook
```

Put the printed `whsec_...` into `stripe.webhook-secret`. The endpoint handles `checkout.session.completed`, `checkout.session.expired`, and both `checkout.session.async_payment_*` events — subscribe to all four in the Stripe dashboard for production.

## Configuration

All settings live in `src/main/resources/application.properties`, overridable by environment variables.

Required (no defaults — the app fails to start without them):

| Env var | Purpose |
|---|---|
| `OPENAI_API_KEY` | OpenAI API key |
| `STRIPE_SECRET_KEY`, `STRIPE_PUBLISHABLE_KEY` | Stripe API keys |
| `STRIPE_PRICE_PROFESSIONAL`, `STRIPE_PRICE_STUDENT`, `STRIPE_PRICE_A_LEVEL_UNDECIDED` | Stripe Price IDs per journey |
| `STRIPE_WEBHOOK_SECRET` | Webhook signature secret |
| `APP_BASE_URL` | Public base URL used in checkout redirect URLs |

Common optional ones:

| Env var / property | Default | Purpose |
|---|---|---|
| `PORT` | `8081` | HTTP port |
| `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` | H2 file at `./data/` | Point at PostgreSQL in production |
| `ACTUATOR_USER`, `ACTUATOR_PASSWORD` | unset (locked) | Enables basic-auth access to `/actuator/metrics` and `/actuator/prometheus` |
| `AI_MODEL_MINI`, `AI_MODEL_REPORT` | `gpt-5.4-mini` | Model per path; cost rates via `AI_COST_*` |
| `RATE_LIMIT_REPORT_CAPACITY` / `_REFILL_HOURS` | 5 / 1h | Report-generation rate limit per visitor |
| `AI_CIRCUIT_BREAKER_*` | 3 fails / 30s / 10 calls | Summary-path circuit breaker; report path via `app.ai.report.circuit-breaker.*` |
| `app.report.generation.*` | 2 core / 4 max / queue 20 | Async report generation pool (global concurrency budget) |
| `app.report.dedupe-window-minutes` | 30 | Window in which identical generation requests reuse the same report |
| `app.report.expiry-hours` (`APP_REPORT_EXPIRY_HOURS`) | 24 | Unpaid reports are purged after this |
| `app.cv.parse-timeout-seconds` | 10 | CV (Tika) parse timeout |

## How it works

1. **Free assessment** — `POST /assess` (form: profession/course/A-Level interests, manual text or CV upload). The AI writes the narrative; the deterministic `RiskScoringService` computes the score. Result page offers the paid report.
2. **Paid report** — `POST /generate-report` returns `202` + `reportId` immediately; generation runs on a bounded background pool. The generating page polls `GET /report/{id}/status` until `COMPLETED`, then redirects to `/report/{id}` (locked preview). Scores are recomputed server-side — client-sent values are ignored. Identical requests within the dedupe window return the same report instead of a second AI call.
3. **Payment** — `POST /api/report/{id}/checkout-session` creates (or reuses) a Stripe Checkout session. The webhook marks the report paid only when `payment_status=paid` **and** the session matches the one attached at checkout. Paid reports unlock the full page and `GET /report/{id}/download` (PDF).

## Observability

- **Analytics**: funnel/revenue events are persisted to the `analytics_events` table (source of truth — e.g. `SELECT COUNT(*) FROM analytics_events WHERE event_type = 'payment_completed' AND occurred_at > ...`) and mirrored to the `ANALYTICS` logger.
- **Metrics**: Micrometer counters/timers under the `jobai.*` prefix (AI latency/tokens/cost, circuit-breaker state, generations by outcome, payments, webhook failures). Exposed at `/actuator/metrics` and `/actuator/prometheus`, locked behind `ACTUATOR_USER`/`ACTUATOR_PASSWORD`. `/actuator/health` is public.
- **AI cost**: every model call logs an `AI_COST` line with token counts and estimated USD/GBP cost.

## Project layout

```
src/main/java/com/chikere/jobai/
  controller/      MVC + REST endpoints (assessment, report, checkout, webhook, analytics)
  service/         AI clients, scoring, report lifecycle, PDF, rate limiting
  model/           JPA entities, request/response DTOs, journey config
  repository/      Spring Data repositories
  configuration/   Security (CSP, CSRF, actuator auth), async pools, AI clients
src/main/resources/
  prompts/         AI prompt templates (active ones referenced from JourneyConfigRegistry)
  templates/       Thymeleaf pages
improvements.MD    Known issues / review backlog with fix status
```
