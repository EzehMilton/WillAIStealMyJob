# Current Assessment Journey Map

This codebase currently supports two assessment modes:

- `profession`: a professional/current-role journey.
- `course`: a student/course-or-degree journey.

There is no explicit journey enum yet. The string field `mode` carries journey intent through the application, and downstream code branches on `"course"` versus everything else.

## Current End-to-End Flow

### 1. Intake Page

Entry point:

- `GET /`
- Controller: `RiskAssessorController.home`
- Template: `src/main/resources/templates/index.html`
- Form DTO: `RiskAssessmentForm`

The controller creates a blank `RiskAssessmentForm`, defaults `mode` to `"profession"`, adds word limits, and renders `index`.

The intake form posts to:

- `POST /assess`
- Controller: `RiskAssessorController.assessRisk`

The page exposes two user-facing choices:

- `profession`: "My current profession"
- `course`: "A course or degree I'm studying/considering"

The form fields are:

- `mode`: `"profession"` or `"course"`
- `profession`: reused label for either job title or course/degree name
- `roleSummary`: manual description
- `inputMethod`: `"manual"` or `"cv"`
- `cvFile`: uploaded CV file

Professional users can choose manual entry or CV upload. Course/student users are forced to manual input by the frontend script; CV upload is only treated as active server-side when `inputMethod == "cv"` and `mode == "profession"`.

Word limits:

- `app.form.role-summary-word-limit.profession=800`
- `app.form.role-summary-word-limit.course=450`

Manual word count enforcement happens in `RiskAssessorController.assessRisk`.

### 2. Summary Assessment Generation

Main service:

- `RiskAssessmentService.processAssessment`

Supporting services:

- `DocumentParserService`
- `JobAiService`
- `AnalyticsService`

Professional manual path:

1. User submits `mode=profession`, `inputMethod=manual`.
2. `RiskAssessmentService` requires non-blank `roleSummary`.
3. `JobAiService.assessJobRisk("profession", profession, roleSummary)` is called.

Professional CV path:

1. User submits `mode=profession`, `inputMethod=cv`, and `cvFile`.
2. `RiskAssessmentService` validates file presence, size under 2MB, and extension `.pdf`, `.doc`, or `.docx`.
3. `DocumentParserService` extracts text using Apache Tika.
4. Extracted text must be at least 50 characters.
5. Extracted text becomes `roleSummary`.
6. `JobAiService.assessJobRisk("profession", profession, extractedText)` is called.

Student/course path:

1. User submits `mode=course`.
2. Frontend hides CV upload and sets `inputMethod=manual`.
3. `RiskAssessmentService` ignores CV mode unless `mode=profession`.
4. `roleSummary` is required.
5. `JobAiService.assessJobRisk("course", courseName, expectedCareerPath)` is called.

`JobAiService` behavior:

- Loads prompt files:
  - `prompts/jobai.txt`
  - `prompts/profession-instructions.txt`
  - `prompts/course-instructions.txt`
- Normalizes unknown/blank mode to `profession`.
- For `course`, uses course instructions and labels:
  - input label: `Course/Degree`
  - details label: `Expected Career Path`
- For profession, uses profession instructions and labels:
  - input label: `Profession`
  - details label: `Role Details`
- For real AI mode:
  - profession summary uses `gpt54ChatClient`
  - course summary uses `gpt54MiniChatClient`
- For dummy mode, returns deterministic local output.

Summary output model:

- `JobRiskAssessment`
  - `score`
  - `riskLevel`
  - `summary`
  - `assessment`
  - `generationMetrics`

After generation, `RiskAssessorController` stores the result in redirect flash attributes and redirects to `/result`.

### 3. Results Page and Premium Report Request

Entry point:

- `GET /result`
- Controller: `RiskAssessorController.result`
- Template: `src/main/resources/templates/result.html`

The results page reads flash attributes:

- `mode`
- `profession`
- `score`
- `riskLevel`
- `summary`
- `assessment`
- `success`

The template displays the free snapshot and premium upsell. Copy and price branch on:

- `mode == "course"`: student/course copy and `£2.99`
- otherwise: professional copy and `£4.99`

Clicking `Generate Report Preview` does not pay immediately. It:

1. Builds a browser `sessionStorage.checkoutPayload` object containing:
   - `mode`
   - `profession`
   - `score`
   - `riskLevel`
   - `summary`
   - `assessment`
2. Records an analytics event.
3. Redirects to `/generating-report`.

### 4. Full Premium Report Generation

Entry page:

- `GET /generating-report`
- Controller: `RiskAssessorController.generatingReport`
- Template: `src/main/resources/templates/generating-report.html`

The template runs animation and calls:

- `POST /generate-report`
- Controller: `ReportController.generateReport`
- Request DTO: `GenerateReportRequest`
- Response DTO: `GenerateReportResponse`

Browser payload sent to `/generate-report`:

- `sessionId`: URL param if present, normally blank
- `profession`: from `checkoutPayload.profession`
- `description`: `checkoutPayload.assessment || checkoutPayload.summary`
- `score`
- `riskLevel`
- `mode`

Important current behavior: the original manual role summary or extracted CV text is not carried forward. The premium report prompt receives the short free assessment text as `description`.

`ReportController.generateReport`:

1. Purges expired unpaid reports.
2. Calls `ReportService.generateAndStoreReport`.
3. Records analytics.
4. Returns `{ "reportId": "..." }`.

`ReportService.generateAndStoreReport`:

1. Calls `generateFullReport`.
2. Generates a `PremiumReport` either from dummy mode or `PremiumReportAiService`.
3. Persists a `ReportRequest` entity in table `generated_reports`.
4. Sets `paymentStatus=PENDING`.
5. Sets `expiresAt=now + app.report.expiry-hours`.
6. Stores the full generated report JSON in `reportJson`.

Full report model:

- `PremiumReport`
  - base fields: `reportId`, `profession`, `mode`, `score`, `riskLevel`, `generatedAt`, `generationMetrics`
  - rendered sections: KPIs, executive summary, task map, timeline, skills, salary, adjacent roles, action plan, resources
  - retained/mock fields: AI strengths, human advantages, warning signs, scorecard, level analysis, track comparison

`PremiumReportAiService`:

- Loads `prompts/premium-report-prompt.txt`.
- Branches prompt labels on `mode == "course"` versus profession.
- Maps AI JSON into `PremiumReport`.
- Records generation metrics.

Current wiring note: `AIModelConfiguration` defines `gpt54ReportChatClient`, but `PremiumReportAiService` currently receives a `ChatClient gpt54MiniChatClient` constructor parameter and `app.ai.model.mini`, so full report generation appears to use the mini client despite the dedicated report client existing.

After `/generate-report` returns, `generating-report.html` redirects to:

- `/premium-report/{reportId}`

### 5. Locked Premium Report View

Entry points:

- `GET /report/{reportId}`
- `GET /premium-report/{reportId}`
- Controller: `ReportController.viewReport`
- Template: `src/main/resources/templates/premium-report.html`

`ReportController.viewReport`:

1. Purges expired unpaid reports.
2. If `?checkout=success`, calls `ReportService.syncPaymentStatusIfNeeded`.
3. Calls `ReportService.getReportView`.
4. Adds:
   - `report`
   - `reportId`
   - `reportLocked`
   - `paymentStatus`
   - `expiresAt`
   - `checkoutState`
5. Renders `premium-report`.

`ReportService.getReportView`:

1. Loads `ReportRequest` by UUID.
2. Requires stored `reportJson`.
3. Requires the report to be accessible:
   - `PAID`, or
   - no expiry, or
   - expiry is still in the future
4. Deserializes full `PremiumReport`.
5. If `paymentStatus == PAID`, returns the full report.
6. Otherwise, calls `ReportPreviewService.buildLockedPreview`.

Locked preview fields:

- `reportId`
- `profession`
- `mode`
- `score`
- `riskLevel`
- `generatedAt`
- `disruptionWindow`
- `adaptabilityPotential`
- `executiveSummary`
- `coreAdvice`
- first 3 `taskExposureMap` rows

The full report is generated before payment and stored server-side. Payment only unlocks the already-generated JSON.

### 6. Payment Unlock

Checkout endpoint:

- `POST /api/report/{reportId}/checkout-session`
- Controller: `CheckoutController.createCheckoutSession`
- Response DTO: `CheckoutResponse`

Flow:

1. Loads report view through `ReportService.getReportView`.
2. If already unlocked, returns `/report/{reportId}`.
3. Builds a Stripe Checkout Session.
4. Success URL: `{app.base-url}/report/{reportId}?checkout=success`
5. Cancel URL: `{app.base-url}/report/{reportId}?checkout=cancelled`
6. Chooses Stripe price by `report.mode`:
   - `course` -> `stripe.price-id.course`
   - otherwise -> `stripe.price-id.profession`
7. Adds metadata:
   - `visitorId`
   - `reportId`
   - `profession`
8. Stores `stripeSessionId` on `ReportRequest`.
9. Returns the Stripe checkout URL.

Webhook endpoint:

- `POST /stripe/webhook`
- Controller: `WebhookController.handleWebhook`

Webhook behavior:

- `checkout.session.completed`:
  - reads `reportId` and `visitorId` from metadata
  - calls `ReportService.markPaidFromWebhook`
  - records payment analytics
- `checkout.session.expired`:
  - calls `ReportService.markFailedBySessionId`

Success redirect fallback:

- When the user lands on `/report/{reportId}?checkout=success`, `ReportController` calls `ReportService.syncPaymentStatusIfNeeded`.
- If the stored Stripe session has `paymentStatus == "paid"`, the report is marked `PAID` even if the webhook has not arrived yet.

Unlocked behavior:

- `ReportService.getReportView` returns the full `PremiumReport`.
- `premium-report.html` renders all premium sections.
- Print/save UI becomes available.

Download endpoint:

- `GET /report/{reportId}/download`
- `GET /premium-report/{reportId}/download`
- Controller: `ReportController.downloadReport`
- Service: `PdfService`
- Template: `premium-report-pdf.html`

The endpoint only returns a PDF if `ReportService.getUnlockedReport(reportId)` succeeds. Otherwise it returns `403 FORBIDDEN`.

## Main Implementation Units

### Controllers

- `RiskAssessorController`: intake, summary assessment, result page, sample page, generating page.
- `ReportController`: premium report generation, locked/unlocked report view, PDF download.
- `CheckoutController`: Stripe Checkout Session creation.
- `WebhookController`: Stripe webhook payment status updates.
- `AnalyticsController`: frontend analytics event ingestion.

### Services

- `RiskAssessmentService`: validates intake, handles manual versus CV extraction, delegates summary scoring.
- `JobAiService`: free summary AI prompt selection and summary result mapping.
- `DocumentParserService`: Apache Tika document text extraction.
- `ReportService`: full report generation, persistence, lock/unlock access rules, Stripe session linkage, expiry cleanup.
- `PremiumReportAiService`: full premium report AI prompt and JSON mapping.
- `ReportPreviewService`: builds locked preview from full report.
- `PdfService`: renders unlocked report to PDF using Thymeleaf, jsoup, and Flying Saucer.
- `AnalyticsService`: log-based event and generation/payment analytics.
- `GenerationMetricsService`: token, duration, and estimated cost metrics.

### Models / DTOs / Persistence

- `RiskAssessmentForm`: intake form backing object.
- `JobRiskAssessment`: free summary result.
- `GenerateReportRequest`: browser-to-server full report generation payload.
- `GenerateReportResponse`: generated report id response.
- `PremiumReport`: full report DTO, serialized into `ReportRequest.reportJson`.
- `ReportRequest`: JPA entity for generated reports, payment status, Stripe session id, expiry, and JSON.
- `PaymentStatus`: `PENDING`, `PAID`, `FAILED`.
- `CheckoutResponse`: checkout redirect URL response.
- `AnalyticsEventRequest`: frontend analytics payload.
- `GenerationMetrics`: AI generation telemetry.
- `ReportStatus`, `ReportStatusResponse`, `CheckoutRequest`: present models, not central in the current active flow.

### Templates

- `index.html`: intake UI, mode switch, CV/manual UI, word counter, visitor id, analytics, loading overlay.
- `result.html`: free summary, premium upsell modal, stores `checkoutPayload`, redirects to generating page.
- `generating-report.html`: calls `/generate-report`, waits for animation/API completion, redirects to premium report.
- `premium-report.html`: locked preview, unlock buttons, full unlocked report, print/save UX.
- `premium-report-pdf.html`: PDF rendering template.
- `sample-report.html`: static/sample report page.

### Configuration

- `application.properties`
  - server and Thymeleaf config
  - H2 datasource and JPA `ddl-auto=update`
  - OpenAI/Spring AI model names and timeouts
  - dummy mode flag
  - AI cost estimation
  - Stripe keys and price ids
  - app base URL
  - report expiry
  - form word limits
- `AIModelConfiguration`
  - `gpt54ChatClient`: premium model, temperature 0.2
  - `gpt54MiniChatClient`: mini model, temperature 0.2
  - `gpt54ReportChatClient`: premium model, temperature 0.6, currently appears unused by `PremiumReportAiService`
- Prompt files:
  - `jobai.txt`
  - `profession-instructions.txt`
  - `course-instructions.txt`
  - `premium-report-prompt.txt`

### Tests

- `RiskAssessmentServiceTest`: covers manual input, professional CV input, course mode ignoring CV, validation failures, supported document extensions, and parse errors.
- `JobaiApplicationTests`: context load.
- There is currently no focused test coverage for `ReportService`, checkout, webhook unlock, premium preview locking, or journey-specific prompt selection.

## Key Coupling / Refactor Risks

- Journey is a loose string (`mode`) used by controllers, templates, services, prompts, Stripe price selection, and persisted reports.
- `"course"` is the only special case; all non-course values behave as profession in several places.
- `profession` means both job title and course name.
- `roleSummary` means both role details and expected career path.
- The premium report payload loses the original detailed intake and uses the free summary/assessment text as `description`.
- Premium report generation happens before payment, and payment only unlocks stored JSON.
- Stripe price selection only supports two buckets: course and profession.
- Locked preview assumes all journeys can use the same preview fields and first 3 task rows.
- Templates contain much of the journey-specific copy and price logic inline.
- Existing database rows store old mode strings; migration/backward compatibility matters.

## Safest Refactor Sequence for Three Journeys

Target journeys:

- `PROFESSIONAL`
- `UNIVERSITY_STUDENT`
- `A_LEVEL_UNDECIDED`

Recommended sequence:

1. Add a typed journey concept without changing behavior.
   - Introduce a `JourneyType` enum.
   - Map existing `mode=profession` to `PROFESSIONAL`.
   - Map existing `mode=course` to `UNIVERSITY_STUDENT` initially.
   - Keep old `mode` strings accepted at boundaries for compatibility.
   - Add tests for parsing/defaulting.

2. Create a central journey configuration layer.
   - Define per-journey labels, word limits, input methods, prompt instruction resource, Stripe price key, template copy keys, and whether CV upload is allowed.
   - Replace scattered `"course"` checks gradually with this config.
   - Keep rendered HTML behavior identical at first.

3. Rename concepts internally while preserving API compatibility.
   - Add clearer fields such as `subject`, `details`, and `journeyType` to request/service-level DTOs.
   - Keep `profession`, `roleSummary`, and `mode` as compatibility aliases until templates and JS are migrated.
   - Do not change `ReportRequest.mode` semantics until reads/writes are backward-compatible.

4. Preserve original intake details through premium generation.
   - Store or carry the original manual text / extracted CV text into the premium generation payload.
   - Use the free summary only as supporting context, not as the main `description`.
   - Add a test proving `/generate-report` receives journey, subject, original details, score, and risk level.

5. Split prompt instructions by journey.
   - Keep `jobai.txt` as the shared summary wrapper if useful.
   - Add dedicated instructions for:
     - professional
     - university student
     - A-level undecided
   - Add premium prompt conditioning for each journey before changing UI.
   - Test prompt context selection with mocked AI clients.

6. Extend pricing and Stripe mapping.
   - Add explicit Stripe price properties per journey:
     - `stripe.price-id.professional`
     - `stripe.price-id.university-student`
     - `stripe.price-id.a-level-undecided`
   - Keep old `stripe.price-id.profession` and `stripe.price-id.course` as fallback aliases during transition.
   - Replace `resolvePriceId(String mode)` with `resolvePriceId(JourneyType journeyType)`.

7. Update persistence carefully.
   - Add a `journeyType` column or migrate `mode` values explicitly.
   - For old rows, derive journey from old mode.
   - Keep report JSON deserialization tolerant of old `mode` values.
   - Add repository/service tests for old and new records.

8. Refactor frontend intake last.
   - Replace the two-radio mode selector with three journey choices.
   - Drive labels, placeholders, word limits, CV availability, and hidden values from a small frontend journey config.
   - Keep server-side validation authoritative.
   - Add journeys incrementally:
     - first PROFESSIONAL parity
     - then UNIVERSITY_STUDENT parity from old course
     - then A_LEVEL_UNDECIDED with its own required fields/copy

9. Update report and unlock templates from journey config.
   - Replace `mode == 'course'` template branches with journey-derived values.
   - Centralize price display and feature-list copy.
   - Keep locked/unlocked mechanics unchanged.

10. Add regression coverage before broad copy changes.
   - `RiskAssessmentService`: all three journeys, CV allowed only for professional.
   - `JobAiService`: journey-specific prompt context/model selection.
   - `ReportService`: generation, preview lock, paid unlock, expiry.
   - `CheckoutController`: price id selection for all three journeys.
   - `WebhookController` or service-level payment status update tests.

The lowest-risk path is to introduce the enum and central config first, then migrate all existing `profession`/`course` behavior onto it without changing user-visible behavior. Only after parity tests pass should the A-level undecided journey be added as a new branch.
