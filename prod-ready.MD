# Production Readiness Review
**Application:** WillAIStealMyJob — AI Career Risk Assessment Platform
**Reviewed:** 2026-05-15
**Stack:** Spring Boot 3, Thymeleaf, OpenAI, Stripe, Flying Saucer (PDF), H2/JPA

---

## Confirmed Working Well

The following areas are solid and require no further work:

- **CSRF protection** — `CookieCsrfTokenRepository` correctly configured; all AJAX POST calls send `X-XSRF-TOKEN`
- **HTTP security headers** — `X-Frame-Options: DENY`, `X-Content-Type-Options`, HSTS (1 year + subdomains), and a CSP matching the app's actual external dependencies (Google Fonts, jsDelivr)
- **XSS** — all template output uses `th:text` (escaped), no `th:utext`; no user data rendered via `innerHTML`
- **SQL injection** — Spring Data JPA throughout, no raw SQL
- **File upload** — extension allowlist, 2 MB cap, Apache Tika for safe parsing
- **Stripe webhook** — signature verified before any processing; critical path (mark paid) and analytics path separated so an analytics failure cannot trigger a Stripe retry
- **Checkout idempotency** — existing open Stripe session reused on duplicate calls
- **Rate limiting** — Bucket4j token bucket on `POST /generate-report`, 5 reports/visitor/hour, returns `429` with `Retry-After`
- **Global exception handler** — unhandled exceptions return clean JSON; no stack traces exposed
- **PII in logs** — profession removed from all INFO/ERROR log statements; raw AI output removed from error logs
- **Report access control** — payment status and expiry both checked before serving a report
- **Thymeleaf caching** — enabled
- **H2 console** — disabled

---

## Verdict

**Close to production-ready.** The foundations are solid. There is one meaningful gap (no size ceiling on AI prompt inputs) and a handful of low-priority items. The app can go live as-is with acceptable risk, but the input size issue should be addressed before sustained public traffic.

---

## Must Fix

### 1 · No size limits on report generation input
**File:** `src/main/java/com/chikere/jobai/model/GenerateReportRequest.java`

`profession`, `description`, and other fields on `GenerateReportRequest` have no length constraints. The `description` field is populated from a previous AI assessment (potentially several hundred words) and sent verbatim into the premium report prompt. A malicious caller can send an arbitrarily large payload and inflate the OpenAI bill with every request, with the rate limiter as the only defence.

**Note:** Attempts to add `@Valid` with `@Size` constraints have broken the report generation flow in testing. The recommended alternative is **server-side silent truncation** inside `PremiumReportAiService.buildPremiumPrompt()` before the prompt is assembled — the input is capped silently rather than rejected, which avoids any risk of breaking the request flow:

```java
String profession = normalise(request.getProfession(), "Unknown");
String description = normalise(request.getDescription(), "Not provided");

// Cap inputs before they reach the prompt — no validation rejection needed
if (profession.length() > 300)    profession = profession.substring(0, 300);
if (description.length() > 4000)  description = description.substring(0, 4000);
```

This is safer than validation annotations for this endpoint given the AI-generated content flowing through it.

---

## Fix Soon

### 2 · Webhook marks report paid without confirming it exists
**File:** `src/main/java/com/chikere/jobai/controller/WebhookController.java`

`markPaidFromWebhook()` is called with the `reportId` from Stripe metadata without first checking the report is still in the database. If a report expired and was purged between checkout and webhook delivery, the call silently does nothing — but there is no log or alert, making payment reconciliation harder. Additionally, if the `reportId` is not a valid UUID, the call will throw an uncaught `IllegalArgumentException`.

Add an existence check and UUID validation before calling the service:

```java
if (!reportService.reportExists(reportId)) {
    log.error("Webhook references unknown reportId={} sessionId={}", reportId, session.getId());
    return ResponseEntity.ok("received");
}
```

### 3 · Rate limiter map grows without bound
**File:** `src/main/java/com/chikere/jobai/service/ReportRateLimiterService.java`

Every unique visitor ID creates a `Bucket` entry in the `ConcurrentHashMap` that is never evicted. Under sustained traffic with many visitors this will cause a gradual memory leak. Each bucket is small (~200 bytes), so this is not an immediate problem, but it will grow over months.

The simplest fix is to replace the `ConcurrentHashMap` with a Caffeine cache with a 24-hour TTL. Caffeine is already available transitively via Spring Boot.

```java
private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
        .expireAfterAccess(24, TimeUnit.HOURS)
        .maximumSize(50_000)
        .build();

public ConsumptionProbe tryConsume(String visitorId) {
    return buckets.get(visitorId, id -> newBucket())
                  .tryConsumeAndReturnRemaining(1);
}
```

---

## Low Priority

### 4 · Expiry time shown without timezone
**File:** `src/main/resources/templates/premium-report.html`

The report expiry timestamp is stored in UTC and displayed as `dd MMM yyyy HH:mm` with no timezone label. A user sees `14 May 2026 10:00` and has no way to know if that is their local time or UTC.

```html
<!-- Change the format pattern from 'dd MMM yyyy HH:mm' to 'dd MMM yyyy HH:mm z' -->
```

### 5 · Stripe metadata truncation is silent
**File:** `src/main/java/com/chikere/jobai/controller/CheckoutController.java` line 81

Profession strings longer than 490 characters are silently truncated before being stored in Stripe metadata. This is correct (Stripe's limit is 500 chars) but there is no log entry when truncation occurs, making it hard to diagnose metadata mismatches.

```java
if (profession != null && profession.length() > 490) {
    log.warn("Profession truncated for Stripe metadata reportId={}", reportId);
}
```

### 6 · PDF generation has no timeout
**File:** `src/main/java/com/chikere/jobai/controller/ReportController.java`

PDF rendering runs on a bounded thread pool (max 4 threads, queue of 20) via `CompletableFuture`. If Flying Saucer hangs on a malformed template, the thread is held indefinitely. Adding `.orTimeout(60, TimeUnit.SECONDS)` to the future would release the thread and return a `503` to the user.

---

## Not a Concern

- **Secrets in `application-dev.properties`** — confirmed not pushed or deployed
- **`HttpOnly: false` on CSRF cookie** — intentional, required for the Double Submit Cookie pattern (JS must read the cookie to send it as a header)
- **No user authentication** — correct for this app; all pages are intentionally public
- **`ddl-auto=update`** — acceptable given the schema is stable and unlikely to change; see earlier discussion
- **H2 in production** — ensure `DATABASE_URL` env var points to a real database (PostgreSQL recommended); H2 file mode is adequate for low traffic but not resilient to container restarts
- **Stack traces to users** — covered by `GlobalExceptionHandler`

---

## Pre-Deployment Checklist

- [ ] Apply server-side input truncation in `PremiumReportAiService` (item 1 above)
- [ ] Add webhook report-existence check (item 2 above)
- [ ] Set `STRIPE_SECRET_KEY` and `STRIPE_WEBHOOK_SECRET` to **live** keys (not test)
- [ ] Set `OPENAI_API_KEY` env var
- [ ] Set `APP_BASE_URL` to the production domain
- [ ] Set `DATABASE_URL` to a production-grade database
- [ ] Set `DATABASE_USERNAME` and `DATABASE_PASSWORD`
- [ ] Verify `SPRING_PROFILES_ACTIVE` does not load dev config
- [ ] Run a full end-to-end test payment on live Stripe keys before opening to traffic
- [ ] Confirm HSTS header only activates once HTTPS is confirmed (do not deploy HSTS without HTTPS)
