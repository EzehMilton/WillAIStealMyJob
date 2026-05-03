# WillAIStealMyFuture — High-Level Design (HLD)

_Last updated: 2026-05-02 · Branch: feature/multi-journey-assessment_

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
9. [Scoring Architecture](#9-scoring-architecture)
10. [Report Locking and Payment Flow](#10-report-locking-and-payment-flow)
11. [Data Flow](#11-data-flow)
12. [Configuration](#12-configuration)
13. [Templates / Screens](#13-templates--screens)
14. [Testing Overview](#14-testing-overview)
15. [Known Design Decisions](#15-known-design-decisions)
16. [Risks and Future Improvements](#16-risks-and-future-improvements)

---

## 1. Executive Summary

**WillAIStealMyFuture** is a web application that helps people understand how artificial intelligence might affect their career, education, or future options.

It does this by:

1. Asking the user a few questions about their job, course, or school subjects.
2. Calling an AI model to generate a narrative assessment, then applying a deterministic 5-dimension scoring model to produce a reliable risk score.
3. Showing the user a free snapshot: score, risk level, and a 2-sentence assessment.
4. Offering a detailed, paid premium report (unlocked via Stripe payment) that includes timelines, skill recommendations, salary comparisons, career pivots, and actionable plans — with its own independently AI-generated score.

The application targets three types of users:

- **Professionals** who want to know whether their current job is at risk from AI automation.
- **University or course students** who want to know whether their degree or study path will lead to a resilient career.
- **School students and pre-university / undecided individuals** who are exploring subject choices and want to understand AI's effect on their future options.

---

## 2. Product Overview

The application runs as a single Spring Boot web app with **three user journeys** (called "modes" internally). All three journeys share the same form, controllers, services, and report structure. Only the labels, word limits, AI instructions, pricing, and scoring baselines differ.

### Journey 1 — Professional

> "Will AI take my job?"

- User enters their job title and describes their role (manual text or CV upload).
- A 5-dimension scoring model produces the risk score; an AI model generates the narrative.
- Premium report includes: task exposure map, career transition paths, salary intelligence, and a 1-year action plan.
- Price: **£4.99** (or £10.00 per `APP_REPORT_PRICE_PROFESSION_PENCE:1000`)

### Journey 2 — University Student / Course User

> "Is my degree future-proof?"

- User enters their course or degree name and describes their career goals (manual only).
- Same scoring model with lower baselines; AI provides course-specific narrative.
- Premium report includes: career path risk, AI-proof skills, degree ROI, and recommended additional skills.
- Price: **£2.99** (or £3.00 per `APP_REPORT_PRICE_COURSE_PENCE:300`)

### Journey 3 — School Student / Pre-University / Undecided

> "What should I study and where is it heading?"

- User enters their subject interests, strengths, and preferences (manual only).
- Scoring model uses the lowest baselines; AI advises on subject combinations and career clusters.
- Premium report includes: subject combinations, future career clusters, AI-exposure of those paths, and action steps.
- Price: **£0.99**

> **Note on naming:** The backend uses `mode=a_level` and `JourneyType.A_LEVEL_UNDECIDED` internally. The user-facing UI uses global language ("school student", "pre-university", "still exploring options") to serve users worldwide.

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
│   Service Layer   │  │       Configuration & Registry             │
│ RiskAssessment    │  │  JourneyConfigRegistry · AIModelConfig     │
│ JobAiService      │  └───────────────────────────────────────────┘
│ RiskScoringService│
│ RiskDimension     │◄── RiskAdjustmentService
│   Calculator      │◄── RiskSanityValidator
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
│  Spring AI ChatClient → OpenAI (gpt-5.4 / gpt-5.4-mini)          │
│  Free summary: narrative only — score overridden server-side      │
│  Premium report: full JSON including premiumScore                  │
└────────────────────────────────────────────────────────────────────┘
         │
┌────────▼──────────────────────────────────────────────────────────┐
│                    Scoring Layer (deterministic)                   │
│  RiskScoringService                                                │
│    → RiskDimensionCalculator (5 dimensions, keyword rules)        │
│    → RiskAdjustmentService (protective factors)                   │
│    → RiskSanityValidator (thresholds + aligned summary)           │
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
│  PdfService → Thymeleaf → jsoup → Flying Saucer → byte[]         │
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
| Document parsing | Apache Tika 2.9.2 |
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
 │◄─────────────────────│ index.html (journey selector)│               │
 │                      │                              │               │
 │  POST /assess        │                              │               │
 │─────────────────────►│                              │               │
 │                      │──── AI narrative call ──────►│               │
 │                      │◄─── summary + assessment ────│               │
 │                      │  RiskScoringService overrides score          │
 │◄─────────────────────│ redirect → /result           │               │
 │                      │                              │               │
 │  GET /result         │                              │               │
 │─────────────────────►│                              │               │
 │◄─────────────────────│ result.html (score + snapshot)│              │
 │                      │                              │               │
 │  [Click "Unlock Full Report" in modal]               │               │
 │  → sessionStorage.checkoutPayload set                │               │
 │  → navigate to /generating-report                    │               │
 │─────────────────────►│                              │               │
 │◄─────────────────────│ generating-report.html       │               │
 │                      │                              │               │
 │  POST /generate-report (AJAX from page)              │               │
 │─────────────────────►│                              │               │
 │                      │──── AI premium report ──────►│               │
 │                      │◄─── PremiumReport JSON ───────│               │
 │                      │ store in DB (PENDING)         │               │
 │◄─────────────────────│ { reportId: "uuid" }         │               │
 │                      │                              │               │
 │  GET /premium-report/{id}                            │               │
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
 │  [Redirected to Stripe]                              │               │
 │──────────────────────────────────────────────────────────────────────►│
 │◄─────────────────────────────────────────────────────────────────────│
 │                      │                              │               │
 │                      │◄──── POST /stripe/webhook (session.completed) │
 │                      │  markPaid(reportId)          │               │
 │                      │                              │               │
 │  GET /premium-report/{id}?checkout=success           │               │
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
- `DocumentParserService` receives the `MultipartFile`, validates it (size ≤ 2 MB, extension whitelisted, not empty), and uses Apache Tika to extract plain text (minimum 50 chars required).
- The extracted text becomes the `roleSummary` sent to the AI.
- If parsing fails the user is offered the option to type manually.

**How the free snapshot is generated**
1. `JobAiService` calls the **premium model** (gpt-5.4, temp 0.2) with `jobai.txt` + `profession-instructionsV2.txt`.
2. The AI returns `{ score, riskLevel, summary, assessment }` JSON.
3. `RiskScoringService` **overrides** `score`, `riskLevel`, and `summary` with values from the deterministic 5-dimension model.
4. The AI's 2-sentence `assessment` is kept as-is.

**What the premium report includes**
1. Cover KPIs: disruption window and adaptability potential
2. Executive summary (references `premiumScore`) and core advice
3. Task exposure map (each task area scored 0–100%)
4. Timeline (4 phases)
5. Skill cards (priority-ranked)
6. Salary intelligence (traditional vs AI-augmented)
7. Adjacent career paths
8. Action plans (30-day, 90-day, 1-year)
9. Resources (courses, tools, books, communities)

The premium report generates its own `premiumScore` and `premiumRiskLevel` — independent of the free snapshot score.

**Payment and unlock:** £4.99 via Stripe.

---

### B. University Student / Course User

**What they enter**
- Course or degree name (required)
- Career goals or expected outcomes (up to 450 words, manual only)

**Why CV upload is disabled**
- CV upload is only meaningful for people with work history. `JourneyConfig.cvUploadAllowed = false`.

**How the assessment works**
- Uses the **mini model** (gpt-5.4-mini, temp 0.2) with `course-instructionsV2.txt`.
- Instructions emphasise: career pathways, goal alignment, skills longevity, market demand, AI literacy strategy, ROI on the course.
- Scoring uses lower baselines (4.3–4.5 vs 4.7–5.0 for professional).

**Payment and unlock:** £2.99 via Stripe.

---

### C. School Student / Pre-University / Undecided User

**What they enter**
- Interests, strengths, preferred subjects, and what kind of work they find appealing (up to 350 words, manual only).

**Why this is not "A-Level only"**
- The backend mode is `a_level` for legacy reasons, but the UI uses global language.
- The scoring represents "AI future-readiness risk": low = flexible and human-centred paths; high = narrow or automatable directions.

**How the assessment works**
- Uses the **mini model** with `a-level-instructionsV3.txt`.
- Instructions emphasise subject combinations, career clusters, AI-exposure of possible paths, skills to build, practical next steps.
- Uses the lowest scoring baselines (4.0 across all dimensions).

**Payment and unlock:** £0.99 via Stripe.

---

## 6. Important Domain Concepts

### JourneyType

| | |
|---|---|
| **What it is** | Enum: `PROFESSIONAL`, `UNIVERSITY_STUDENT`, `A_LEVEL_UNDECIDED` |
| **Why it exists** | Type-safe journey selector, replaces raw string comparisons throughout |
| **Key detail** | Each value carries a `legacyMode()` string (`"profession"`, `"course"`, `"a_level"`) for backward compatibility with form submissions and stored data |
| **Where used** | JourneyConfigRegistry, RiskDimensionCalculator (baselines), RiskSanityValidator (summary framing), PremiumReportAiService (framing), CheckoutController (price routing) |

---

### JourneyConfig

| | |
|---|---|
| **What it is** | `record` holding all journey-specific UI and prompt metadata |
| **Why it exists** | Eliminates scattered `if (mode.equals(...))` checks — all journey-specific values live in one place |
| **Contents** | journeyType, legacyModeValue, displayName, wordLimit, subjectLabel, detailsLabel, cvUploadAllowed, manualInputAllowed, promptInstructionResource |
| **Where used** | RiskAssessorController (form rendering), RiskAssessmentService (validation messages), JobAiService (prompt selection), CheckoutController (price display) |

---

### JourneyConfigRegistry

| | |
|---|---|
| **What it is** | Spring singleton holding an `EnumMap<JourneyType, JourneyConfig>` |
| **Why it exists** | Central registry — any class can look up journey config without constructing it |
| **Key methods** | `get(JourneyType)`, `get(String legacyMode)` |

---

### RiskDimensions

| | |
|---|---|
| **What it is** | `record` of 5 scored dimensions (each 0–10) |
| **Fields** | `taskRepeatability`, `digitalExecution`, `humanInteraction`, `creativityExecution`, `environmentComplexity` |
| **Why it exists** | Separates the inputs to the scoring model, making weights transparent and testable |
| **Where used** | Calculated by `RiskDimensionCalculator`, consumed by `RiskScoringService` |

---

### RiskScoringResult

| | |
|---|---|
| **What it is** | `record` returned by `RiskScoringService.score()` |
| **Fields** | `score` (final 0–10), `riskLevel`, `summary`, `dimensions` (RiskDimensions), `baseScore` (before protective adjustment), `protectiveAdjustment` |
| **Why it exists** | Bundles the full scoring audit trail — used for overriding AI output and for test assertions |
| **Where used** | Returned from RiskScoringService; applied to JobRiskAssessment in JobAiService |

---

### AssessmentProcessingResult

| | |
|---|---|
| **What it is** | `record` returned by `RiskAssessmentService` after a successful free assessment |
| **Fields** | `assessment` (JobRiskAssessment), `resolvedDetails` (original role summary text), `subject`, `journeyType`, `mode` |
| **Why it exists** | Bundles everything the controller needs and carries `resolvedDetails` so the premium report is generated from the same input |
| **Where used** | Returned from `RiskAssessmentService.processAssessmentWithDetails()`; used to build flash attributes |

---

### JobRiskAssessment

| | |
|---|---|
| **What it is** | Structured output of the free assessment |
| **Fields** | `score` (0–10, set by RiskScoringService), `riskLevel` (set by RiskSanityValidator), `summary` (set by RiskSanityValidator), `assessment` (2-sentence AI narrative, kept as-is), `generationMetrics` |
| **Why it exists** | Strongly-typed container carried through flash attributes to `result.html` |

---

### PremiumReport

| | |
|---|---|
| **What it is** | Full paid report with 8 sections |
| **Key new fields** | `premiumScore` (AI-generated, independent of free score), `premiumRiskLevel`, `scoreRationale` |
| **Score relationship** | `score` / `riskLevel` = copied from free assessment (for display context); `premiumScore` / `premiumRiskLevel` = independently generated by the AI in the premium prompt |
| **Where used** | Generated by PremiumReportAiService; serialised to JSON for DB storage; rendered by ReportController and PdfService |

---

### ReportRequest

| | |
|---|---|
| **What it is** | JPA entity in `generated_reports` table |
| **Fields** | `id` (UUID), `profession`, `mode`, `riskScore`, `riskLevel`, `paymentStatus`, `stripeSessionId`, `expiresAt`, `reportJson` (LOB — serialised PremiumReport) |
| **Why it exists** | Persists the premium report between generation and payment; tracks payment lifecycle |

---

### PaymentStatus

| | |
|---|---|
| **PENDING** | Report generated, Stripe session not yet completed |
| **PAID** | Payment confirmed (webhook or direct sync); report unlocked |
| **FAILED** | Stripe session expired without payment |

---

### GenerationMetrics

| | |
|---|---|
| **What it is** | Tracks AI usage for a single generation call |
| **Fields** | `reportType`, `model`, `durationMs`, `promptTokens`, `completionTokens`, `totalTokens`, `estimatedCostUsd`, `estimatedCostPence` |
| **GBP conversion** | `estimatedCostPence = estimatedCostUsd × usdToGbpRate × 100` (rate configurable via `AI_COST_USD_TO_GBP_RATE`, default 0.79) |
| **Display labels** | `getDurationLabel()` (ms or s), `getEstimatedCostUsdLabel()` ($0.0000), `getEstimatedCostPenceLabel()` (0.000p) |
| **Where used** | Attached to JobRiskAssessment and PremiumReport; logged via AnalyticsService |

---

## 7. Key Classes and Responsibilities

### Controllers

| Class | Responsibility | Key Endpoints |
|---|---|---|
| **RiskAssessorController** | Home page and form submission; validates input; orchestrates free assessment | `GET /`, `POST /assess`, `GET /result`, `GET /generating-report`, `GET /sample-report` |
| **ReportController** | Triggers premium report generation; serves locked/unlocked report; PDF download | `POST /generate-report`, `GET /report/{id}`, `GET /report/{id}/download` |
| **CheckoutController** | Creates Stripe checkout session with journey-specific price ID | `POST /api/report/{id}/checkout-session` |
| **WebhookController** | Receives and verifies Stripe webhook events; marks reports PAID or FAILED | `POST /stripe/webhook` |
| **AnalyticsController** | Receives frontend analytics events | `POST /analytics/event` |

---

### Services

| Class | Responsibility | Key Methods |
|---|---|---|
| **RiskAssessmentService** | Validates form; extracts text from CV or manual input; orchestrates free assessment | `processAssessmentWithDetails()` |
| **JobAiService** | Calls AI for narrative content; then calls RiskScoringService to override score | `assessJobRisk()`, `buildAssessmentPrompt()` |
| **RiskScoringService** | Orchestrates 5-dimension scoring; calls calculator, adjustment, and validator | `score(journeyType, subject, details, modelSummary)` |
| **RiskDimensionCalculator** | Calculates 5 dimension scores using journey baselines + keyword rules + hard caps | `calculate(journeyType, subject, details)` |
| **RiskAdjustmentService** | Calculates protective adjustment for human/physical factors (max 3.5) | `protectiveAdjustment(subject, details)` |
| **RiskSanityValidator** | Maps score to riskLevel; builds aligned summary text; validates no contradictions | `riskLevel(score)`, `alignedSummary(...)`, `contradicts(summary, riskLevel)` |
| **JourneyConfigRegistry** | Provides journey-specific config (labels, limits, prompt file, pricing) | `get(JourneyType)`, `get(String)` |
| **DocumentParserService** | Extracts text from uploaded CV using Apache Tika | `extractText(MultipartFile)` |
| **ReportService** | Generates and persists premium report; manages payment status and expiry | `generateAndStoreReport()`, `getReportView()`, `markPaidFromWebhook()`, `syncPaymentStatusIfNeeded()`, `purgeExpiredUnpaidReports()` |
| **PremiumReportAiService** | Generates full AI premium report with 8 sections including `premiumScore` | `generate(GenerateReportRequest)` |
| **ReportPreviewService** | Builds limited preview (first 3 task rows, clears paid sections) for locked reports | `buildLockedPreview(PremiumReport)` |
| **PdfService** | Renders premium report to PDF via Thymeleaf + Flying Saucer | `generateReportPdf(PremiumReport)` |
| **AnalyticsService** | Logs structured analytics events to a dedicated SLF4J logger | `recordSummaryGenerated()`, `recordPaymentCompleted()`, `recordReportDelivered()`, `record()` |
| **GenerationMetricsService** | Extracts token counts and estimates USD and GBP cost from AI responses | `fromChatResponse()` |

---

### Models

| Class | Type | Purpose |
|---|---|---|
| `JourneyType` | Enum | Type-safe journey selector with legacy mode aliases |
| `JourneyConfig` | Record | All journey-specific metadata |
| `RiskDimensions` | Record | 5 scored dimensions (taskRepeatability, digitalExecution, humanInteraction, creativityExecution, environmentComplexity) |
| `RiskScoringResult` | Record | Full scoring audit trail (score, riskLevel, summary, dimensions, baseScore, protectiveAdjustment) |
| `RiskAssessmentForm` | POJO | Spring MVC form binding model for `POST /assess` |
| `AssessmentProcessingResult` | Record | Bundles AI output, journey config, and original input |
| `JobRiskAssessment` | POJO | Free assessment result — score overridden by RiskScoringService |
| `PremiumReport` | POJO | Full paid report with 8 sections + premiumScore + scoreRationale |
| `GenerateReportRequest` | POJO | Request body for `POST /generate-report` |
| `ReportRequest` | JPA Entity | DB row for report persistence and payment tracking |
| `PaymentStatus` | Enum | PENDING / PAID / FAILED |
| `GenerationMetrics` | POJO | AI usage metrics (tokens, cost USD, cost pence) |

---

## 8. Prompt and AI Design

### AI Models

Three `ChatClient` beans are defined in `AIModelConfiguration`:

| Bean | Model | Temperature | Used for |
|---|---|---|---|
| `gpt54ChatClient` | `gpt-5.4` (premium) | 0.2 | Free assessment — Professional journey only |
| `gpt54MiniChatClient` | `gpt-5.4-mini` (mini) | 0.2 | Free assessment — University and School journeys; Premium report generation |
| `gpt54ReportChatClient` | `gpt-5.4` (premium) | 0.6 | Defined but not currently wired to a service |

The default OpenAI model in `application.properties` is `gpt-5.1` (the Spring AI default client); the named beans above override this per-request.

---

### Free Summary Assessment Flow

```
jobai.txt
  + profession-instructionsV2.txt  (if PROFESSIONAL)
  + course-instructionsV2.txt      (if UNIVERSITY_STUDENT)
  + a-level-instructionsV3.txt     (if A_LEVEL_UNDECIDED)
  + {profession}, {roleSummary}
  ─────────────────────────────────────────────────────────►  OpenAI
                                                              returns JSON
  ◄─────────────────────────────────────────────────────────
  { score: X, riskLevel: "...", summary: "...", assessment: "..." }
        │
        ▼
  RiskScoringService.score(journeyType, profession, roleSummary, aiSummary)
        │
        ▼  OVERRIDES score, riskLevel, summary
  JobRiskAssessment{ score: Y, riskLevel: "...", summary: "...", assessment: (kept from AI) }
```

**Important:** The score the user sees is **not** the AI's score. The AI provides narrative content. The deterministic scoring model sets the final number. See Section 9 for full detail.

---

### Premium Report Prompt Flow

```
premium-report-prompt.txt
  + {reportFraming}      (journey-specific framing block)
  + {sectionEmphasis}    (journey-specific section focus)
  + {journeyInstructions} (detailed journey-specific rules)
  + {reportQualityBooster} (universal quality meta-prompt)
  + {profession}, {score}, {riskLevel}, {roleSummary}, {mode}
  ──────────────────────────────────────────────────────────────►  OpenAI
                                                                   returns JSON
  ◄──────────────────────────────────────────────────────────────
  PremiumReport JSON (premiumScore, premiumRiskLevel, scoreRationale, 8 sections)
```

The premium report prompt **does** ask the AI to output a `premiumScore` and `premiumRiskLevel` — these are validated against scoring thresholds. The `score` field in the prompt input (`{score}`) is the free assessment score passed as context; the AI can confirm, adjust, or justify a different score in `premiumScore`.

---

### All Prompt Files

| File | Version | Used by | Purpose |
|---|---|---|---|
| `jobai.txt` | Current | JobAiService | Main free assessment prompt — all journeys |
| `profession-instructions.txt` | V1 (superseded) | — | Original professional instructions |
| `profession-instructionsV2.txt` | V2 (active) | JobAiService | Professional journey assessment instructions |
| `course-instructions.txt` | V1 (superseded) | — | Original course instructions |
| `course-instructionsV2.txt` | V2 (active) | JobAiService | University student journey instructions |
| `a-level-instructions.txt` | V1 (superseded) | — | Original school student instructions |
| `a-level-instructionsV2.txt` | V2 (superseded) | — | Interim school student instructions |
| `a-level-instructionsV3.txt` | V3 (active) | JobAiService | Current school/pre-university instructions |
| `premium-report-prompt.txt` | Current | PremiumReportAiService | Full premium report — all journeys |
| `report-quality-booster.txt` | Current | PremiumReportAiService | Universal quality meta-prompt injected into premium prompt |

The V1 files remain on disk but are not referenced by any service. `JourneyConfigRegistry` wires each journey type to the active version via `promptInstructionResource`.

---

### Key Prompt Rules

**`jobai.txt`** constraints:
- Output JSON only — no markdown.
- Do not include numeric scores in narrative text.
- Do not say any role is "safe from AI".
- Include one "open loop" sentence to tease premium content.
- Tone: cautious but not alarmist.

**V2/V3 instruction files** each require:
- A clear judgement (don't sit on the fence).
- An identity/direction statement (who this assessment is for).
- A trade-off statement (what this role/course does not offer).
- A value-shift statement (what becomes more/less valuable).
- Scoring must explain at least 2 contributing factors.

**`premium-report-prompt.txt`** requires:
- `premiumScore` validated against thresholds (Low ≤3.4, Moderate 3.5–6.9, High ≥7.0).
- `scoreRationale` explaining why this score was chosen.
- All 8 sections present with specific item counts (6 skill cards, 4 timeline events, 3 adjacent roles, etc.).
- Salary figures in UK market context.

---

### JSON Parsing

Both `JobAiService` and `PremiumReportAiService` use the same `cleanJsonResponse()` pattern: strips markdown fences, extracts the outermost `{...}` object, then parses with Jackson `ObjectMapper`. Parsing failures throw a `RuntimeException` that surfaces as a 500 to the user.

---

## 9. Scoring Architecture

This is the most significant architectural feature of the application. Scoring is **deterministic and rule-based**, not purely AI-generated.

### Why

AI models are prone to moderate-range clustering (consistently returning 5–6/10 for diverse inputs). A rule-based scoring model ensures scores reflect observable characteristics of the input rather than model calibration bias.

### How the Free Summary Score is Calculated

```
Input: JourneyType + subject + details
         │
         ▼
RiskDimensionCalculator.calculate()
  For each dimension:
    1. Start from journey-type baseline (Professional: 4.7–5.0, University: 4.2–4.5, School: 4.0)
    2. Apply keyword adjustments (additive, capped at 2× per rule)
    3. Apply role-specific hard caps (singers, nurses, data entry clerks, etc.)
  Returns: RiskDimensions{ taskRepeatability, digitalExecution, humanInteraction,
                            creativityExecution, environmentComplexity }
         │
         ▼
RiskScoringService.weightedBaseScore()
  Score = (taskRepeatability × 0.30)
        + (digitalExecution  × 0.25)
        + (humanInteraction  × 0.20)
        + (creativityExecution × 0.15)
        + (environmentComplexity × 0.10)
         │
         ▼
RiskAdjustmentService.protectiveAdjustment()
  Applies a downward adjustment (max 3.5) for protective factors:
    + 1.1 for live performance, performer, singer, choir
    + 0.9 for physical presence, hands-on, site work, patient care
    + 0.8 for real-time coordination, stakeholder leadership
    + 0.9 for emotional intelligence, empathy, nursing, counselling
    + 0.8 for unpredictable environments, emergency, home visits
  Hard caps: call center max 0.6, nurse max 1.0
         │
         ▼
finalScore = clamp(baseScore − protectiveAdjustment, 0.0, 10.0)
         │
         ▼
RiskSanityValidator.riskLevel()
  ≤ 3.4 → "Low"
  3.5 – 6.9 → "Moderate"
  ≥ 7.0 → "High"
         │
         ▼
RiskSanityValidator.alignedSummary()
  Generates a 2-sentence summary that:
  - Names the subject
  - States the impact level ("low/moderate/high AI impact")
  - Explains the key driver dimension
  - Notes protective factors if significant (≥ 0.8)
  - Teases the premium report (journey-specific)
  Never exposes the numeric score or % in the text
```

### Dimension Weights

| Dimension | Weight | Rationale |
|---|---|---|
| Task Repeatability | 30% | Most predictive of automation potential |
| Digital Execution | 25% | Work in digital systems is directly accessible to AI |
| Human Interaction | 20% | Depth of interpersonal trust and emotional context |
| Creativity Execution | 15% | Original judgement vs execution of known patterns |
| Environment Complexity | 10% | Structured vs unpredictable real-world settings |

### Role Hard Caps

| Role category | Effect |
|---|---|
| Choir singer / vocalist | All dimensions capped at very low values |
| Nurse / nursing | Dimensions clamped to narrow low-moderate ranges |
| Electrician | taskRepeatability ≤ 3.4, digitalExecution ≤ 1.8 |
| CEO / chief executive | taskRepeatability ≤ 2.6, humanInteraction ≤ 2.2 |
| Data entry clerk | All dimensions floored to 8.4–9.2 |
| Call center agent | All dimensions floored to 7.2–8.4 |
| Software developer | digitalExecution floored at 8.8; others range-clamped |
| Graphic designer | digitalExecution floored at 8.0; creativity range-clamped |

### Premium Report Score

The premium report generates its own score independently:

- `premiumScore` is output by the AI in the premium prompt response.
- `premiumRiskLevel` must align to `premiumScore` per the prompt thresholds.
- `scoreRationale` explains why the AI chose this score.
- The free assessment `score` is passed into the premium prompt as context (`{score}/10`), but the AI is free to adjust it based on deeper analysis.

---

## 10. Report Locking and Payment Flow

### Why Reports Are Generated Before Payment

The full premium report is generated **before** the user pays. This is intentional:

1. The user sees a limited locked preview immediately — building trust and demonstrating value.
2. No generation delay after payment (better UX).
3. No risk of generation failure after money has been taken.

The trade-off is that some reports are generated but never paid for. These are purged after 24 hours.

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
 │  GET /premium-report/{id}                         │
 │─────────────────────►│                            │
 │◄─────────────────────│  LOCKED preview (3 tasks visible)
 │                      │                            │
 │  POST /api/report/{id}/checkout-session           │
 │─────────────────────►│                            │
 │                      │──── Session.create() ─────►│
 │                      │◄─── { url, sessionId } ────│
 │                      │  attachStripeSession(reportId, sessionId)
 │◄─────────────────────│  { url: "stripe.com/..." } │
 │                      │                            │
 │  [User pays on Stripe]                            │
 │──────────────────────────────────────────────────►│
 │◄──────────────────────────────────────────────────│
 │                      │                            │
 │                      │◄─── POST /stripe/webhook (checkout.session.completed)
 │                      │  markPaidFromWebhook(reportId) → PAID
 │                      │                            │
 │  GET /premium-report/{id}?checkout=success        │
 │─────────────────────►│                            │
 │                      │  syncPaymentStatusIfNeeded()
 │                      │  (verifies with Stripe API if still PENDING)
 │◄─────────────────────│  UNLOCKED full report      │
 │                      │                            │
 │  GET /report/{id}/download                        │
 │─────────────────────►│                            │
 │◄─────────────────────│  PDF bytes                 │
```

### Locked Preview

`ReportPreviewService.buildLockedPreview()` copies the full report but:
- Retains: reportId, profession, mode, `score`, `riskLevel`, `premiumScore`, `premiumRiskLevel`, `scoreRationale`, cover KPIs, executive summary, core advice.
- Limits `taskExposureMap` to the first 3 rows.
- Clears: timeline, skill cards, salary, adjacent roles, action plans, resources.

### PDF Download Protection

`GET /report/{id}/download` calls `ReportService.getUnlockedReport()`, which only returns the full report if `paymentStatus == PAID`. Locked or expired reports receive a 403 response.

### Report Expiry

- Unpaid reports expire after 24 hours (configurable via `APP_REPORT_EXPIRY_HOURS`).
- Paid reports never expire.
- `purgeExpiredUnpaidReports()` is called before report generation and before report viewing.

---

## 11. Data Flow

```
index.html
  │  User selects journey, enters profession + details (or uploads CV)
  │
  POST /assess  →  RiskAssessmentForm
  │
  RiskAssessmentService.processAssessmentWithDetails()
  │  Validates, extracts CV text if uploaded
  │  Calls JobAiService.assessJobRisk()
  │    → AI returns narrative JSON
  │    → RiskScoringService overrides score/riskLevel/summary
  │  Returns AssessmentProcessingResult
  │
  Flash attributes (one-time, consumed on next GET):
  { score, riskLevel, summary, assessment, profession, mode,
    originalDetails (the role text the user gave) }
  │
  redirect → GET /result
  │
result.html
  │  Displays: score * 10 as % on slider + impact signal
  │  Assessment narrative shown
  │  "Unlock" modal: user clicks "Generate Report Preview"
  │
  JavaScript stores in sessionStorage.checkoutPayload:
  { profession, mode, score, riskLevel, summary, assessment, originalDetails }
  │
  window.location → GET /generating-report
  │
generating-report.html
  │  Page loads → JS immediately fires:
  │
  POST /generate-report  (body: GenerateReportRequest from sessionStorage)
  {
    profession, mode, score, riskLevel,
    description: stored.originalDetails || stored.assessment || stored.summary
  }
  │
  ReportService.generateAndStoreReport()
  │  PremiumReportAiService.generate() → PremiumReport (inc. premiumScore)
  │  Persist as ReportRequest → PaymentStatus.PENDING
  │  Returns { reportId }
  │
  JS redirects to → GET /premium-report/{reportId}
  │
/premium-report/{reportId}
  │  ReportService.getReportView()
  │  If PENDING + not expired → LOCKED (ReportPreviewService.buildLockedPreview())
  │  If PAID → full report
  │
  [LOCKED] → POST /api/report/{id}/checkout-session → Stripe URL
  │
  [After Stripe payment] → GET /premium-report/{id}?checkout=success
  │  syncPaymentStatusIfNeeded() → PAID
  │
  User views full report (premiumScore displayed prominently)
  │
GET /report/{id}/download
  │  PdfService.generateReportPdf(report) → PDF bytes
```

**Note on `originalDetails`:** This is the raw role summary text from the user. It is preserved through flash attributes and `sessionStorage` so the premium AI gets the same input that was used in the free assessment, not a re-typed or reconstructed version.

**Note on description fallback:** `generating-report.html` sends: `stored.originalDetails || stored.details || stored.assessment || stored.summary`. If `originalDetails` is missing (e.g., stale sessionStorage from before this field was added), it falls back to the assessment or summary text — much shorter and less context-rich.

---

## 12. Configuration

### `application.properties` — Key Settings

| Property | Default | Description |
|---|---|---|
| `server.port` | `8081` (env: PORT) | HTTP port |
| `spring.ai.openai.api-key` | `${OPENAI_API_KEY}` | OpenAI API access |
| `spring.ai.openai.chat.options.model` | `gpt-5.1` (env: OPENAI_MODEL) | Default Spring AI model |
| `app.ai.model.premium` | `gpt-5.4` (env: AI_MODEL_PREMIUM) | Premium bean model name |
| `app.ai.model.mini` | `gpt-5.4-mini` (env: AI_MODEL_MINI) | Mini bean model name |
| `app.ai.cost.premium.input-per-1m` | `2.50` | USD per 1M input tokens (premium) |
| `app.ai.cost.premium.output-per-1m` | `10.00` | USD per 1M output tokens (premium) |
| `app.ai.cost.mini.input-per-1m` | `0.40` | USD per 1M input tokens (mini) |
| `app.ai.cost.mini.output-per-1m` | `1.60` | USD per 1M output tokens (mini) |
| `app.ai.cost.usd-to-gbp-rate` | `0.79` | For GBP pence cost estimation |
| `stripe.secret-key` | `${STRIPE_SECRET_KEY}` | Stripe server-side key |
| `stripe.price-id.profession` | `${STRIPE_PRICE_PROFESSIONAL}` | Stripe price ID, £4.99 product |
| `stripe.price-id.course` | `${STRIPE_PRICE_STUDENT}` | Stripe price ID, £2.99 product |
| `stripe.price-id.a-level-undecided` | `${STRIPE_PRICE_A_LEVEL_UNDECIDED:}` | Stripe price ID, £0.99 product (optional, falls back to course price) |
| `stripe.webhook-secret` | `${STRIPE_WEBHOOK_SECRET}` | Webhook signature verification |
| `app.base-url` | `${APP_BASE_URL}` | Used in Stripe success/cancel redirect URLs |
| `app.report.price.profession-pence` | `1000` | Price in pence (£10.00) |
| `app.report.price.course-pence` | `300` | Price in pence (£3.00) |
| `app.report.expiry-hours` | `24` | Hours until unpaid report is purged |
| `app.form.role-summary-word-limit.profession` | `800` | Word limit for professional journey |
| `app.form.role-summary-word-limit.course` | `450` | Word limit for university journey |
| `spring.datasource.url` | H2 file `./data/jobai` | Swap to PostgreSQL via DATABASE_URL |
| `spring.ai.retry.max-attempts` | `5` | Max retries for AI calls |
| `spring.ai.retry.backoff.initial-interval` | `1s` | Initial retry backoff |
| `spring.servlet.multipart.max-file-size` | `2MB` | CV upload limit |

### Required Environment Variables

| Variable | Purpose |
|---|---|
| `OPENAI_API_KEY` | OpenAI API access |
| `STRIPE_SECRET_KEY` | Stripe server-side key |
| `STRIPE_PUBLISHABLE_KEY` | Stripe client-side key |
| `STRIPE_WEBHOOK_SECRET` | Validates Stripe webhook signatures |
| `STRIPE_PRICE_PROFESSIONAL` | Stripe price ID for professional journey |
| `STRIPE_PRICE_STUDENT` | Stripe price ID for university journey |
| `STRIPE_PRICE_A_LEVEL_UNDECIDED` | Stripe price ID for school journey (optional) |
| `APP_BASE_URL` | Public URL of the app |
| `DATABASE_URL` | Optional — override H2 with PostgreSQL |

---

## 13. Templates / Screens

### `index.html` — Home Page / Form

**Purpose:** Entry point. Renders the assessment form.

**What the user sees:**
- Hero: "Is AI Coming for Your Job or Your Future?"
- Journey selector: 3 radio cards — "I am working", "I am studying", "I am choosing subjects".
- Form fields: profession/course/subjects name, input method toggle (manual / CV upload), text area with live word counter, CV file drag-drop.
- CV upload only available for the Professional journey.
- Word counter dynamically shows journey-specific limit (800/450/350).
- Preview card explaining what the free and premium reports include.

**Data it expects:** `riskAssessmentForm` (for form binding), `wordLimitProfession`, `wordLimitCourse`.

---

### `result.html` — Free Snapshot Results

**Purpose:** Shows the deterministic risk score and AI narrative after form submission.

**What the user sees:**
- Large impact signal with colour-coded risk level (Low=green, Moderate=amber, High=red).
- Risk scale slider (0–10) with position indicator.
- Snapshot insight box (2–3 sentences from the aligned summary).
- Assessment text (AI narrative, 2 sentences).
- Call-to-action: "Unlock your full AI Future Strategy Report" — opens premium modal.
- Modal previews premium features (journey-specific list) and a price badge.

**Data it expects:** `score`, `riskLevel`, `summary`, `assessment`, `profession`, `mode`, `originalDetails` — all from flash attributes after `POST /assess`.

**Key JS:** On modal "Generate" click, saves `checkoutPayload` to `sessionStorage` and navigates to `/generating-report`.

---

### `generating-report.html` — Loading / Generation Page

**Purpose:** Shown while the premium report is being generated. Fires the API call automatically.

**What the user sees:** Spinner, 5-step progress animation with journey-specific copy, "Preparing your report" final step.

**Data it expects:** `sessionStorage.checkoutPayload` (set in `result.html`). Reads `stored.mode` to apply journey-specific step labels.

**Key JS:** On load, fires `POST /generate-report` with data from sessionStorage. On success, redirects to `/premium-report/{reportId}`. On error (after animation), shows an error state.

---

### `premium-report.html` — Locked and Unlocked Report View

**Purpose:** The main report page — shows locked preview or full paid content.

**Locked state:** Shows cover KPIs, executive summary, first 3 task rows. All premium sections are blurred/hidden. "Unlock" button triggers Stripe checkout.

**Unlocked state:** Full 8-section report — executive summary, task exposure map, timeline, skill cards, salary intelligence, adjacent roles, action plans, resources. Download PDF button enabled.

**Score display:** The cover stat box shows `report.score * 10` % (free assessment score). Where `premiumScore` is present, the premium score section shows it with `scoreRationale`.

**Data it expects:** `report` (PremiumReport or limited preview), `reportId`, `reportLocked`, `paymentStatus`, `expiresAt`, `checkoutState`.

---

### `premium-report-pdf.html` — PDF Template

**Purpose:** Print-optimised version of the full report, rendered by `PdfService` via Flying Saucer.

**Notes:** Inline CSS only (no external stylesheets — required for Flying Saucer compatibility). Same content as the unlocked HTML report but laid out for A4.

---

### `sample-report.html` — Example Report

**Purpose:** Static marketing/demo page showing what a premium report looks like. Does not require authentication or assessment.

---

## 14. Testing Overview

### Test Files (19 total)

| Test Class | What it Covers |
|---|---|
| `JobaiApplicationTests` | Spring context loads cleanly |
| `NoRealAiSafetyTest` | Guards against accidentally calling live AI in tests |
| `JourneyTypeTest` | `fromMode()` parsing for all 3 journeys; invalid mode handling |
| `JourneyConfigRegistryTest` | Config lookup by JourneyType and legacy mode string; word limits and flags |
| `RiskScoringServiceTest` | Risk level thresholds (3.4/3.5/6.9/7.0), sample role expected levels, protective factor reduction, summary alignment |
| `RiskDimensionCalculatorTest` | Keyword adjustments, baseline values per journey, role hard caps |
| `RiskScoringBenchmarkTest` | Score distribution across many role/journey combinations |
| `RiskAssessmentServiceTest` | Form validation, CV extraction, manual input, resolvedDetails preservation |
| `JobAiServiceTest` | Prompt construction per journey, correct instruction file injected |
| `JobAiServiceMockedChatClientTest` | Mocked ChatClient; verifies RiskScoringService is called and overrides AI score |
| `PremiumReportAiServiceTest` | Prompt framing per journey, journey-specific instructions injected |
| `PremiumReportAiServiceMockedChatClientTest` | Mocked ChatClient; verifies JSON mapping to PremiumReport including premiumScore |
| `ReportServiceTest` | Report persistence, payment status transitions, expiry logic |
| `ReportPreviewServiceTest` | Locked preview contains only allowed fields; premium sections cleared |
| `CheckoutControllerTest` | Price ID routing per journey; fallback for unconfigured a-level price |
| `RiskAssessorControllerTest` | `POST /assess` success path; flash attributes preserved; validation error handling |
| `ResultTemplateTest` | Thymeleaf renders result.html without errors; key elements present |
| `PremiumReportTemplateTest` | Thymeleaf renders premium-report.html for locked and unlocked states |
| `GeneratingReportTemplateTest` | Thymeleaf renders generating-report.html correctly |

### Key Test Assertions

- Risk level thresholds: `riskLevel(3.4) == "Low"`, `riskLevel(3.5) == "Moderate"`, `riskLevel(7.0) == "High"`.
- Sample roles: singers/electricians/CEOs → Low; developers/designers → Moderate; data entry/call center → High.
- Protective factors: singer's `protectiveAdjustment >= 2.0` and `finalScore < baseScore`.
- Summary alignment: no numeric score in summary, correct impact phrase for riskLevel.
- Flash attributes: `originalDetails` preserved after `POST /assess`.
- Locked preview: `taskExposureMap.size() == 3`, no `timelineEvents`, no `skillCards`.

### Known Gaps

| Gap | Risk |
|---|---|
| No test for `description` fallback chain (`originalDetails || assessment || summary`) | Silent context degradation in premium report |
| No full end-to-end payment integration test (webhook → unlock) | Payment flow tested manually only |
| No test for PDF generation correctness | PDF layout regressions undetected |
| No test for AI score value when `cleanJsonResponse` fails | RuntimeException path untested |
| No test for stale sessionStorage across browser sessions | User sees wrong context for report generation |
| `gpt54ReportChatClient` bean is defined but not wired to any service | Unused infrastructure |

---

## 15. Known Design Decisions

### 1. Deterministic scoring model, not AI-determined score

The final risk score is produced by a rule-based 5-dimension model (`RiskScoringService`), not the AI. The AI generates narrative content (assessment text), which is kept; the AI's numeric score is discarded and replaced.

**Reason:** AI models cluster around moderate values regardless of input. A rule-based model with role-specific hard caps produces more discriminating and predictable scores.

**Trade-off:** The scoring rules require maintenance as new roles emerge. The AI prompt still asks for a score (for its own narrative coherence), but that score is thrown away.

---

### 2. Premium report has its own independent score

`PremiumReport.premiumScore` and `premiumRiskLevel` are output by the AI in the premium prompt. They are not copies of the free assessment score.

**Reason:** The premium AI has more context (full original details) and can perform deeper analysis. It may assign a different score with explicit reasoning in `scoreRationale`.

**Trade-off:** The user may see different scores between the free snapshot and the premium report. This is expected and documented in the report copy.

---

### 3. One application with three journeys

All three user types share the same controllers, services, and report structure. Journey-specific behaviour is injected via `JourneyConfig` and prompt files.

**Trade-off:** A single form handling three use cases can feel awkward if journeys diverge significantly.

---

### 4. Legacy mode strings kept for compatibility

The database stores `mode` as `"profession"`, `"course"`, or `"a_level"`. `JourneyType.fromMode()` converts between strings and enums.

**Planned:** Eventually rename the DB column and enum value.

---

### 5. Generate premium report before payment, then lock it

Full report generated and stored PENDING. User pays to unlock. Unpaid reports purged after 24 hours.

---

### 6. Backend uses `a_level` internally; UI uses global language

User-facing copy uses "school student" / "pre-university". The backend enum and DB value remain `a_level` / `A_LEVEL_UNDECIDED` for now.

---

### 7. V1 prompt files retained on disk

The original `profession-instructions.txt`, `course-instructions.txt`, and `a-level-instructions.txt` are not deleted. They are superseded by V2/V3 equivalents and not referenced by any service. They can be removed safely.

---

## 16. Risks and Future Improvements

### Internal Renames

- [ ] Rename `A_LEVEL_UNDECIDED` → `SCHOOL_STUDENT` or `PRE_UNIVERSITY` throughout.
- [ ] Migrate `mode` DB column from legacy strings to enum names.
- [ ] Delete V1 prompt files (`profession-instructions.txt`, `course-instructions.txt`, `a-level-instructions.txt`, `a-level-instructionsV2.txt`).
- [ ] Wire `gpt54ReportChatClient` (premium, temp 0.6) to `PremiumReportAiService` if higher narrative quality is needed, or remove the bean.

### Scoring Model

- [ ] Add a word-limit for the `a_level` journey in `application.properties` (currently not present; only profession and course limits are defined).
- [ ] Add keyword rules for more roles as the app scales.
- [ ] Consider exposing dimension scores to users as part of the premium report (currently computed but not rendered).

### Product Features

- [ ] Email capture — send the report to the user's email; reduce risk of losing access.
- [ ] Rate limiting — prevent abuse of the free assessment endpoint.
- [ ] Admin dashboard — view reports generated, payment rates, top professions assessed.
- [ ] Analytics by journey — break down conversion rates and scores per journey type.
- [ ] Free usage limits — cap free assessments per visitor.

### Technical Improvements

- [ ] Add `@Valid` constraints to `GenerateReportRequest` — currently no server-side validation of `score`, `riskLevel`, or `mode` from the client payload.
- [ ] Guard against missing `originalDetails` in `generating-report.html` fallback chain.
- [ ] PostgreSQL migration path — document production DB setup; add Flyway or Liquibase.
- [ ] Improve test coverage: end-to-end payment webhook, PDF generation, description fallback.
- [ ] Add GBP pricing display in the UI (currently prices are in properties but displayed as hardcoded strings in templates).
- [ ] Store `premiumScore` separately in `ReportRequest` entity for analytics queries (currently only the free `riskScore` is stored as a column; `premiumScore` is only inside the JSON blob).

---

*Document updated: 2026-05-02*  
*Branch: feature/multi-journey-assessment*
