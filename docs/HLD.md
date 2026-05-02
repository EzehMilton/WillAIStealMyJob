# WillAIStealMyJob — High-Level Design (HLD)

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Product Overview](#2-product-overview)
3. [Architecture Overview](#3-architecture-overview)
4. [End-to-End Application Flow](#4-end-to-end-application-flow)
5. [User Journeys](#5-user-journeys)
6. [Important Domain Concepts](#6-important-domain-concepts)
7. [Key Classes and Responsibilities](#7-key-classes-and-responsibilities)
8. [Prompt and AI Design](#8-prompt-and-ai-design)
9. [Report Locking and Payment Flow](#9-report-locking-and-payment-flow)
10. [Data Flow](#10-data-flow)
11. [Configuration](#11-configuration)
12. [Templates / Screens](#12-templates--screens)
13. [Testing Overview](#13-testing-overview)
14. [Known Design Decisions](#14-known-design-decisions)
15. [Risks and Future Improvements](#15-risks-and-future-improvements)

---

## 1. Executive Summary

**WillAIStealMyJob** is a web application that helps people understand how artificial intelligence might affect their career, education, or future options.

It does this by:

1. Asking the user a few questions about their job, course, or school subjects.
2. Sending that information to an AI model that analyses the risk of AI disruption.
3. Showing the user a free summary score and brief assessment.
4. Offering a detailed, paid premium report (unlocked via Stripe payment) that includes timelines, skill recommendations, salary comparisons, career pivots, and actionable plans.

The application targets three types of users:

- **Professionals** who want to know whether their current job is at risk from AI automation.
- **University or course students** who want to know whether their degree or study path will lead to a resilient career.
- **School students and pre-university / undecided individuals** who are exploring subject choices and want to understand AI's effect on their future options.

---

## 2. Product Overview

The application runs as a single Spring Boot web app with **three user journeys** (called "modes" internally). All three journeys share the same form, controllers, services, and report structure — only the labels, word limits, AI instructions, and pricing differ.

### Journey 1 — Professional

> "Will AI take my job?"

- User enters their job title and describes their role.
- They can type a manual description **or** upload their CV (PDF, DOC, DOCX).
- The AI assesses their job's automation risk on a 0–10 scale.
- Premium report includes: task exposure map, career transition paths, salary intelligence, and a 1-year action plan.
- Price: **£4.99**

### Journey 2 — University Student / Course User

> "Is my degree future-proof?"

- User enters the name of their course or degree and describes their career goals.
- No CV upload (CV is not relevant for prospective students).
- The AI assesses how AI disruption might affect the career paths their course leads to.
- Premium report includes: career path risk, AI-proof skills, degree ROI, and recommended additional skills.
- Price: **£2.99**

### Journey 3 — School Student / Pre-University / Undecided

> "What should I study and where is it heading?"

- User enters their subject interests, strengths, and what kind of work they enjoy.
- No CV upload.
- The AI advises on subject combinations, future career clusters, and how AI-exposed those paths are.
- The scoring here reflects "AI future-readiness risk" — low means flexible and human-centred; high means narrow or highly automatable.
- Price: **£0.99**

> **Note on naming**: The backend currently uses `mode=a_level` internally for this journey and `JourneyType.A_LEVEL_UNDECIDED` as the enum value. This is a legacy label. The user-facing UI uses global language — "school student", "pre-university", or "still exploring options" — to serve users worldwide, not just those in the UK A-Level system.

---

## 3. Architecture Overview

The application is a **server-rendered Spring Boot monolith** with external integrations for AI (OpenAI) and payments (Stripe).

```
┌──────────────────────────────────────────────────────────────────┐
│                          Browser / User                           │
└───────────────────────────────┬──────────────────────────────────┘
                                │  HTTP (Thymeleaf HTML)
┌───────────────────────────────▼──────────────────────────────────┐
│                        Web / UI Layer                             │
│    index.html · result.html · generating-report.html             │
│    premium-report.html · premium-report-pdf.html                  │
└───────────────────────────────┬──────────────────────────────────┘
                                │
┌───────────────────────────────▼──────────────────────────────────┐
│                       Controller Layer                            │
│  RiskAssessorController · ReportController · CheckoutController  │
│  WebhookController · AnalyticsController                         │
└────────┬──────────────────────┬──────────────────────────────────┘
         │                      │
┌────────▼──────────┐  ┌────────▼──────────────────────────────────┐
│   Service Layer   │  │          Configuration & Registry          │
│ RiskAssessment    │  │  JourneyConfigRegistry · AIModelConfig     │
│ JobAiService      │  └───────────────────────────────────────────┘
│ ReportService     │
│ PremiumReportAi   │
│ DocumentParser    │
│ ReportPreview     │
│ PdfService        │
│ Analytics         │
│ GenerationMetrics │
└────────┬──────────┘
         │
┌────────▼──────────────────────────────────────────────────────────┐
│                    AI / Prompt Layer                               │
│  Spring AI ChatClient → OpenAI gpt-5.4 / gpt-5.4-mini            │
│  Prompt files: jobai.txt · premium-report-prompt.txt              │
│  Journey instructions: profession / course / a-level              │
└────────────────────────────────────────────────────────────────────┘
         │
┌────────▼──────────────────────────────────────────────────────────┐
│                    Persistence / Database Layer                    │
│  Spring Data JPA · H2 (dev) / PostgreSQL (prod)                   │
│  Entity: ReportRequest · Repository: ReportRequestRepository      │
└────────────────────────────────────────────────────────────────────┘
         │
┌────────▼──────────────────────────────────────────────────────────┐
│                    Payment / Stripe Layer                          │
│  CheckoutController → Stripe Session API                          │
│  WebhookController ← Stripe Webhook Events                        │
└────────────────────────────────────────────────────────────────────┘
         │
┌────────▼──────────────────────────────────────────────────────────┐
│                  PDF / Export Layer                                │
│  PdfService → Thymeleaf → Flying Saucer → byte[]                  │
└────────────────────────────────────────────────────────────────────┘
         │
┌────────▼──────────────────────────────────────────────────────────┐
│                     Analytics Layer                                │
│  AnalyticsService → Structured log events (ANALYTICS logger)      │
│  Events: summary_generated, report_delivered, payment_completed    │
└────────────────────────────────────────────────────────────────────┘
```

**Tech Stack**

| Component | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.5.8 |
| Templating | Thymeleaf 3 |
| AI integration | Spring AI 1.1.2 (OpenAI) |
| Payments | Stripe Java SDK 26.3 |
| Document parsing | Apache Tika |
| PDF generation | Flying Saucer + jsoup |
| Database | H2 (dev) / PostgreSQL (prod) |
| ORM | Spring Data JPA + Hibernate |
| Build | Maven |

---

## 4. End-to-End Application Flow

### Overview Sequence

```
User                   App                         OpenAI         Stripe
 │                      │                              │               │
 │  GET /               │                              │               │
 │─────────────────────►│                              │               │
 │◄─────────────────────│ index.html (mode-aware form) │               │
 │                      │                              │               │
 │  POST /assess        │                              │               │
 │─────────────────────►│                              │               │
 │                      │──── assessJobRisk() ────────►│               │
 │                      │◄─── JobRiskAssessment JSON ──│               │
 │◄─────────────────────│ redirect → /result           │               │
 │                      │                              │               │
 │  GET /result         │                              │               │
 │─────────────────────►│                              │               │
 │◄─────────────────────│ result.html (score + summary)│               │
 │                      │                              │               │
 │  [Click "Unlock Full Report"]                        │               │
 │  GET /generating-report                              │               │
 │─────────────────────►│                              │               │
 │◄─────────────────────│ generating-report.html       │               │
 │                      │                              │               │
 │  POST /generate-report (AJAX from page)              │               │
 │─────────────────────►│                              │               │
 │                      │──── generate() ─────────────►│               │
 │                      │◄─── PremiumReport JSON ───────│               │
 │                      │ store in DB (PENDING)         │               │
 │◄─────────────────────│ { reportId: "uuid" }         │               │
 │                      │                              │               │
 │  GET /report/{id}    │                              │               │
 │─────────────────────►│                              │               │
 │◄─────────────────────│ premium-report.html (LOCKED) │               │
 │                      │                              │               │
 │  [Click "Unlock — Pay £X"]                           │               │
 │  POST /api/report/{id}/checkout-session              │               │
 │─────────────────────►│                              │               │
 │                      │──────────────────────────────────────────────►│
 │                      │◄────────────────────────── Stripe session URL │
 │◄─────────────────────│ { url: "stripe.com/..." }    │               │
 │                      │                              │               │
 │  [Redirected to Stripe payment page]                 │               │
 │──────────────────────────────────────────────────────────────────────►│
 │◄─────────────────────────────────────────────────────────────────────│
 │                      │                              │               │
 │                      │◄──────────── POST /stripe/webhook (session.completed)
 │                      │  markPaid(reportId)          │               │
 │                      │                              │               │
 │  GET /report/{id}?checkout=success                   │               │
 │─────────────────────►│                              │               │
 │                      │ syncPaymentStatus()          │               │
 │◄─────────────────────│ premium-report.html (UNLOCKED)│              │
 │                      │                              │               │
 │  GET /report/{id}/download                           │               │
 │─────────────────────►│                              │               │
 │◄─────────────────────│ PDF bytes (Content-Disposition: attachment)  │
```

---

## 5. User Journeys

### A. Professional User

**What they enter**
- Job title / profession (required, max 100 chars)
- Role description — *either*:
  - Manual text (up to 800 words)
  - CV file upload (PDF, DOC, DOCX; max 2 MB)

**How CV parsing works**
- DocumentParserService receives the uploaded MultipartFile.
- File is validated: size ≤ 2 MB, extension whitelisted, not empty.
- Apache Tika extracts plain text from the document.
- The extracted text (≥ 50 chars required) becomes the `roleSummary`.
- If parsing fails, the user is offered the option to type manually instead.

**How the free summary is generated**
- RiskAssessmentService passes the job title and role summary to JobAiService.
- JobAiService selects the **premium AI model** (gpt-5.4) for professional assessments — higher accuracy for working adults.
- The prompt (`jobai.txt`) is combined with `profession-instructions.txt` which focuses on: task automation, human skill requirements, industry AI adoption, augmentation vs replacement, and economic factors.
- The AI returns a JSON object: score (0–10), riskLevel, summary (1 sentence), assessment (2 sentences).

**What the premium report includes**
1. Cover KPIs: disruption window and adaptability potential
2. Executive summary and core advice
3. Task exposure map (each task area scored 0–100% exposure)
4. Timeline (4 phases showing how the role evolves over time)
5. Skill cards (recommended skills with priority labels)
6. Salary intelligence (traditional vs AI-augmented role comparison)
7. Adjacent career paths (pivot options with difficulty ratings)
8. Action plans (30-day, 90-day, 1-year)
9. Resources (courses, tools, books, communities)

**Payment and unlock**
- User pays £4.99 via Stripe.
- Stripe price ID: `STRIPE_PRICE_PROFESSIONAL` environment variable.
- After payment, full report is visible and PDF download is enabled.

---

### B. University Student / Course User

**What they enter**
- Course or degree name (required)
- Description of their career goals or what they hope to do after studying (up to 450 words)

**Why CV upload is disabled**
- CV upload is only meaningful for people with work history.
- For students, the relevant context is their academic direction, not employment history.
- `JourneyConfig.isCvAllowed = false` for this journey.

**How course/career goals are assessed**
- Uses the **mini AI model** (gpt-5.4-mini) — cost-efficient, suitable for course assessments.
- Prompt uses `course-instructions.txt`, which focuses on: career pathway viability, AI-proof elements of the degree, job market demand, ROI on the course, skills longevity, and AI-complementary skills to pursue.

**What the premium report includes**
- Same 8-section structure, but tailored framing:
  - Task exposure map reflects typical graduate job tasks, not a current role.
  - Salary intelligence shows graduate starting salaries vs AI-augmented equivalents.
  - Action plans focus on in-study and post-graduation steps.

**Payment and unlock**
- User pays £2.99 via Stripe.
- Stripe price ID: `STRIPE_PRICE_STUDENT` environment variable.

---

### C. School Student / Pre-University / Undecided User

**What they enter**
- Their interests, strengths, preferred subjects, and what kind of work they find appealing (up to 350 words)

**Why this is not "A-Level only"**
- Internally the mode is labelled `a_level` for legacy reasons.
- The prompt file is `a-level-instructions.txt`.
- However, the user-facing language is intentionally global: "school student", "pre-university", or "still deciding".
- The concept of subject choice before higher education is universal; the A-Level label is an implementation detail, not a product constraint.

**How subject interests/strengths are assessed**
- Uses the **mini AI model** (gpt-5.4-mini).
- `a-level-instructions.txt` instructs the AI to: consider interests and strengths, advise on subject combinations, map those to future career clusters, and assess how AI-exposed those clusters are.
- The **risk score** here means "AI future-readiness risk" — a low score means the student is pointing toward flexible, human-centred work; a high score means the path is narrow or automatable without strong human skills.

**What the premium report includes**
- Task exposure map framed as "typical tasks in careers this path leads to".
- Timeline is framed around study milestones and early career, not a current job.
- Action plans cover: subject choices now, university applications, early career pivots.
- Salary intelligence reflects entry-level and graduate ranges.

**Payment and unlock**
- User pays £0.99 via Stripe.
- Stripe price ID: `STRIPE_PRICE_A_LEVEL_UNDECIDED` environment variable (falls back to `STRIPE_PRICE_STUDENT` if not set).

---

## 6. Important Domain Concepts

### JourneyType

| | |
|---|---|
| **What it is** | An enum with three values: `PROFESSIONAL`, `UNIVERSITY_STUDENT`, `A_LEVEL_UNDECIDED` |
| **Why it exists** | To replace string-based `mode` comparisons with a type-safe, IDE-friendly enum throughout the application |
| **Key detail** | Each value carries a `legacyMode()` string (`"profession"`, `"course"`, `"a_level"`) for backward compatibility with form submissions and stored data |
| **Where used** | JourneyConfigRegistry, JourneyConfig, CheckoutController (price routing), JobAiService (model selection), PremiumReportAiService (framing), ReportService |

---

### JourneyConfig

| | |
|---|---|
| **What it is** | A `record` holding all journey-specific metadata |
| **Why it exists** | Eliminates scattered `if (mode.equals("profession"))` checks — all journey-specific values live in one place |
| **Contents** | JourneyType, legacy mode string, display name, form labels, word limit, whether CV is allowed, prompt resource file, free preview copy, price display string |
| **Where used** | RiskAssessorController (form rendering), RiskAssessmentService (validation messages), JobAiService (prompt selection), ReportController (display copy) |

---

### JourneyConfigRegistry

| | |
|---|---|
| **What it is** | A Spring singleton service holding an `EnumMap<JourneyType, JourneyConfig>` |
| **Why it exists** | Central registry so any class can look up journey config without constructing it themselves |
| **Key methods** | `get(JourneyType)`, `get(String legacyMode)` |
| **Where used** | Injected into any class that needs journey-specific behaviour |

---

### RiskAssessmentForm

| | |
|---|---|
| **What it is** | The form model bound from `POST /assess` |
| **Fields** | `mode` (journey selector), `profession` (job/course/interests), `roleSummary` (optional manual text), `inputMethod` ("manual" or "cv"), `cvFile` (optional MultipartFile) |
| **Why it exists** | Encapsulates raw user input before validation and processing |
| **Where used** | Bound by Spring MVC in RiskAssessorController; passed to RiskAssessmentService |

---

### AssessmentProcessingResult

| | |
|---|---|
| **What it is** | A `record` returned by RiskAssessmentService after a successful free assessment |
| **Contents** | `JobRiskAssessment` (AI output), `JourneyConfig` (resolved journey), `originalDetails` (the role summary text used — preserved for later premium report generation) |
| **Why it exists** | Bundles everything the controller needs in one object, and carries `originalDetails` so that the premium report can be generated from the same input the user gave |
| **Where used** | Returned from RiskAssessmentService.processAssessmentWithDetails(); stored in flash attributes; used in GenerateReportRequest |

---

### JobRiskAssessment

| | |
|---|---|
| **What it is** | The structured output from the free AI assessment |
| **Fields** | `score` (0.0–10.0), `riskLevel` ("Low" / "Moderate" / "High"), `summary` (1 sentence), `assessment` (2 sentences), `generationMetrics` |
| **Why it exists** | Strongly-typed container for the AI's JSON response |
| **Where used** | Returned by JobAiService; stored in flash attributes; displayed on result.html |

---

### PremiumReport

| | |
|---|---|
| **What it is** | The full paid report structure with 8 sections |
| **Fields** | Cover KPIs (disruptionWindow, adaptabilityPotential), executiveSummary, coreAdvice, taskExposureMap (list of TaskRow), timelineEvents (4 TimelineEvent), skillCards, salaryData (traditional vs augmented), adjacentRoles (TransitionRow list), actionPlans (30-day / 90-day / year), resources (ResourceCard list) |
| **Why it exists** | One shared structure for all three journeys — journey-specific framing is handled at the prompt level, not by having separate report types |
| **Where used** | Generated by PremiumReportAiService; serialised to JSON for DB storage; rendered by ReportController and PdfService |

---

### ReportRequest

| | |
|---|---|
| **What it is** | The JPA entity stored in the `generated_reports` database table |
| **Fields** | `id` (UUID), `profession`, `mode`, `riskScore`, `riskLevel`, `paymentStatus`, `stripeSessionId`, `expiresAt`, `reportJson` (LOB — serialised PremiumReport), timestamps |
| **Why it exists** | Persists the premium report between generation and payment; tracks payment lifecycle |
| **Where used** | Created by ReportService; queried by ReportController and WebhookController |

---

### PaymentStatus

| | |
|---|---|
| **What it is** | Enum: `PENDING`, `PAID`, `FAILED` |
| **PENDING** | Report generated, Stripe session not yet completed |
| **PAID** | Payment confirmed (via webhook or sync); report unlocked |
| **FAILED** | Stripe session expired without payment |
| **Where used** | ReportRequest entity; isAccessible() logic in ReportService; report lock/unlock rendering in templates |

---

### GenerationMetrics

| | |
|---|---|
| **What it is** | Tracks AI usage for a single generation call |
| **Fields** | `reportType`, `model`, `durationMs`, `promptTokens`, `completionTokens`, `totalTokens`, `estimatedCostUsd` |
| **Why it exists** | Cost monitoring and operational visibility |
| **Where used** | Attached to JobRiskAssessment and PremiumReport; logged via AnalyticsService |

---

## 7. Key Classes and Responsibilities

### Controllers

| Class | Path | Responsibility | Key Methods | Journeys |
|---|---|---|---|---|
| **RiskAssessorController** | `controller/` | Handles home page and form submission; validates input; orchestrates free assessment | `GET /`, `POST /assess`, `GET /result` | All |
| **ReportController** | `controller/` | Triggers premium report generation; serves locked/unlocked report; payment sync; PDF download | `POST /generate-report`, `GET /report/{id}`, `GET /report/{id}/download` | All |
| **CheckoutController** | `controller/` | Creates Stripe checkout session with journey-specific price ID | `POST /api/report/{id}/checkout-session` | All |
| **WebhookController** | `controller/` | Receives and verifies Stripe webhook events; marks reports PAID or FAILED | `POST /stripe/webhook` | All |
| **AnalyticsController** | `controller/` | Receives frontend analytics events | `POST /analytics/event` | All |

---

### Services

| Class | Path | Responsibility | Key Methods | Main Collaborators | Journeys |
|---|---|---|---|---|---|
| **RiskAssessmentService** | `service/` | Validates form input; extracts text from CV or manual input; calls AI for scoring | `processAssessmentWithDetails()` | JobAiService, DocumentParserService, JourneyConfigRegistry | All |
| **JobAiService** | `service/` | Builds prompt and calls OpenAI for free summary assessment; handles dummy mode | `assessJobRisk()` | Spring AI ChatClient, GenerationMetricsService | All |
| **JourneyConfigRegistry** | `service/` | Provides journey-specific config (labels, limits, prompt file, pricing) | `get(JourneyType)`, `get(String)` | — | All |
| **DocumentParserService** | `service/` | Extracts text from uploaded CV file using Apache Tika | `extractText(MultipartFile)` | Apache Tika | Professional only |
| **ReportService** | `service/` | Generates and persists premium report; manages payment status; handles expiry and purge | `generateAndStoreReport()`, `getReportView()`, `markPaidFromWebhook()`, `syncPaymentStatusIfNeeded()`, `purgeExpiredUnpaidReports()` | PremiumReportAiService, ReportPreviewService, ReportRequestRepository | All |
| **PremiumReportAiService** | `service/` | Generates full 8-section premium report via AI; handles dummy mode | `generate(GenerateReportRequest)` | Spring AI ChatClient, GenerationMetricsService | All |
| **ReportPreviewService** | `service/` | Builds a limited preview (first 3 task rows, clears paid sections) for locked reports | `buildLockedPreview(PremiumReport)` | — | All |
| **PdfService** | `service/` | Renders premium report to PDF using Thymeleaf + Flying Saucer | `generateReportPdf(PremiumReport)` | Thymeleaf, Flying Saucer | All |
| **AnalyticsService** | `service/` | Logs structured analytics events to a dedicated logger | `recordSummaryGenerated()`, `recordPaymentCompleted()`, `recordReportDelivered()`, `record()` | SLF4J ANALYTICS logger | All |
| **GenerationMetricsService** | `service/` | Extracts token counts and estimates USD cost from AI responses | `fromChatResponse()`, `forLocalGeneration()` | Spring AI ChatResponse | All |

---

### Models

| Class | Path | Responsibility | Journeys |
|---|---|---|---|
| **JourneyType** | `model/` | Enum — type-safe journey selector with legacy mode aliases | All |
| **JourneyConfig** | `model/` | Record — all journey-specific metadata in one place | All |
| **RiskAssessmentForm** | `model/` | Spring MVC form binding model for `/assess` POST | All |
| **AssessmentProcessingResult** | `model/` | Record — bundles AI output, journey config, and original input for downstream use | All |
| **JobRiskAssessment** | `model/` | Structured free assessment result from AI (score, level, summary, assessment) | All |
| **PremiumReport** | `model/` | Full paid report with 8 nested sections | All |
| **ReportRequest** | `model/` | JPA entity for report persistence and payment tracking | All |
| **PaymentStatus** | `model/` | Enum: PENDING / PAID / FAILED | All |
| **GenerationMetrics** | `model/` | AI usage metrics (tokens, cost, duration) | All |

---

## 8. Prompt and AI Design

### AI Models Used

| Purpose | Model | Temperature | Why |
|---|---|---|---|
| Free assessment — Professional | gpt-5.4 (premium) | 0.2 | Higher accuracy for career risk; user pays for reliability |
| Free assessment — Student / School | gpt-5.4-mini | 0.2 | Cost-efficient; student assessments less nuanced |
| Premium report generation | gpt-5.4-mini | 0.6 | All journeys; slightly higher temperature for richer narrative |

The three ChatClient beans are defined in `AIModelConfiguration`:
- `gpt54ChatClient` — premium model, temp 0.2
- `gpt54MiniChatClient` — mini model, temp 0.2
- `gpt54ReportChatClient` — mini model, temp 0.6

---

### Summary Prompt Flow

```
jobai.txt
  + profession-instructions.txt  (if PROFESSIONAL)
  + course-instructions.txt      (if UNIVERSITY_STUDENT)
  + a-level-instructions.txt     (if A_LEVEL_UNDECIDED)
  + {profession}, {roleSummary}
  ──────────────────────────────────────────────────────►  OpenAI
                                                           returns JSON
  ◄──────────────────────────────────────────────────────
  { score, riskLevel, summary, assessment }
```

**Prompt template variables in `jobai.txt`:**

| Variable | Value |
|---|---|
| `{mode}` | Journey display name |
| `{modeInstructions}` | Contents of the relevant `*-instructions.txt` file |
| `{inputLabel}` | "Job Title" / "Course Name" / "Interests" |
| `{detailsLabel}` | "Role Summary" / "Career Goals" / "Interests & Strengths" |
| `{profession}` | What the user entered for their job/course/interests |
| `{roleSummary}` | The manual text or CV-extracted text |

**Output rules enforced in `jobai.txt`:**
- JSON only — no markdown, no prose.
- Summary: max 16 words, one sentence.
- Assessment: exactly 2 sentences (1st: AI's effect; 2nd: "open loop" to build curiosity).
- Tone: cautious but not alarmist.

---

### Premium Report Prompt Flow

```
premium-report-prompt.txt
  + journey-specific framing (injected inline)
  + {profession}, {mode}, {score}, {riskLevel}, {originalDetails}
  ──────────────────────────────────────────────────────────────►  OpenAI
                                                                   returns JSON
  ◄──────────────────────────────────────────────────────────────
  PremiumReport JSON (8 sections)
```

**Output rules in `premium-report-prompt.txt`:**
- Full JSON structure required — all 8 sections must be present.
- Tone: honest, empowering, actionable.
- Salary figures: UK market context.
- Section emphasis varies by journey (e.g., "adjacent roles" framed differently for students vs professionals).

---

### All Prompt Files

| File | Used By | Purpose |
|---|---|---|
| `jobai.txt` | JobAiService | Main free assessment prompt shell — all journeys |
| `profession-instructions.txt` | JobAiService | Professional journey-specific assessment instructions |
| `course-instructions.txt` | JobAiService | University student journey-specific instructions |
| `a-level-instructions.txt` | JobAiService | School/pre-university journey-specific instructions |
| `premium-report-prompt.txt` | PremiumReportAiService | Full premium report prompt — all journeys |

---

### JSON Parsing

- Both AI services call `cleanJsonResponse()` before parsing — strips markdown code fences (` ```json `, ` ``` `) and extracts the first `{...}` JSON object found.
- Jackson ObjectMapper deserialises the cleaned string into typed Java objects.
- Parsing failures are logged; a generic error is returned to the user.

---

### Dummy Mode

When `app.ai.use-dummy=true`:

- **JobAiService** generates a deterministic mock score based on a hash of `mode + profession + content`. Keyword-based adjustments are applied:
  - "repetitive", "data entry", "routine" → score + 1.4 (higher risk)
  - "analysis", "problem solving" → score + 0.8
  - "leadership", "strategic" → score − 1.2 (lower risk)
  - "field work", "physical" → score − 0.6
- **ReportService** builds a mock PremiumReport with all sections populated using plausible placeholder data.
- No calls to OpenAI or Stripe are made.
- Useful for local development and CI testing without live API keys.

---

### Generation Metrics Tracking

Every AI call records:
- Model name
- Duration (ms)
- Prompt tokens, completion tokens, total tokens
- Estimated cost in USD (calculated from per-million-token pricing in properties)

These metrics are attached to the AI output objects and logged via AnalyticsService under `event=generation_completed`.

---

## 9. Report Locking and Payment Flow

### Why reports are generated before payment

The full premium report is generated **before** the user pays. This is a deliberate product decision:

1. It allows the user to see a limited locked preview immediately — creating trust and demonstrating value.
2. It avoids making the user wait after payment (a poor UX).
3. It ensures payment and delivery happen close together with no generation failure risk after money is taken.

The trade-off is that a small number of reports are generated but never paid for. These are purged after 24 hours.

---

### Payment / Unlock Sequence

```
User                   App                       Stripe
 │                      │                            │
 │  POST /generate-report                            │
 │─────────────────────►│                            │
 │                      │  PremiumReportAiService.generate()
 │                      │  ReportService.save() → PaymentStatus.PENDING
 │◄─────────────────────│  { reportId: "uuid" }      │
 │                      │                            │
 │  GET /report/{id}    │                            │
 │─────────────────────►│                            │
 │◄─────────────────────│  LOCKED preview (3 tasks visible, rest hidden)
 │                      │                            │
 │  POST /api/report/{id}/checkout-session           │
 │─────────────────────►│                            │
 │                      │──── Session.create() ─────►│
 │                      │◄─── { url, sessionId } ────│
 │                      │  attachStripeSession(reportId, sessionId)
 │◄─────────────────────│  { url: "stripe.com/..." } │
 │                      │                            │
 │  [Redirect to Stripe payment page]                │
 │──────────────────────────────────────────────────►│
 │  [User pays]                                      │
 │◄──────────────────────────────────────────────────│
 │                      │                            │
 │                      │◄─── POST /stripe/webhook (checkout.session.completed)
 │                      │  markPaidFromWebhook(reportId)
 │                      │  PaymentStatus → PAID      │
 │                      │                            │
 │  GET /report/{id}?checkout=success                │
 │─────────────────────►│                            │
 │                      │  syncPaymentStatusIfNeeded()
 │                      │  (verifies via Stripe API if still PENDING)
 │◄─────────────────────│  UNLOCKED full report      │
 │                      │                            │
 │  GET /report/{id}/download                        │
 │─────────────────────►│                            │
 │◄─────────────────────│  PDF bytes                 │
```

---

### How locked previews work

- `ReportPreviewService.buildLockedPreview()` creates a shallow copy of the PremiumReport.
- It preserves: reportId, profession, mode, score, riskLevel, cover KPIs, executive summary, core advice.
- It includes only the **first 3 rows** of the task exposure map.
- It **clears** all premium sections: timeline, skill cards, salary intelligence, adjacent roles, action plans, resources.
- The template (`premium-report.html`) checks `reportLocked` — if true, it renders the preview and shows the payment CTA; if false, it renders the full report.

---

### PDF download protection

- `GET /report/{id}/download` first calls `ReportService.getUnlockedReport()`.
- This method only returns the full report if `paymentStatus == PAID`.
- If the report is locked or not found, the controller returns a 403 or 404 response.

---

## 10. Data Flow

```
index.html
  │  User selects mode, enters profession + details (or uploads CV)
  │
  POST /assess  →  RiskAssessmentForm
  │
  RiskAssessmentService
  │  Validates input, extracts CV text if uploaded
  │  Calls JobAiService.assessJobRisk()
  │  Returns AssessmentProcessingResult
  │
  Flash attributes stored in session:
  {
    score, riskLevel, summary, assessment,
    profession, mode,
    originalDetails  ← preserved for premium report generation
  }
  │
  redirect → GET /result
  │
result.html
  │  Shows score, summary, assessment
  │  "Unlock Full Report" → JavaScript stores checkoutPayload in sessionStorage
  │  { profession, mode, score, riskLevel, originalDetails }
  │
  GET /generating-report
  │  Page loads, then immediately fires AJAX:
  │
  POST /generate-report  (body: GenerateReportRequest from sessionStorage)
  │
  ReportService.generateAndStoreReport()
  │  PremiumReportAiService.generate() → PremiumReport
  │  Persist as ReportRequest → PaymentStatus.PENDING
  │  Returns { reportId }
  │
  Frontend redirects to GET /report/{reportId}
  │
/report/{reportId}  (or /premium-report/{reportId})
  │  ReportService.getReportView()
  │  If PENDING/FAILED + not expired → LOCKED preview
  │  If PAID → full report
  │
  [LOCKED] User clicks "Unlock" → POST /api/report/{id}/checkout-session
  │         Returns Stripe checkout URL → user redirected to Stripe
  │
  [After payment] → GET /report/{id}?checkout=success
  │  syncPaymentStatusIfNeeded() verifies with Stripe
  │  Report unlocked
  │
  User views full report
  │
GET /report/{id}/download
  │  PDF generated from PremiumReport via PdfService
  │  Returned as attachment
```

**Note on `originalDetails`:** This is the role summary text (manual or CV-extracted) that the user provided during the free assessment. It is preserved through the session and included in the `GenerateReportRequest` so that the premium report is generated from the **same input** the user saw assessed, not a re-typed version.

---

## 11. Configuration

### `application.properties` — Key Settings

| Property | Description | Default / Example |
|---|---|---|
| `server.port` | HTTP port | `8081` |
| `spring.ai.openai.api-key` | OpenAI API key | `${OPENAI_API_KEY}` |
| `app.ai.model.premium` | Premium model name | `gpt-5.4` |
| `app.ai.model.mini` | Mini model name | `gpt-5.4-mini` |
| `app.ai.use-dummy` | Bypass AI calls (local/test) | `false` |
| `app.ai.cost.premium.input-per-1m` | Cost estimation (USD/1M tokens) | `2.50` |
| `app.ai.cost.premium.output-per-1m` | Output cost (USD/1M tokens) | `10.00` |
| `app.ai.cost.mini.input-per-1m` | Mini model input cost | `0.40` |
| `app.ai.cost.mini.output-per-1m` | Mini model output cost | `1.60` |
| `stripe.secret-key` | Stripe secret key | `${STRIPE_SECRET_KEY}` |
| `stripe.price-id.profession` | Stripe price for professional | `${STRIPE_PRICE_PROFESSIONAL}` |
| `stripe.price-id.course` | Stripe price for students | `${STRIPE_PRICE_STUDENT}` |
| `stripe.price-id.a-level-undecided` | Stripe price for school students | `${STRIPE_PRICE_A_LEVEL_UNDECIDED}` |
| `stripe.webhook-secret` | Webhook signature secret | `${STRIPE_WEBHOOK_SECRET}` |
| `app.base-url` | Used in Stripe success/cancel URLs | `${APP_BASE_URL}` |
| `app.report.expiry-hours` | Hours until unpaid report is purged | `24` |
| `app.form.role-summary-word-limit.profession` | Word limit for professional | `800` |
| `app.form.role-summary-word-limit.course` | Word limit for university student | `450` |
| `app.form.role-summary-word-limit.a-level-undecided` | Word limit for school student | `350` |
| `spring.datasource.url` | Database URL | H2 file `./data/jobai` |
| `spring.jpa.hibernate.ddl-auto` | Schema strategy | `update` |

### Environment Variables Required at Runtime

| Variable | Purpose |
|---|---|
| `OPENAI_API_KEY` | OpenAI API access |
| `STRIPE_SECRET_KEY` | Stripe server-side API key |
| `STRIPE_WEBHOOK_SECRET` | Validates Stripe webhook signatures |
| `STRIPE_PRICE_PROFESSIONAL` | Stripe price ID for £4.99 product |
| `STRIPE_PRICE_STUDENT` | Stripe price ID for £2.99 product |
| `STRIPE_PRICE_A_LEVEL_UNDECIDED` | Stripe price ID for £0.99 product (optional, falls back to student) |
| `APP_BASE_URL` | Public URL of the app (for Stripe redirect URLs) |
| `DATABASE_URL` | Optional — override H2 with PostgreSQL in production |
| `APP_AI_USE_DUMMY` | Set to `true` to skip AI calls (local/test) |

---

## 12. Templates / Screens

### `index.html` — Home Page / Form

**Purpose:** Entry point. Renders the assessment form.

**What the user sees:** A form asking for their mode (professional / student / school), their job/course/interests name, and an input area for manual text or CV upload (professional only).

**Data it expects:**
- `journeyConfig` — populated by the controller from JourneyConfigRegistry; drives form labels, word limit display, and CV section visibility.
- Any prior `BindingResult` errors displayed inline.

---

### `result.html` — Free Assessment Results

**Purpose:** Shows the AI risk score and free summary after form submission.

**What the user sees:** A score (0–10), risk level badge, one-sentence summary, two-sentence assessment. A call-to-action invites them to unlock the full report.

**Data it expects:**
- `score`, `riskLevel`, `summary`, `assessment` — from flash attributes after `/assess`.
- `profession`, `mode` — for display and for building the `checkoutPayload` stored in `sessionStorage`.
- `originalDetails` — preserved in `sessionStorage` for premium report generation.

---

### `generating-report.html` — Loading / Generation Page

**Purpose:** Shown while the premium report is being generated server-side.

**What the user sees:** A loading animation and message. On page load, JavaScript fires `POST /generate-report` and, on success, redirects to `/report/{reportId}`.

**Data it expects:**
- `checkoutPayload` from `sessionStorage` (set on the result page) — sent as request body to `/generate-report`.

---

### `premium-report.html` — Locked and Unlocked Report View

**Purpose:** The main report page — shows locked preview or full paid content depending on payment status.

**Locked state (user has not paid):**
- Shows cover KPIs (disruption window, adaptability potential), executive summary, and first 3 task rows.
- All other sections are hidden.
- A "Unlock Full Report — Pay £X" button triggers Stripe checkout.

**Unlocked state (PAID):**
- Renders all 8 sections: executive summary, task exposure map with bar visualisation, timeline phases, skill cards, salary comparison table, adjacent role cards, action plans, and resource links.
- A "Download PDF" button is shown.

**Data it expects:**
- `report` (PremiumReport or limited preview)
- `reportId`, `reportLocked` (boolean), `paymentStatus`, `expiresAt`

---

### `premium-report-pdf.html` — PDF Template

**Purpose:** A print-optimised version of the full report, rendered by PdfService using Flying Saucer.

**What the user sees:** Delivered as a PDF download — same content as the unlocked HTML report but formatted for A4 / print layout.

**Data it expects:** Same as `premium-report.html` (full PremiumReport object).

---

### `sample-report.html` — Example Report

**Purpose:** Marketing / preview page showing what a premium report looks like.

**What the user sees:** A fictitious example report to give potential users a sense of the output quality before committing.

---

## 13. Testing Overview

### Current Test Coverage

| Area | Test Class | What is Covered |
|---|---|---|
| Journey type parsing | `JourneyTypeTest` | Mode alias resolution for all 3 journeys; `fromMode()` with valid and invalid inputs |
| Journey config registry | `JourneyConfigRegistryTest` | Config lookup by JourneyType and legacy mode string; correct word limits and flags per journey |
| Risk assessment service | `RiskAssessmentServiceTest` | Form validation; word limit enforcement; CV extraction integration; journey-specific validation messages |
| JobAi service | `JobAiServiceTest` | Prompt construction for each journey; model selection logic; dummy mode behaviour |
| Premium report AI service | `PremiumReportAiServiceTest` | Report generation; JSON parsing of all 8 sections; dummy mode output |
| Controller flow | `RiskAssessorControllerTest` | POST /assess success path; flash attribute preservation; validation error handling |
| Checkout price routing | `CheckoutControllerTest` | Correct price ID returned per journey; fallback for a-level if not configured |
| Template rendering | `ResultTemplateTest`, `PremiumReportTemplateTest`, `GeneratingReportTemplateTest` | Thymeleaf renders without errors; key elements present in output |
| Application context | `JobaiApplicationTests` | Spring context loads cleanly |

### Test Configuration

- Tests use an in-memory H2 database (`src/test/resources/application.properties`).
- Dummy mode is enabled in tests (`app.ai.use-dummy=true`).
- Stripe and OpenAI keys are set to test values to prevent live calls.
- ChatClient beans are mocked where AI behaviour is under test.

---

### Known Gaps

| Gap | Priority |
|---|---|
| No full end-to-end payment integration test (Stripe webhook → report unlock) | High |
| No test for PDF generation output correctness | Medium |
| Limited tests for report lock/unlock state transitions in ReportService | Medium |
| No tests for AI response edge cases (malformed JSON, missing fields) | Medium |
| No tests for document parsing failure paths (corrupt file, empty extraction) | Low |
| No tests for report expiry and purge logic | Low |
| No tests for analytics event content | Low |

---

## 14. Known Design Decisions

### 1. One application, three journeys — not three separate apps

All three user types share the same controllers, services, and report structure. Journey-specific behaviour is injected via `JourneyConfig` and `JourneyConfigRegistry`. This reduces code duplication and makes it easy to add a fourth journey later.

**Trade-off:** A single form handling three different use cases can feel awkward if journeys diverge significantly. This would need reassessment if the school or university journeys grow substantially.

---

### 2. Legacy mode strings kept for compatibility

The database stores `mode` as a string (`"profession"`, `"course"`, `"a_level"`). These strings appear in `ReportRequest` entities, Stripe session metadata, and analytics logs. Internally, the application converts these to `JourneyType` enum values via `JourneyType.fromMode()`.

**Trade-off:** Some internal code still reads legacy strings. A future refactor should migrate the DB column to use enum names.

---

### 3. JourneyType/JourneyConfig for cleaner branching

Before these were introduced, mode comparisons were scattered `if (mode.equals("profession"))` blocks. The registry and config record centralise all journey-specific values, making the code easier to read and extend.

---

### 4. Generate full premium report before payment, then lock it

The premium report is generated and stored in the database before the user pays. The user sees a limited locked preview. After payment, the same stored report is unlocked.

**Why:** Better UX — no generation delay after payment. The risk (generating reports that are never paid for) is mitigated by the 24-hour expiry and purge process.

---

### 5. Use existing PremiumReport structure for all journeys

Rather than building separate report models for professionals, students, and school students, the same `PremiumReport` structure is used. Journey-specific framing is handled at the AI prompt level.

**Why:** Simplicity. One structure, one renderer, one PDF template.

---

### 6. Backend uses `a_level` internally; UI uses global language

The `A_LEVEL_UNDECIDED` journey uses `mode=a_level` for legacy reasons, but the user-facing UI uses language like "school student" or "still exploring options". This makes the product accessible globally without requiring a backend rename.

**Planned resolution:** Eventually rename the internal enum and DB value to `SCHOOL_STUDENT` or `PRE_UNIVERSITY`.

---

## 15. Risks and Future Improvements

### Internal Renames

- [ ] Rename `A_LEVEL_UNDECIDED` → `SCHOOL_STUDENT` or `PRE_UNIVERSITY` throughout the codebase.
- [ ] Migrate `mode` DB column from legacy strings to enum names.
- [ ] Rename `a-level-instructions.txt` → `school-student-instructions.txt`.

### Globalisation

- [ ] Replace A-Level–specific copy in `a-level-instructions.txt` with globally neutral language (e.g., "secondary school subjects", "pre-university choices").
- [ ] Add currency and salary localisation beyond UK.

### Product Features

- [ ] Email capture — let users receive their report by email; reduces risk of losing access.
- [ ] Save / retrieve reports by email address without re-generating.
- [ ] Free usage limits — cap free assessments per visitor without an account.
- [ ] Admin dashboard — view reports generated, payment rates, top professions assessed.
- [ ] Rate limiting — prevent abuse of the free assessment endpoint.

### Technical Improvements

- [ ] Blurred preview optimisation — instead of hiding sections, render the PDF and blur premium pages for a more polished locked preview.
- [ ] Analytics by journey — break down event logs by `JourneyType` for per-journey conversion tracking.
- [ ] Stronger prompt evaluation tests — automated checks on AI output quality (e.g., score range, required JSON fields, tone).
- [ ] Better report expiry handling — notify users before their unpaid report expires.
- [ ] PostgreSQL migration path — document the production DB setup; add a migration framework (Flyway or Liquibase).
- [ ] Store structured original intake separately — currently `originalDetails` is a plain string; consider storing it as structured JSON for richer premium report generation.
- [ ] Improve test coverage for payment webhook and report unlock flows.

---

*Document generated: 2026-05-01*
*Branch: feature/multi-journey-assessment*
*Author: WillAIStealMyJob development team*
