# WillAIStealMyFuture — Developer Change Log

A running record of every significant change made to the application, with the reasoning behind each decision.

---

## Session: 13 March 2026

---

### 1. `pom.xml` — Added three new dependencies

**What changed:**
Added `stripe-java`, `flying-saucer-pdf-openpdf`, and `jsoup` to the Maven dependencies. The current build also uses Apache Tika for CV text extraction.

**Why:**
- **`stripe-java:26.3.0`** — The Stripe SDK is needed to create Checkout Sessions server-side. Stripe Checkout handles all payment UI and PCI compliance, so we never touch card data directly.
- **`flying-saucer-pdf-openpdf:9.1.22`** — Flying Saucer converts XHTML to PDF. It remains the report-generation library and is intentionally separate from the CV ingestion pipeline, which now uses Apache Tika for text extraction.
- **`jsoup:1.17.2`** (explicit version required) — Thymeleaf in HTML5 mode produces lenient HTML, not strict XML. Flying Saucer's parser requires valid XHTML. jsoup bridges the gap: it parses the Thymeleaf output and re-serialises it as XML before handing it to Flying Saucer. Spring Boot 3.5.8 does **not** manage jsoup in its BOM, so an explicit version is mandatory — omitting it causes a build failure.

---

### 2. `application.properties` — Stripe configuration and feature flags added

**What changed:**
Added Stripe keys, price IDs, webhook secret, base URL, and the `app.ai.use-dummy` toggle.

**Why:**
- Stripe config uses the pattern `${ENV_VAR:default}` so secrets come from environment variables in production but fall back to test keys locally — no separate profiles needed.
- `app.base-url` is needed by `CheckoutController` to construct the `success_url` and `cancel_url` passed to Stripe. These must be absolute URLs.
- `app.ai.use-dummy=true` lets the app run locally without making real OpenAI calls. When `true`, `ReportService` returns deterministic mock data. Set it to `false` in production.

---

### 3. `RiskAssessorController.java` — Two new GET routes

**What changed:**
Added `GET /sample-report` and `GET /generating-report` route handlers.

**Why:**
- `/sample-report` needed its own controller mapping because it is a standalone Thymeleaf page (not a redirect or form handler).
- `/generating-report` is the page the user lands on after a successful Stripe payment. It needs a controller mapping so Spring can resolve the Thymeleaf template. The page itself drives all its logic through JavaScript after the initial render.

---

### 4. `CheckoutController.java` — New controller (Stripe Checkout)

**What changed:**
New `@RestController` with a single `POST /payment/create-checkout-session` endpoint.

**Why:**
- Stripe Checkout requires a server-side API call to create a session — the secret key must never be exposed to the browser.
- The endpoint receives the assessment payload (profession, score, risk level, mode), picks the correct Stripe Price ID based on mode (`profession` = £4.99, `course` = £2.99), attaches metadata to the session, and returns the hosted Checkout URL to the client.
- Metadata is stored on the session so the backend can recover profession and risk data after the payment redirect, without needing server-side session state.
- `@PostConstruct` is used to initialise `Stripe.apiKey` once at startup rather than setting it on every request.

---

### 5. `ReportController.java` — New controller (report viewing and PDF download)

**What changed:**
New `@Controller` handling three endpoints:
- `POST /generate-report` — generates the report and returns the `reportId`
- `GET /premium-report/{reportId}` — renders the Thymeleaf report page
- `GET /premium-report/{reportId}/download` — streams a PDF

**Why:**
- The generating page (`/generating-report`) calls `POST /generate-report` via `fetch()` in JavaScript once the animation completes. Separating report generation from report viewing means the user sees a progress experience rather than a blocking spinner.
- The download endpoint delegates entirely to `PdfService`, keeping the controller thin. It sets `Content-Disposition: attachment` so the browser prompts a file save rather than attempting to render the PDF inline.

---

### 6. Model classes — Five new classes added

**Files:** `CheckoutRequest.java`, `CheckoutResponse.java`, `GenerateReportRequest.java`, `GenerateReportResponse.java`, `PremiumReport.java`

**Why:**
Each class has a single responsibility:
- `CheckoutRequest` / `CheckoutResponse` — carry data between the browser and `CheckoutController`.
- `GenerateReportRequest` / `GenerateReportResponse` — carry data between the generating page's JS and `ReportController`.
- `PremiumReport` — the central domain model that holds all the report data. It uses the **Builder pattern** (via Lombok `@Builder`) because there are many optional fields and constructing it inline with a long constructor would be unreadable. Inner static classes (`TaskRow`, `CareerLevelRow`, etc.) group related fields without creating separate top-level files.

---

### 7. `ReportService.java` — Mock report builder + AI delegation

**What changed:**
New service with an in-memory `ConcurrentHashMap` store. Builds deterministic mock reports keyed on risk level. Later updated to inject `PremiumReportAiService` and route to real AI when `app.ai.use-dummy=false`.

**Why:**
- **In-memory store** — a database was deliberately avoided for this stage. Reports are short-lived (one session) and adding JPA/persistence would significantly increase complexity. `ConcurrentHashMap` handles concurrent requests safely.
- **Mock fallback** — the mock builder lets the whole payment and report flow be tested end-to-end locally without spending OpenAI credits. Content adapts based on `riskLevel` so the pages are not completely static even in dummy mode.
- **AI delegation** — when `useDummyMode=false`, `generateReport()` calls `premiumReportAiService.generate(request)` instead. The AI service generates its own `reportId` internally, so `ReportService` stores the returned report using whatever ID the AI service assigned, keeping the two services decoupled.

---

### 8. `PdfService.java` — New service (HTML → PDF pipeline)

**What changed:**
New `@Service` that converts a `PremiumReport` object into a `byte[]` PDF.

**Why:**
The pipeline has three stages, each solving a specific problem:
1. **Thymeleaf → HTML string** — `SpringTemplateEngine` is injected and called headlessly (outside a web request) using `org.thymeleaf.context.Context`. This lets PDF generation reuse the same template engine as the web layer with no duplication.
2. **HTML → XHTML** — jsoup re-parses the HTML output and serialises it as XML. This is necessary because Flying Saucer's XML parser will throw on any HTML5 shorthand (e.g. self-closing tags, unquoted attributes).
3. **XHTML → PDF bytes** — Flying Saucer's `ITextRenderer` converts XHTML + CSS 2.1 to a PDF in memory. The result is returned as `byte[]` so the controller can stream it directly with no temp files.

---

### 9. `PremiumReportAiService.java` — New service (AI-powered report generation)

**What changed:**
New `@Service` that calls OpenAI via Spring AI's `ChatClient`, parses the JSON response, and maps it to a `PremiumReport`.

**Why:**
- **Separation of concerns** — AI logic lives in its own service rather than inside `ReportService`. This makes it easy to swap the AI provider, update the prompt, or add retry logic without touching the main service.
- **`gpt54ChatClient` bean** — uses the full-capability model (not the mini variant) because premium report generation requires nuanced, detailed responses across many structured fields.
- **JSON parsing via Jackson `JsonNode`** — the AI response is parsed field-by-field rather than deserialising into a POJO directly. This is intentional: if the AI returns a slightly non-conforming structure (a missing field, an unexpected null), the code degrades gracefully rather than throwing a deserialisation exception on the whole response.
- **`extractJson()`** — strips markdown code fences that some models add around JSON responses. This is a known quirk of GPT-family models.
- **Prompt loaded at startup** — the prompt file is read once in the constructor via `ResourceLoader`. This fails fast at application start if the file is missing, rather than failing silently on the first user request.

---

### 10. `prompts/premium-report-prompt.txt` — Prompt template for AI report generation

**What changed:**
New prompt file with placeholders (`{profession}`, `{score}`, `{riskLevel}`, etc.) and a strict JSON schema.

**Why:**
- **Strict JSON schema in the prompt** — listing every expected field with its type and constraints (e.g. `"Very High | High | Moderate | Low | Very Low"`) dramatically reduces hallucinated field names or unexpected structures. The AI is told to return only JSON with no prose.
- **Placeholders replaced at runtime** — the service does simple string `.replace()` calls rather than a templating engine. This avoids a dependency and is sufficient because the prompt has no loops or conditionals.
- **Quantity guidelines** — telling the AI how many items to produce per array (e.g. "6–9 items for taskExposureMap") prevents both sparse and excessively long responses that would break the PDF layout.

---

### 11. `result.html` — Added buttons, premium modal, and Stripe payment flow

**What changed:**
- Added "View Sample Report" button between existing buttons, with `flex-nowrap` to keep all three on one row
- Added Bootstrap modal triggered by "Unlock Premium Insights" button
- Added JavaScript that POSTs to `/payment/create-checkout-session` and redirects to Stripe Checkout
- Used `sessionStorage` to persist the assessment payload across the Stripe redirect

**Why:**
- **`sessionStorage` bridge** — after a successful Stripe payment, the browser is redirected to `/generating-report`. At that point, the assessment data (profession, score, risk level) needs to be available to call `POST /generate-report`. Since there is no server-side session, the data is saved to `sessionStorage` before the Stripe redirect and read back on the generating page.
- **`th:inline="javascript"`** — used to safely inject server-side Thymeleaf variables (mode, profession, score) into the JavaScript without XSS risk. Thymeleaf escapes the values correctly for a JS context.

---

### 12. `generating-report.html` — New animated progress page

**What changed:**
Completely new page with 5 sequential animated steps, a progress bar, and JavaScript that drives both the animation and the API call concurrently.

**Why:**
- The AI report generation takes several seconds. Rather than making the user stare at a spinner, this page shows a sequential animation that communicates progress.
- The API call (`POST /generate-report`) and the animation run **concurrently**. The redirect to the premium report only happens when **both** are complete — whichever finishes last triggers the navigation. This prevents either a jarring immediate redirect or the animation finishing before the API responds.
- `sessionStorage` is read here to reconstruct the payload, combined with the Stripe `session_id` from the URL query string.

---

### 13. `sample-report.html` — New sample report page (static)

**What changed:**
New page accessible at `/sample-report`, showing a hardcoded example report for "Software Developer".

**Why:**
Prospective users need to see what they are buying before paying. The sample report uses identical CSS and layout to the real premium report, so it accurately represents the product. It is intentionally static — there is no model binding needed since it is always the same example.

---

### 14. `premium-report.html` — Converted to dynamic Thymeleaf template

**What changed:**
The page was first redesigned (by the developer) as a static dark-themed HTML file, then converted to a full Thymeleaf template bound to the `PremiumReport` model.

**Why:**
- The dark theme (CSS variables, DM Serif Display / DM Sans / JetBrains Mono fonts, SVG gauge, risk bars) was the developer's design decision to give the report a premium feel distinct from the site's main Bootstrap theme.
- The Thymeleaf conversion binds every section to real model data: the SVG gauge uses `th:attr` to set `stroke-dashoffset` and `stroke` dynamically from `report.score` and `report.riskLevel`; risk bars use conditional `th:style` to map the exposure string ("Very High", "High", etc.) to a bar width percentage; all tables and lists use `th:each`.
- The **Download PDF** button links to `/premium-report/{reportId}/download` using Thymeleaf's `@{...}` URL syntax with path variable injection.

**Key Thymeleaf patterns used:**
```
// Dynamic SVG attribute (stroke colour + dashoffset in one th:attr)
th:attr="stroke=${...},stroke-dashoffset=${339.3 * (1.0 - report.score / 10.0)}"

// Exposure string → bar width
th:style="${row.exposure == 'Very High' ? 'width:90%' : (row.exposure == 'High' ? 'width:74%' : ...)}"

// Risk level → CSS badge class
th:class="${report.riskLevel == 'High' ? 'badge badge-red' : ...}"

// Path variable in link
th:href="@{/premium-report/{id}/download(id=${report.reportId})}"
```

---

### 15. `premium-report-pdf.html` — New PDF-specific template

**What changed:**
New Thymeleaf template used exclusively by `PdfService` to generate the downloadable PDF. Visually matches the dark theme of `premium-report.html` but uses only CSS 2.1.

**Why:**
Flying Saucer (the PDF renderer) implements **CSS 2.1 only** — it does not support:
- CSS custom properties (`var(--accent)`) → replaced with direct hex values
- Flexbox (`display: flex`) → replaced with `float` layouts and HTML `<table>` elements
- CSS Grid (`display: grid`) → replaced with `<table>` for all multi-column layouts
- `linear-gradient()` / `radial-gradient()` → replaced with solid background colours
- Google Fonts → replaced with system font fallbacks (Georgia, Arial, Courier New)

The SVG gauge works in Flying Saucer using a `<g transform="rotate(-90 65 65)">` wrapper (since the CSS `transform: rotate(-90deg)` on the `<svg>` element is not applied by Flying Saucer), with `th:attr` for the dynamic `stroke-dashoffset`.

A separate template is used rather than one shared template with conditional CSS because the CSS differences are fundamental — not cosmetic — and merging them would make both templates harder to maintain.

---

## How to update this file

When making a change, add a new dated section at the top of the file (below the header) with:
1. The file(s) changed
2. What was changed (briefly)
3. **Why** — the reasoning, trade-off, or constraint that drove the decision

The goal is to capture decisions that are not obvious from reading the code.
