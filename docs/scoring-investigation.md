# Scoring Investigation — WillAIStealMyFuture

**Updated:** 2026-05-02  
**Branch:** feature/multi-journey-assessment  
**Status:** Investigation only — no production code changed

> This document supersedes the original 2026-05-01 version. The scoring architecture changed significantly: the AI no longer determines the headline risk score. A deterministic rule-based model replaced it. All findings, risks, and recommendations have been updated accordingly.

---

## Table of Contents

1. [Scoring Architecture Overview](#1-scoring-architecture-overview)
2. [How Many AI Calls Are Made?](#2-how-many-ai-calls-are-made)
3. [Full Score Data Flow Diagram](#3-full-score-data-flow-diagram)
4. [Free Summary Score Generation](#4-free-summary-score-generation)
5. [Premium Report Score](#5-premium-report-score)
6. [Same Browser Session — Multiple Journeys](#6-same-browser-session--multiple-journeys)
7. [Mode-Specific Scoring](#7-mode-specific-scoring)
8. [Likely Causes of Similar Scores](#8-likely-causes-of-similar-scores)
9. [Risks](#9-risks)
10. [Test Coverage](#10-test-coverage)
11. [Recommended Fixes](#11-recommended-fixes)
12. [Suggested Next Steps](#12-suggested-next-steps)

---

## 1. Scoring Architecture Overview

The application uses a **two-score model**:

1. **Free summary score** — produced by a deterministic rule-based engine (`RiskScoringService`), not the AI. The AI generates narrative text; the score and risk level are computed server-side and override whatever the AI returns.

2. **Premium score** — produced independently by the AI inside the premium report prompt. It outputs `premiumScore`, `premiumRiskLevel`, and `scoreRationale` as part of the full report JSON. This is separate from the free summary score.

```
FREE ASSESSMENT                          PREMIUM REPORT
────────────────────                     ──────────────────────────────────────
POST /assess                             POST /generate-report
  → JobAiService                           → PremiumReportAiService
      → AI call 1 (narrative only)             → AI call 2 (full report)
          AI returns { score, riskLevel,            AI returns { premiumScore,
            summary, assessment }                     premiumRiskLevel,
          ↓                                           scoreRationale, ... }
      → RiskScoringService OVERRIDES              ↓
          score, riskLevel, summary           report.premiumScore = AI output
          AI's assessment text kept              (independent of free score)
                ↓                                             ↓
     result.html shows score from          premium-report.html shows
     RiskScoringService (not AI)           premiumScore from AI
```

**Key facts:**
- The free summary score the user sees is **never the AI's score**. The AI's numeric score is discarded.
- The AI's 2-sentence `assessment` narrative is the only AI output kept unchanged from Call 1.
- The premium report's headline score (`premiumScore`) is genuinely AI-generated and may differ from the free summary score.
- There is no dummy mode active for `JobAiService` (it was removed). `ReportService` retains a `app.ai.use-dummy` fallback defaulting to `false`.

---

## 2. How Many AI Calls Are Made?

**Two calls total — one per report.**

### Call 1 — Free summary (`POST /assess`)

**Class:** `JobAiService.assessJobRisk()`  
**Model:** gpt-5.4 (Professional) or gpt-5.4-mini (University/School)  
**Temperature:** 0.2  
**Prompt:** `jobai.txt` + journey-specific instructions (V2/V3)

The AI receives:
- The user's profession/subject/interests
- Their role description (manual text or CV-extracted)

The AI returns `{ score, riskLevel, summary, assessment }`.

After parsing, `RiskScoringService.score()` is called immediately and **replaces** `score`, `riskLevel`, and `summary`. Only `assessment` (the 2-sentence narrative) survives from the AI.

---

### Call 2 — Premium report (`POST /generate-report`)

**Class:** `PremiumReportAiService.generate()`  
**Model:** gpt-5.4-mini  
**Temperature:** 0.2  
**Prompt:** `premium-report-prompt.txt` + journey framing + section emphasis + journey instructions + `report-quality-booster.txt`

The AI receives:
- The same profession and role description (from `sessionStorage.checkoutPayload.originalDetails`)
- The free summary score and riskLevel as **context** (`Risk Score: {score}/10`)

The AI returns a large JSON object with all 8 report sections plus its own `premiumScore`, `premiumRiskLevel`, and `scoreRationale`. This score is kept as-is — it is not overridden.

---

### Summary

| | Call 1 (Free Summary) | Call 2 (Premium Report) |
|---|---|---|
| Triggered by | `POST /assess` | `POST /generate-report` |
| Input | profession + role description | same + free score as context |
| AI output used | `assessment` text only | all fields including `premiumScore` |
| Score source | `RiskScoringService` (overrides AI) | AI output directly |
| Model | gpt-5.4 / gpt-5.4-mini | gpt-5.4-mini |

---

## 3. Full Score Data Flow Diagram

```
[1] User submits POST /assess
         │
         ▼
[2] RiskAssessmentService.processAssessmentWithDetails()
    Validates form → extracts roleSummary (manual or CV)
    Calls: JobAiService.assessJobRisk(mode, profession, roleSummary)
         │
         ▼
[3] JobAiService — AI CALL 1
    selectedChatClient.prompt(prompt).call()
    AI returns JSON:
      { score: X, riskLevel: "...", summary: "...", assessment: "..." }
         │
         ▼ ← AI score/riskLevel/summary DISCARDED here
[4] RiskScoringService.score(journeyType, profession, roleSummary, aiSummary)
    → RiskDimensionCalculator.calculate() → RiskDimensions (5 scores, 0–10 each)
    → weightedBaseScore = (taskRepeatability × 0.30) + (digitalExecution × 0.25)
                        + (humanInteraction × 0.20) + (creativityExecution × 0.15)
                        + (environmentComplexity × 0.10)
    → RiskAdjustmentService.protectiveAdjustment() → subtract up to 3.5
    → finalScore = clamp(baseScore − protective, 0.0, 10.0)
    → RiskSanityValidator.riskLevel(finalScore): ≤3.4=Low, 3.5–6.9=Moderate, ≥7.0=High
    → RiskSanityValidator.alignedSummary(...)
    Returns: RiskScoringResult{ score, riskLevel, summary, dimensions, baseScore, protectiveAdjustment }
         │
         ▼
[5] assessment.setScore(scoringResult.score())        ← DETERMINISTIC value
    assessment.setRiskLevel(scoringResult.riskLevel()) ← DETERMINISTIC value
    assessment.setSummary(scoringResult.summary())     ← DETERMINISTIC value
    assessment.setAssessment(...)                      ← kept from AI (unchanged)
         │
         ▼
[6] AssessmentProcessingResult{
      assessment (score=X from RiskScoringService),
      resolvedDetails (original role text),
      profession, journeyType, mode
    }
         │
         ▼
[7] RiskAssessorController.addSuccessAttributes()
    Flash attributes stored (one-time, consumed on next GET):
      score           = X    ← from RiskScoringService
      riskLevel       = "..."← from RiskSanityValidator
      summary         = "..."← from RiskSanityValidator
      assessment      = "..."← from AI (unchanged)
      profession      = "..."
      mode            = "profession"
      originalDetails = "...(user's actual role text)..."
         │
         ▼ redirect
[8] GET /result → result.html
    Displays: score * 10 as %  (e.g., 5.8 → 58%)
    Score is from RiskScoringService, not the AI.
         │
         ▼ user clicks modal button "Generate Report Preview"
[9] result.html JavaScript saves to sessionStorage.checkoutPayload:
    {
      mode:            "profession",
      profession:      "Software Engineer",
      score:           X,               ← deterministic score, NOT AI score
      riskLevel:       "...",
      summary:         "...",
      assessment:      "...",
      originalDetails: "...(role text)..."
    }
    window.location.href = '/generating-report'
         │
         ▼
[10] GET /generating-report → generating-report.html
     On load, JS reads sessionStorage.checkoutPayload and fires:
     POST /generate-report with body:
     {
       profession:  stored.profession,
       description: stored.originalDetails || stored.assessment || stored.summary || '',
       score:       stored.score,       ← deterministic score passed as context
       riskLevel:   stored.riskLevel,
       mode:        stored.mode
     }
         │
         ▼
[11] ReportController.generateReport()
     → ReportService.generateAndStoreReport(request)
     → PremiumReportAiService.generate(request) — AI CALL 2
       Prompt includes: "{score}/10" as context for the AI
       AI returns full JSON including:
         premiumScore     ← AI's own independent score
         premiumRiskLevel ← must align to premiumScore
         scoreRationale   ← AI's explanation
         + all 8 report sections
       mapToReport() sets:
         report.score           = req.getScore()      ← carried from free summary
         report.riskLevel       = req.getRiskLevel()  ← carried from free summary
         report.premiumScore    = AI output           ← independent AI score
         report.premiumRiskLevel= AI output
         report.scoreRationale  = AI output
         │
         ▼
[12] ReportService.generateAndStoreReport()
     stored.setRiskScore(fullReport.getScore())      ← free summary score in DB column
     stored.setRiskLevel(fullReport.getRiskLevel())
     objectMapper.writeValueAsString(fullReport)     ← full JSON (inc. premiumScore) stored
         │
         ▼
[13] GET /premium-report/{reportId} → premium-report.html
     Displays: report.score * 10 as % (free summary score — for context)
     Also displays: report.premiumScore and report.scoreRationale (unlocked section)
     Locked preview: score visible; premiumScore visible in cover; narrative hidden
         │
         ▼
[14] GET /report/{reportId}/download → premium-report-pdf.html
     Same report object → PDF shows both scores
```

---

## 4. Free Summary Score Generation

### Where it comes from

**Class:** `RiskScoringService`  
**Called from:** `JobAiService.assessJobRisk()` after the AI call  
**File:** `src/main/java/com/chikere/jobai/service/RiskScoringService.java`

The score is **deterministic** — given the same inputs, it always produces the same result. It does not change between runs.

### The 5-dimension model

`RiskDimensionCalculator.calculate()` scores 5 dimensions from the combined subject + details text:

| Dimension | Weight | What it measures |
|---|---|---|
| `taskRepeatability` | 30% | How predictable and rule-based the work is |
| `digitalExecution` | 25% | How much the work lives in digital systems AI can access |
| `humanInteraction` | 20% | How much deep interpersonal judgement is required |
| `creativityExecution` | 15% | How much original thought vs execution of known patterns |
| `environmentComplexity` | 10% | How structured vs unpredictable the real-world setting is |

**Baselines by journey type:**

| Dimension | Professional | University | School/A-Level |
|---|---|---|---|
| taskRepeatability | 4.8 | 4.3 | 4.0 |
| digitalExecution | 5.0 | 4.4 | 4.0 |
| humanInteraction | 5.0 | 4.5 | 4.2 |
| creativityExecution | 4.8 | 4.3 | 4.0 |
| environmentComplexity | 4.7 | 4.2 | 4.0 |

**Default weighted base score (no keyword matches):**

| Journey | Approximate default score | Displayed as |
|---|---|---|
| Professional | ~4.9 | ~49% |
| University | ~4.3 | ~43% |
| School / A-Level | ~4.0 | ~40% |

### Keyword adjustments

Keywords in the text trigger additive adjustments to individual dimensions:

| Trigger keywords | Dimension affected | Adjustment |
|---|---|---|
| "data entry", "admin", "scheduling", "routine", "repetitive", "transcription" | taskRepeatability | +2.6 |
| "documentation", "reporting", "testing", "debugging", "processing" | taskRepeatability | +1.4 |
| "leadership", "strategy", "nurse", "electrician", "singer", "social care" | taskRepeatability | −2.0 |
| "software", "developer", "coding", "analyst", "spreadsheet", "online" | digitalExecution | +3.0 |
| "electrician", "nurse", "care", "physical", "hands-on", "field work" | digitalExecution | −3.0 |
| "data entry", "admin", "processing", "forms", "reporting" | humanInteraction | +2.2 |
| "nurse", "care", "empathy", "teaching", "negotiation", "leadership" | humanInteraction | −2.4 |
| "data entry", "scripted", "routine", "documentation", "testing" | creativityExecution | +2.1 |
| "creative", "brand", "concept", "strategy", "leadership" | creativityExecution | −2.0 |
| "structured", "scripted", "rules-based", "forms", "processing" | environmentComplexity | +2.1 |
| "unpredictable", "emergency", "field work", "patient", "live performance" | environmentComplexity | −2.1 |

Keyword matches cap at 2× per rule (a second matching keyword doubles the adjustment; a third does not add more).

### Role hard caps

Certain role types have their dimensions clamped to fixed ranges regardless of keyword content:

| Role category | Effect |
|---|---|
| Singer, choir, vocalist | All dimensions capped at very low values (≤2.4) |
| Nurse / nursing | Dimensions clamped to narrow low-moderate ranges |
| Electrician | taskRepeatability ≤ 3.4, digitalExecution ≤ 1.8 |
| CEO / chief executive | taskRepeatability ≤ 2.6, humanInteraction ≤ 2.2 |
| **Data entry clerk** | All dimensions floored to 8.4–9.2 (extreme high risk) |
| **Call center agent** | All dimensions floored to 7.2–8.4 |
| Software developer | digitalExecution floored at 8.8; others range-clamped |
| Graphic designer | digitalExecution floored at 8.0; creativity range-clamped |

### Protective adjustment

`RiskAdjustmentService.protectiveAdjustment()` subtracts up to 3.5 from the base score:

| Trigger keywords | Reduction |
|---|---|
| "live performance", "performer", "singer", "choir", "audience" | −1.1 |
| "physical presence", "hands-on", "site", "field work", "patient", "ward", "electrician" | −0.9 |
| "real-time", "coordination", "stakeholder", "leadership", "ceo" | −0.8 |
| "emotional intelligence", "empathy", "safeguarding", "care", "nurse", "counselling" | −0.9 |
| "unpredictable", "chaotic", "emergency", "home visit", "real-world" | −0.8 |

Hard caps on adjustment: call center max −0.6, nurse max −1.0.

### Risk level thresholds

`RiskSanityValidator.riskLevel()`:

| Score | Risk Level |
|---|---|
| 0.0 – 3.4 | Low |
| 3.5 – 6.9 | Moderate |
| 7.0 – 10.0 | High |

### Aligned summary generation

`RiskSanityValidator.alignedSummary()` generates the displayed summary. It:
- Names the user's subject.
- States the impact level ("low/moderate/high AI impact").
- Explains the dominant dimension that drove the score.
- Notes protective factors if the adjustment was ≥ 0.8.
- Appends a journey-specific teaser for the premium report.
- **Never exposes the numeric score or percentage in the text.**

### What the AI's `assessment` field does

The 2-sentence AI narrative (`assessment`) is the only thing kept from AI Call 1. It is displayed below the score on `result.html` under "Assessment Summary". It is not used to compute anything — it is purely copy.

---

## 5. Premium Report Score

### Does the premium report calculate a new score?

**Yes.** The premium report generates its own score independently via AI Call 2.

### The three premium score fields

| Field | Source | Purpose |
|---|---|---|
| `PremiumReport.score` | Copied from `GenerateReportRequest.score` (free summary score) | Provides continuity — shows what the user was told in the free snapshot |
| `PremiumReport.premiumScore` | AI output from premium prompt | The AI's deeper assessment; may differ from the free score |
| `PremiumReport.premiumRiskLevel` | AI output; must align to `premiumScore` per thresholds | Validated against the same ≤3.4 / 3.5–6.9 / ≥7.0 bands |
| `PremiumReport.scoreRationale` | AI output | Explains why the AI chose `premiumScore`; shows if it adjusted the initial score and why |

### `mapToReport()` code

```java
// PremiumReportAiService.java
PremiumReport.builder()
    .score(req.getScore())                        // ← free summary score (carried through)
    .riskLevel(req.getRiskLevel())                // ← free summary riskLevel (carried through)
    .premiumScore(...)                            // ← AI output
    .premiumRiskLevel(...)                        // ← AI output
    .scoreRationale(text(root, "scoreRationale")) // ← AI output
    ...
```

### What is stored in the database

`ReportRequest.riskScore` and `ReportRequest.riskLevel` store the **free summary** score (for analytics queries). The `premiumScore` is inside `reportJson` (the serialised `PremiumReport` LOB) only — it is not a separate indexed column.

### What is displayed in templates

| Template | Score shown |
|---|---|
| `result.html` | `score` (deterministic) as `score * 10 %` |
| `premium-report.html` (cover stat box) | `report.score * 10 %` (free summary, for reference) |
| `premium-report.html` (section 01 gauge) | `report.score * 10 %` |
| `premium-report.html` (premium section) | `report.premiumScore` with `scoreRationale` (unlocked only) |
| `premium-report-pdf.html` | Both `score` and `premiumScore` |

### Are task exposure percents independent of the headline score?

**Yes, always (in live mode).** The `taskExposureMap[].exposurePercent` values come from the premium AI's own analysis of the profession and original details. They are not derived from either `score` or `premiumScore`.

`ReportService` retains a `buildMockReport()` method (accessible via `app.ai.use-dummy=true`, default `false`) which uses riskLevel-banded hardcoded values. In production this path is inactive.

---

## 6. Same Browser Session — Multiple Journeys

### sessionStorage overwrite behaviour

When the user clicks "Generate Report Preview" in `result.html`, the JavaScript **always overwrites** `sessionStorage.checkoutPayload` completely:

```javascript
sessionStorage.setItem('checkoutPayload', JSON.stringify(payload));
window.location.href = '/generating-report';
```

There is no accumulation or merging. The last assessment wins. Sequential journeys in the same tab are safe:

```
Assess profession A → result → click "Generate" → payload A written → report generated for A
Assess profession B → result → click "Generate" → payload B OVERWRITES A → report generated for B ✓
```

### Risk: stale sessionStorage if `/generating-report` is reached without clicking "Generate"

`sessionStorage` persists across page loads and back/forward navigation within the same tab. If a user abandons `/generating-report` mid-way and navigates back to it without re-assessing, the old payload fires again:

```
Assess profession A → click "Generate" → payload A stored → abandon page
Navigate back to /generating-report (browser history)
→ old payload A is in sessionStorage → generates stale report
```

There is no server-side guard against this.

### Flash attribute safety

Spring MVC flash attributes are one-time: stored in the HTTP session for exactly one redirect cycle, consumed on `GET /result`, then deleted. Refreshing `/result` renders the "no data" state — `success` is null/false. The score card does not render.

### Server-side session state

No `HttpSession` carries score data. The server is stateless across the free assessment → premium report flow. The only persistent server-side state is the `ReportRequest` entity in the database.

### reportId uniqueness

Each premium report receives `UUID.randomUUID()` in `PremiumReportAiService.generate()`. Every generated report is independent. There is no reuse risk.

### Browser back/forward cache

If the browser serves a cached `/result` page (bfcache), pressing "back" shows the old score (baked into the Thymeleaf JS at render time). Clicking "Generate" on a cached result page saves those baked-in values to sessionStorage — correct for the visible result. This is expected behaviour.

If the user navigates directly to `/result` without a prior `POST /assess` redirect, the model has no flash attributes. `success` is null, score is not rendered. Clicking "Generate" from this state would save `{ score: 0, ... }` to sessionStorage.

---

## 7. Mode-Specific Scoring

### Summary

| Mode | Journey | Baseline range | AI model (narrative) | Prompt file |
|---|---|---|---|---|
| `profession` | Professional | 4.7–5.0 | gpt-5.4 (premium) | `profession-instructionsV2.txt` |
| `course` | University student | 4.2–4.5 | gpt-5.4-mini | `course-instructionsV2.txt` |
| `a_level` | School / pre-university | 4.0 | gpt-5.4-mini | `a-level-instructionsV3.txt` |

### Are scores semantically comparable across modes?

**No.** The score meaning differs:

- **profession:** How automatable are the day-to-day tasks of this job?
- **course:** How AI-exposed are the career paths this degree leads to?
- **a_level:** How narrow or automatable are the future paths this subject direction points toward?

The same 0–10 scale and Low/Moderate/High labels are used for all three, but the `scoreExplanation` text shown to the user on `result.html` explains the mode-specific interpretation.

### Scoring differences across modes

The differences are in **baselines**, not the scoring rules. A professional with no distinctive keywords scores ~4.9. A student with the same description scores ~4.3. A school student scores ~4.0. Keyword adjustments apply identically across all modes.

This means the same description produces a lower score for students than for professionals — intentional, because student journeys are assessed against less certain future paths, and the default risk baseline is lower.

### AI model differences

Only the Professional journey uses the premium model (gpt-5.4) for narrative generation. University and school journeys use the mini model (gpt-5.4-mini). Both operate at temperature 0.2. The narrative quality may differ, but the **score** is unaffected by model choice — it comes from `RiskScoringService`, not the AI.

---

## 8. Likely Causes of Similar Scores

The original investigation identified dummy mode (mean ~5.8) as the primary cause. That path has been removed from `JobAiService`. The analysis below reflects the current architecture.

---

### Cause 1 — Common keywords pushing most professional roles into the 55–65% band (MEDIUM CONFIDENCE)

The keyword adjustments for common office-role vocabulary are additive and stack across dimensions:

| Words present | Dimensions affected | Combined effect on weighted score |
|---|---|---|
| "reporting" | taskRepeatability +1.4, humanInteraction +2.2 | weighted ≈ +0.9 |
| "documentation" | taskRepeatability +1.4, creativityExecution +2.1 | weighted ≈ +0.7 |
| "reporting" + "documentation" | both rules fire | weighted ≈ +1.6 on top of 4.9 baseline → ~6.5 |

A professional describing their role with "I handle reporting and documentation" would score approximately **6.5 → 65%**. Many generic office roles without distinctive high-risk or low-risk vocabulary land in the 55–65% range.

This is not a flaw — it is the expected behaviour of the model for genuinely moderate-risk roles. It becomes visible as "similar scores" only when a diverse set of professions all lack strong distinguishing keywords.

---

### Cause 2 — Roles with strong signals score very differently (CONFIRMED, working as intended)

| Role | Approximate score | Displayed |
|---|---|---|
| Data entry clerk | ~8.7 (hard floor) | ~87% |
| Call center agent | ~7.8 (hard floor) | ~78% |
| Software developer | ~6.5 (range-clamped) | ~65% |
| Generic professional (no keywords) | ~4.9 (baseline) | ~49% |
| Graphic designer | ~6.0 (range-clamped) | ~60% |
| CEO | ~3.4 (hard cap) | ~34% |
| Electrician | ~2.8 (hard cap) | ~28% |
| Nurse | ~2.5–3.5 (range-clamped) | ~25–35% |
| Singer / choir | ~1.6 (hard cap) | ~16% |

Professions with explicit keyword matches spread across the full 0–10 range. Similar scores only appear for the "middle ground" — roles that don't clearly match any keyword cluster.

---

### Cause 3 — Free summary score and premium report cover score are the same value (CONFIRMED, by design)

`PremiumReport.score` is copied from the `GenerateReportRequest.score`, which came from the free assessment. The cover stat box in `premium-report.html` shows this value.

A user who saw 55% in the free snapshot will see 55% on the premium report cover. This is intentional — continuity for the user. The `premiumScore` (AI-generated, potentially different) is shown as a separate field with its own rationale in the unlocked report body.

---

### Cause 4 — Score formatting is correct (NOT A CAUSE)

Both templates use `report.score * 10` for display. `5.5 → 55%`, `7.0 → 70%`. The arithmetic is correct. No formatting bug.

---

### Cause 5 — What is no longer a cause: dummy mode

The old investigation identified dummy mode as the primary cause of ~58% clustering. `JobAiService` no longer has dummy mode logic. `app.ai.use-dummy` is absent from `application.properties`. `ReportService.buildMockReport()` still exists as a fallback (defaulting to inactive) but would only be triggered if `APP_AI_USE_DUMMY=true` is explicitly set as an environment variable.

If dummy mode was recently removed, scores may have shifted from consistently ~58% to values driven by the keyword model — typically 40–65% for professional roles without strong signals.

---

## 9. Risks

### Risk 1 — Critical: Description fallback chain may use degraded context

In `generating-report.html`:

```javascript
description: stored.originalDetails || stored.details || stored.assessment || stored.summary || '',
```

If `originalDetails` is absent from sessionStorage, the fallback uses:
- `stored.assessment` — the 2-sentence AI narrative (very short, not the user's actual input)
- `stored.summary` — the aligned summary sentence generated by `RiskSanityValidator` (not user input at all)

The premium AI then receives a generated sentence as if it were the user's role description. This produces a less personalised report. The user would not know this happened.

**When this occurs:** If sessionStorage has a stale payload from a previous incomplete session, or if the user navigates directly to `/generating-report`.

---

### Risk 2 — Medium: Score of 0 if user reaches `/generating-report` without prior assessment

If `sessionStorage.checkoutPayload` is missing, the JS sends `score: 0` and `riskLevel: ''`. The server accepts this without validation. The premium AI receives `Risk Score: 0.0/10` and generates a report anchored on that false baseline. `PremiumReport.score = 0`, which renders as `0%` on the cover.

`GenerateReportRequest` has no `@Valid` constraints — the server does not reject this.

---

### Risk 3 — Medium: No server-side validation of `GenerateReportRequest`

Any HTTP client can send arbitrary `score`, `riskLevel`, or `mode` values to `POST /generate-report`. A malformed request is not caught until it causes a downstream failure (e.g., `JourneyType.fromMode()` throws for an unknown mode, which would produce a 500).

---

### Risk 4 — Medium: `premiumScore` not stored as a queryable DB column

`ReportRequest.riskScore` holds the free summary score. `premiumScore` lives only inside the `reportJson` LOB. This means:
- Analytics queries on scores only see the free summary score.
- You cannot query "reports where premiumScore > 7.0" without deserialising all report JSON.
- If a premium score is ever needed for a report listing, the JSON must be parsed.

---

### Risk 5 — Low: Browser back/forward shows stale result page

bfcache may serve a cached `/result` page when the user presses "back". The baked-in score and profession are correct for the visible result, but if the user came from a different journey on the same tab, they see and interact with the old result. Clicking "Generate" would overwrite sessionStorage with the old values — generating a report for the previous assessment, not the one the user may be expecting.

---

### Risk 6 — Low: `gpt54ReportChatClient` bean is defined but not wired

`AIModelConfiguration` defines a `gpt54ReportChatClient` bean (premium model, temperature 0.6, intended for richer narrative). `PremiumReportAiService` currently uses `gpt54MiniChatClient` (mini, 0.2). The report client bean is unused. This is either dead configuration or an intended future upgrade that was not completed.

---

### Risks resolved since original investigation

| Original risk | Status |
|---|---|
| AI score outside 0–10 rendering as 115% | **Resolved** — `RiskScoringService` overrides the AI score entirely; `clampScore()` also exists in `JobAiService` |
| Dummy mode riskLevel threshold discrepancy | **Resolved** — dummy mode removed from `JobAiService`; `RiskSanityValidator` uses consistent thresholds (≤3.4/3.5–6.9/≥7.0) |
| AI score clustering around 5.8 | **Resolved** — AI no longer sets the score |
| Dummy mode `buildDummyAssessment` mean ≈5.8 | **Resolved** — method removed |

---

## 10. Test Coverage

### What is now tested

| Test Class | What it covers |
|---|---|
| `RiskScoringServiceTest` | Risk level thresholds (3.4/3.5/6.9/7.0), sample roles (Low/Moderate/High expected levels), protective factor reduction, summary alignment and no numeric score in text |
| `RiskDimensionCalculatorTest` | Keyword adjustments per dimension, journey baselines, role hard caps, clamp behaviour |
| `RiskScoringBenchmarkTest` | Score distribution across a range of role/journey combinations — guards against regression |
| `JobAiServiceTest` | Correct prompt built per journey; correct instruction file injected |
| `JobAiServiceMockedChatClientTest` | Mocked ChatClient; verifies `RiskScoringService` is called and that its output overrides the AI's score |
| `RiskAssessmentServiceTest` | Form validation, CV extraction, `resolvedDetails` preserved, all 3 journey validations |
| `PremiumReportAiServiceTest` | Prompt framing per journey; journey-specific instructions injected |
| `PremiumReportAiServiceMockedChatClientTest` | Mocked ChatClient; verifies JSON mapping to `PremiumReport` including `premiumScore` |
| `ReportServiceTest` | Report persistence, payment status transitions, expiry, purge |
| `ReportPreviewServiceTest` | Locked preview has only allowed fields; premium sections cleared; `taskExposureMap` limited to 3 rows |
| `CheckoutControllerTest` | Price ID routing per journey; fallback for unconfigured a-level price |
| `RiskAssessorControllerTest` | `POST /assess` success path; flash attributes preserved including `originalDetails` |
| `JourneyConfigRegistryTest` | Config lookup, word limits, flags |
| `JourneyTypeTest` | `fromMode()` for all 3 modes; invalid mode handling |
| `ResultTemplateTest`, `PremiumReportTemplateTest`, `GeneratingReportTemplateTest` | Thymeleaf renders correctly for locked/unlocked states |
| `NoRealAiSafetyTest` | Guards against live AI calls in tests |

### Remaining gaps

| Gap | Risk |
|---|---|
| No test for `description` fallback chain — missing `originalDetails` silently uses assessment text | Premium report generated from wrong input |
| No test for `GenerateReportRequest` with `score=0` or blank `riskLevel` | Invalid payload accepted server-side |
| No test for the `gpt54ReportChatClient` bean being wired and used | Unused bean may confuse future developers |
| No test for `premiumScore` being stored inside `reportJson` and correctly round-tripped | Serialisation regression undetected |
| No test for PDF content correctness | Layout regressions undetected |
| No end-to-end payment webhook integration test | Lock/unlock flow tested manually only |
| No test for stale sessionStorage across browser back/forward | Stale report context undetected |

---

## 11. Recommended Fixes

### Fix 1 — Add server-side validation to `GenerateReportRequest`

**File:** `GenerateReportRequest.java`

Add `@NotBlank` on `mode` and `riskLevel`, and validate `score` is in [0.0, 10.0] before calling `PremiumReportAiService`. Reject or sanitise invalid payloads with a 400 response rather than letting them produce broken reports.

---

### Fix 2 — Guard against missing `originalDetails` in the description fallback

**File:** `generating-report.html` (JS) and/or `ReportController.java`

Option A: In the JS, check that `stored.originalDetails` is non-empty before proceeding. If it is absent, redirect the user back to the form with a message.

Option B: Server-side — validate that `request.getDescription()` has a minimum length (e.g., 20 chars) and return a 400 if not.

---

### Fix 3 — Store `premiumScore` as a separate DB column

**File:** `ReportRequest.java`

Add `@Column private Double premiumScore;` to `ReportRequest` and populate it in `ReportService.generateAndStoreReport()`. This allows analytics queries on the AI-generated premium score without deserialising report JSON.

---

### Fix 4 — Wire or remove `gpt54ReportChatClient`

**File:** `AIModelConfiguration.java`, `PremiumReportAiService.java`

Either inject `gpt54ReportChatClient` into `PremiumReportAiService` (premium model, temp 0.6) for richer narrative, or delete the bean to remove dead configuration. Leaving it undefined wastes a Spring bean and misleads future developers.

---

### Fix 5 — Prevent stale sessionStorage generating wrong report

**File:** `generating-report.html`

When the page loads, validate that `stored.profession` is non-empty and that the stored payload looks recent (e.g., by adding a `timestamp` field when saving in `result.html`). If the payload is missing or too old, redirect to the home page rather than silently generating with stale data.

---

## 12. Suggested Next Steps

In priority order:

1. **Add `@Valid` constraints to `GenerateReportRequest`** (Fix 1) — prevents score=0 and blank riskLevel from reaching the AI.

2. **Guard the description fallback chain** (Fix 2) — highest-impact quality risk; a missing `originalDetails` silently degrades the premium report.

3. **Add a test for `score=0` and blank `riskLevel` in `GenerateReportRequest`** — quick unit test that pins the validation behaviour once Fix 1 is in place.

4. **Add a test for the `description` fallback chain** — assert that `originalDetails` is present and non-empty before the `POST /generate-report` call fires.

5. **Store `premiumScore` as a DB column** (Fix 3) — enables future analytics queries without JSON deserialisation.

6. **Resolve the `gpt54ReportChatClient` question** (Fix 4) — wire it or remove it; don't leave unused infrastructure.

7. **Confirm that `app.ai.use-dummy` is not set in any production environment** — `buildMockReport()` still exists in `ReportService` and would silently activate if this variable were ever set.

---

## Files Changed

| File | Action |
|---|---|
| `docs/scoring-investigation.md` | **Rewritten** — updated to reflect current architecture |

**No production code was modified.**

---

*Updated: 2026-05-02 · Branch: feature/multi-journey-assessment*
