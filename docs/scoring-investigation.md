# Scoring Investigation — WillAIStealMyJob

**Date:** 2026-05-01  
**Branch:** feature/multi-journey-assessment  
**Status:** Investigation only — no production code changed

---

## Table of Contents

1. [Scoring Architecture Overview](#1-scoring-architecture-overview)
2. [Full Score Data Flow Diagram](#2-full-score-data-flow-diagram)
3. [Free Summary Score Generation](#3-free-summary-score-generation)
4. [Premium Report Score](#4-premium-report-score)
5. [Same Browser Session — Multiple Journeys](#5-same-browser-session--multiple-journeys)
6. [Mode-Specific Scoring](#6-mode-specific-scoring)
7. [Likely Causes of Similar Scores (58–59%)](#7-likely-causes-of-similar-scores-5859)
8. [Risks](#8-risks)
9. [Test Gaps](#9-test-gaps)
10. [Recommended Fixes](#10-recommended-fixes)
11. [Suggested Next Implementation Steps](#11-suggested-next-implementation-steps)

---

## 1. Scoring Architecture Overview

The application uses a **single-score model**: the score is generated once during the free assessment, then copied into all subsequent structures (flash attributes → sessionStorage → GenerateReportRequest → PremiumReport → ReportRequest entity → HTML templates → PDF).

The premium AI does **not** recalculate the headline score. It receives the original score as context in its prompt and outputs all other fields (disruption window, task exposure, timeline, skills, salary, etc.). The `score` field in the JSON response schema for `premium-report-prompt.txt` does not exist — there is no `score` output field in that prompt's response.

```
FREE ASSESSMENT                     PREMIUM REPORT
─────────────────                   ──────────────────────────────────────────────
POST /assess                        POST /generate-report
  → JobAiService                      → PremiumReportAiService
      → AI model                          → AI model (receives score as context)
          → { score: 5.8 }               → No score in output — copies req.getScore()
                ↓                                             ↓
     stored in flash attrs           report.score = request.score (same value)
                ↓                                             ↓
     result.html shows 58%          premium-report.html shows 58%
```

There is **one exception**: in `dummy mode`, a separate local algorithm generates the score for the free assessment AND the mock premium report also copies `req.getScore()` — so the same value flows through.

---

## 2. Full Score Data Flow Diagram

```
[1] User submits POST /assess
         │
         ▼
[2] RiskAssessmentService.processAssessmentWithDetails()
    Validates form → extracts roleSummary (manual or CV)
    Calls: JobAiService.assessJobRisk(mode, profession, roleSummary)
         │
         ▼
[3] JobAiService.assessJobRisk()
    ┌─── dummy mode: buildDummyAssessment() → deterministic score from hash + keyword adjustments
    └─── live mode:  chatClient.prompt(prompt).call() → AI returns JSON → parsed to JobRiskAssessment
                                                         { score: 5.8, riskLevel: "Moderate", summary: "...", assessment: "..." }
         │
         ▼
[4] AssessmentProcessingResult{ assessment(score=5.8), resolvedDetails, profession, journeyType, mode }
         │
         ▼
[5] RiskAssessorController.addSuccessAttributes()
    Flash attributes stored:
      score      = 5.8
      riskLevel  = "Moderate"
      summary    = "..."
      assessment = "..."
      profession = "..."
      mode       = "profession"
      originalDetails = "...(role summary text)..."
         │
         ▼ redirect
[6] GET /result  →  result.html
    Displays: score * 10 = 58% (as circular gauge and progress bar)
    NOTE: formatInteger(score * 10) — multiply by 10 only for display
         │
         ▼ user clicks "Generate My Premium Report"
[7] result.html JavaScript saves to sessionStorage.checkoutPayload:
    {
      mode:            "profession",
      profession:      "Software Engineer",
      score:           5.8,             ← raw AI score, NOT percentage
      riskLevel:       "Moderate",
      summary:         "...",
      assessment:      "...",
      originalDetails: "...(role summary text)..."
    }
    window.location.href = '/generating-report'
         │
         ▼
[8] GET /generating-report  →  generating-report.html
    On load, JS reads sessionStorage.checkoutPayload and calls:
    POST /generate-report with body:
    {
      profession:  stored.profession,
      description: stored.originalDetails || stored.details || stored.assessment || stored.summary || '',
      score:       stored.score,       ← 5.8 passed unchanged
      riskLevel:   stored.riskLevel,
      mode:        stored.mode
    }
         │
         ▼
[9] ReportController.generateReport()
    → ReportService.generateAndStoreReport(request)
    → PremiumReportAiService.generate(request)
      Builds premium prompt with {score} = "5.8" and {riskLevel} = "Moderate"
      AI returns JSON with all sections BUT no new score field
      mapToReport() copies:
        report.score     = req.getScore()      ← 5.8 (unchanged)
        report.riskLevel = req.getRiskLevel()  ← "Moderate" (unchanged)
         │
         ▼
[10] ReportService.generateAndStoreReport()
     stored.setRiskScore(fullReport.getScore())  ← 5.8 written to DB column
     stored.setRiskLevel(fullReport.getRiskLevel())
     objectMapper.writeValueAsString(fullReport)  ← full JSON with score=5.8 stored in reportJson LOB
         │
         ▼
[11] GET /report/{reportId}  →  premium-report.html
     Displays: report.score * 10 = 58%
     Locked preview: score still visible (ReportPreviewService retains score field)
     Unlocked: full report with same score
         │
         ▼
[12] GET /report/{reportId}/download  →  premium-report-pdf.html
     Thymeleaf renders same report.score → PDF shows 58%
```

---

## 3. Free Summary Score Generation

### Where it is generated

**Class:** `JobAiService`  
**Method:** `assessJobRisk(String mode, String profession, String roleSummary)` — line 71  
**File:** `src/main/java/com/chikere/jobai/service/JobAiService.java`

### Live mode (default)

The score is entirely determined by the AI model. The prompt (`jobai.txt`) instructs the model:

```
Score Range | Risk Level | Interpretation
0.0 – 2.9   | Low        | ...
3.0 – 6.9   | Moderate   | ...
7.0 – 10.0  | High       | ...
```

The AI must return a JSON object `{ "score": X.X, "riskLevel": "...", ... }`. There is no server-side post-processing of the score value itself. Whatever the AI returns is used directly.

```java
// JobAiService.java:96–103
ChatResponse chatResponse = selectedChatClient.prompt(prompt).call().chatResponse();
String response = extractContent(chatResponse);
String cleanedResponse = cleanJsonResponse(response);
JobRiskAssessment assessment = objectMapper.readValue(cleanedResponse, JobRiskAssessment.class);
// assessment.getScore() is whatever the AI returned
```

**AI model behaviour note:** Real language models are calibrated to avoid extremes. In the absence of a strongly high-risk or low-risk profession, models commonly return scores in the 4.5–6.5 range. This produces displayed percentages of 45–65%, clustering around 58–60% for moderate-risk professions.

### Dummy mode (`app.ai.use-dummy=true`)

**Method:** `buildDummyAssessment(String mode, String profession, String roleSummary)` — line 172

```java
double score = (Math.floorMod(
    Objects.hash(mode, profession.toLowerCase(), content), 71
) / 10.0) + 1.5;

score += scoreAdjustment(content);
score = Math.max(0.5, Math.min(9.5, Math.round(score * 10.0) / 10.0));

String riskLevel = score <= 2 ? "Low" : (score <= 6 ? "Moderate" : "High");
```

**Score range analysis:**

| Component | Min | Max | Mean |
|---|---|---|---|
| Hash % 71 | 0 | 70 | ~35 |
| Divided by 10 | 0.0 | 7.0 | ~3.5 |
| Plus 1.5 (offset) | 1.5 | 8.5 | **~5.0** |
| After common adjustments (+0.8 for analysis/documentation) | 2.3 | 9.3 | **~5.8** |
| Clamped | 0.5 | 9.5 | ~5.8 |

**Keyword adjustments applied:**

| Keywords present | Adjustment |
|---|---|
| "repetitive", "data entry", "admin", "scheduling", "routine", "transcription" | +1.4 |
| "analysis", "reporting", "documentation", "support", "compliance" | +0.8 |
| "leadership", "negotiation", "teaching", "creative", "strategy", "hands-on", "care" | −1.2 |
| "field work", "manual", "stakeholder", "coaching", "customer relationship" | −0.6 |

**Finding:** The most common words appearing in role descriptions ("I do analysis", "I handle documentation", "I provide support") trigger the +0.8 adjustment. Combined with a mean base of 5.0, the expected dummy score for a typical role description is approximately **5.8 → 58% displayed**. This is very likely the primary cause of similar 58–59% scores if the app is running in dummy mode.

### Risk level derived from score

In **live mode** / the prompt, the thresholds are:

| Score | Risk Level |
|---|---|
| 0.0 – 2.9 | Low |
| 3.0 – 6.9 | Moderate |
| 7.0 – 10.0 | High |

In **dummy mode**, the threshold is **different**:

```java
String riskLevel = score <= 3 ? "Low" : (score <= 6 ? "Moderate" : "High");
```

| Score | Dummy riskLevel | Prompt riskLevel |
|---|---|---|
| 2.1–2.9 | **Moderate** | Low (discrepancy) |
| 6.1–6.9 | **High** | Moderate (discrepancy) |

**Finding:** Dummy mode uses stricter thresholds (≤2 for Low, ≤6 for Moderate) than what the prompt instructs the AI to use (≤2.9 for Low, ≤6.9 for Moderate). A score of 2.5 would display as "Moderate" in dummy mode but "Low" in live mode. A score of 6.5 would display as "High" in dummy mode but "Moderate" in live mode.

### Does the prompt explicitly ask for a score?

Yes. The `jobai.txt` prompt in `<output_requirements>` explicitly requires:

```json
{
  "score": "Decimal between 0.0 and 10.0 (one decimal place)",
  "riskLevel": "Exactly one of 'Low', 'Moderate', or 'High'",
  ...
}
```

There are no defaults, fallbacks, or server-side constants for the live score. If the AI returns a value outside 0–10 or fails to return valid JSON, the parse throws a `RuntimeException` (line 114) and the user sees an error.

---

## 4. Premium Report Score

### Does the premium report calculate a new score?

**No.** The premium report always reuses the free summary score.

### Evidence

**`PremiumReportAiService.mapToReport()`** — line 127–134:

```java
PremiumReport report = PremiumReport.builder()
    .reportId(reportId)
    .profession(req.getProfession())
    .mode(req.getMode())
    .score(req.getScore())        // ← copied from request, not from AI output
    .riskLevel(req.getRiskLevel()) // ← copied from request, not from AI output
    ...
```

The premium prompt (`premium-report-prompt.txt`) does not include a `score` output field. The AI receives the score as context (`Risk Score: {score}/10`) to inform its narrative, but does not output a new score.

**`ReportService.generateAndStoreReport()`** — line 49–57:

```java
stored.setRiskScore(fullReport.getScore());    // ← the copied value written to DB
stored.setRiskLevel(fullReport.getRiskLevel()); // ← the copied value written to DB
```

### Which fields use the score in PremiumReport?

| Field | Source |
|---|---|
| `PremiumReport.score` | Copied from `GenerateReportRequest.score` |
| `PremiumReport.riskLevel` | Copied from `GenerateReportRequest.riskLevel` |
| `ReportRequest.riskScore` | Copied from `PremiumReport.score` |
| `ReportRequest.riskLevel` | Copied from `PremiumReport.riskLevel` |
| `PremiumReport.taskExposureMap[].exposurePercent` | AI-generated (live) or hardcoded by riskLevel (dummy) — **independent of headline score** |

### Which score is displayed in `premium-report.html`?

```html
<!-- Cover stat box -->
th:text="${#numbers.formatDecimal(report.score * 10, 1, 0)} + '%'"

<!-- Score gauge circle -->
th:text="${#numbers.formatDecimal(report.score * 10, 1, 0)} + '%'"
```

`report.score` is the copied free summary score. The `* 10` is purely for display (5.8 → 58%).

### Which score is displayed in `premium-report-pdf.html`?

Same `report.score * 10` expression. Same value.

### Which score is stored in `ReportRequest.reportJson`?

The entire serialised `PremiumReport` JSON is stored, which includes the `score` field. Example:

```json
{ "score": 5.8, "riskLevel": "Moderate", ... }
```

### Are task exposure percents independent of headline score?

**In live mode:** Yes. Task `exposurePercent` values (0–100) come from the AI's own analysis in the premium report generation step. They are not derived from the headline score.

**In dummy mode (`buildMockReport()`):** Partially. The task rows use hardcoded values that branch on `riskLevel`:

```java
new TaskRow("Routine and repetitive tasks",
    high ? 84 : low ? 26 : 61,   // always 61 for Moderate
    high ? "High" : low ? "Low" : "Moderate",
    ...)
```

So in dummy mode, every "Moderate" user sees identical task exposure values (61, 56, 48, 43, 29, 20, 10). This means dummy mode premium reports for the same riskLevel are visually identical beyond the profession name.

---

## 5. Same Browser Session — Multiple Journeys

### sessionStorage overwrite behaviour

When the user clicks "Generate My Premium Report" in `result.html`, this JavaScript executes:

```javascript
sessionStorage.setItem('checkoutPayload', JSON.stringify(payload));
window.location.href = '/generating-report';
```

**This always overwrites the full `checkoutPayload` object.** If the user assesses profession A, then profession B, the second click replaces the first payload. There is no accumulation or merging — the last assessment wins.

**Scenario: No stale data leak between journeys**

```
User assesses profession A → result page → clicks "Generate" → payload A stored → /generating-report
  → (report generated for A) → navigates home
User assesses profession B → result page → clicks "Generate" → payload B OVERWRITES A → /generating-report
  → report generated correctly for B ✓
```

**Risk: Stale sessionStorage if user navigates to `/generating-report` without re-clicking "Generate"**

```
User assesses profession A → result page → clicks "Generate" → payload A stored → (doesn't complete)
  → presses browser back → navigates manually to /generating-report
  → old payload A is still in sessionStorage → generates old report
```

This is because `sessionStorage` persists within the browser tab across page loads, including back/forward navigation. There is no server-side check that validates the payload belongs to the current user flow.

### Flash attribute safety

Flash attributes in Spring MVC are **one-time**: stored in the HTTP session for exactly one redirect, consumed on `GET /result`, then deleted. This means:

- If the user refreshes `/result` after viewing it once, the flash attributes are gone and `success` will be null/false.
- The template correctly handles this: `th:if="${success} and ${score != null}"` — the risk score card will not render.
- There is **no server-side session state** that persists the score beyond that one redirect.

### Server-side session state

No `HttpSession` is used for score data. The server is stateless for this flow. The only persistent state is the `ReportRequest` database entity keyed by UUID.

### reportId reuse risk

Each premium report receives a fresh `UUID.randomUUID()` generated in `PremiumReportAiService.generate()` (line 65). This is independent of any previous report. There is no reuse risk.

### Browser back/forward cache

If the browser caches the `/result` page (bfcache), pressing "back" shows the old rendered HTML including the old profession and score in the Thymeleaf-baked JavaScript:

```javascript
const score = /*[[${score}]]*/ 0;  // baked in at server render time
```

If the user then clicks "Generate My Premium Report" on this cached page, the JS reads the baked-in values (correct for that result) and overwrites `sessionStorage`. This is the intended behaviour — the data would be correct for the visible result.

However, if the user navigates to `/result` directly (no redirect from `/assess`), the Thymeleaf model has no `score` attribute, so the score displays as the default `0`. The button still saves that `{ score: 0, ... }` payload to sessionStorage if clicked. This would generate a report with score=0.

---

## 6. Mode-Specific Scoring

### Summary

| Mode | Journey | Model Used | Score Meaning | Prompt File |
|---|---|---|---|---|
| `profession` | Professional | `gpt54ChatClient` (premium, gpt-5.4) | Job automation risk | `profession-instructions.txt` |
| `course` | University student | `gpt54MiniChatClient` (mini, gpt-5.4-mini) | Career path AI exposure | `course-instructions.txt` |
| `a_level` | School / pre-university | `gpt54MiniChatClient` (mini, gpt-5.4-mini) | AI future-readiness risk | `a-level-instructions.txt` |

### Are scores semantically comparable across modes?

**No.** A score of 7.0 means different things per mode:

- **profession 7.0**: Most day-to-day tasks are automatable in this job.
- **course 7.0**: The career paths this degree leads to are highly AI-exposed.
- **a_level 7.0**: The subject/interest direction is narrow or leads to highly automatable paths without strong human skills.

The same score dial in `result.html` and `premium-report.html` is used for all three, but the `scoreExplanation` text varies:

```html
scoreExplanation = mode == 'a_level'
  ? 'Score reflects how exposed your likely future study and career paths may be to AI disruption...'
  : mode == 'course'
  ? 'Score reflects how exposed the career paths linked to this course may be...'
  : 'Score reflects how much of your current work can be automated...'
```

The scoring scale (0–10) and risk level labels ("Low", "Moderate", "High") are shared across all modes.

### Model selection logic

```java
// JobAiService.java:119–121
private ChatClient selectAssessmentModel(String mode) {
    return journeyConfigRegistry.get(mode).journeyType().isProfessional()
        ? gpt54ChatClient
        : gpt54MiniChatClient;
}
```

Only PROFESSIONAL uses the premium model. This may produce more reliably calibrated scores for professionals. University and school journeys use the mini model, which is cheaper but may be more variable in its scoring.

---

## 7. Likely Causes of Similar Scores (58–59%)

### Cause 1 — Dummy mode average score ≈ 5.8 (HIGH CONFIDENCE)

**Probability: Very likely if `app.ai.use-dummy=true`**

The dummy mode hash function produces a uniform distribution across 0–70. After dividing by 10 and adding the 1.5 offset, the mean base score is **5.0**. The most common keyword cluster — any description containing words like "analysis", "reporting", "documentation", or "support" — adds **+0.8**, bringing the mean effective score to **~5.8 → 58%**.

This is not a bug in the hash function itself — hash randomness is correct. The issue is that:
1. The +0.8 keyword trigger covers extremely common English words ("support", "reporting", "analysis").
2. The base mean is 5.0, and +0.8 lands squarely on 5.8.
3. The dummy score is deterministic for the same input — testing with the same profession across sessions always returns the same score.

**Diagnosis check:** If many different professions are all showing ~58%, the application is almost certainly running in dummy mode or the professions being tested happen to use the +0.8 keyword cluster.

---

### Cause 2 — AI model moderate-range bias (MEDIUM CONFIDENCE)

**Probability: Plausible for live mode**

Large language models are calibrated to avoid extreme outputs. For ambiguous inputs, they tend to cluster around moderate values. For a 0–10 risk scale, "moderate" would be 4–6, which maps to 40–60% displayed. A score of 5.8 is exactly where a model might settle for a "generic office role" without strong signals in either direction.

This is not a code defect — it is the expected behaviour of the AI. The prompt does include clear scoring guidance, but the AI has discretion within those bands.

---

### Cause 3 — Premium report inherits free summary score (CONFIRMED)

**Probability: Certain**

The premium report does not recalculate a score. If two users have free assessment scores of 5.8, both their premium reports will also show 58%. The premium AI has no mechanism to adjust the headline score based on the deeper analysis it performs.

This means:
- If the free summary score is wrong or biased, the premium report carries the same bias.
- If the AI assigns 5.8 to nearly everyone, every premium report shows 58%.

---

### Cause 4 — Task exposure percents cluster in dummy mode (CONFIRMED)

**Probability: Certain for dummy mode**

In `ReportService.buildMockReport()`, the `taskRows()` method hardcodes exposure percentages based only on riskLevel:

```java
new TaskRow("Routine and repetitive tasks", high ? 84 : low ? 26 : 61, ...)
new TaskRow("Data processing and reporting", high ? 76 : low ? 30 : 56, ...)
```

Every "Moderate" risk user in dummy mode sees: 61%, 56%, 48%, 43%, 29%, 20%, 10% — identical values regardless of profession. This makes different professions look identical in the task exposure section.

---

### Cause 5 — Score formatting is correct (NOT A CAUSE)

Both templates use `report.score * 10` for display. The arithmetic is correct. A score of `5.8` displays as `58%`. There is no formatting bug.

---

## 8. Risks

### Risk 1 — Critical: Description fallback chain may use the wrong context

In `generating-report.html` (line 481):

```javascript
description: stored.originalDetails || stored.details || stored.assessment || stored.summary || '',
```

If `originalDetails` is absent from sessionStorage (e.g., old payload from before this field was added, or a browser reload), the fallback chain uses:
- `stored.assessment` — the 2-sentence free assessment narrative (very short, not the user's actual input)
- `stored.summary` — the 1-sentence summary (16 words max)

The premium AI then receives a summary sentence instead of the user's original role description. This produces a much less personalised premium report. The user would not know this happened.

**Current mitigation:** `originalDetails` is set in `RiskAssessorController.addSuccessAttributes()` and baked into `result.html` JS. If the user goes directly from `/result` to `/generating-report` in the same session, `originalDetails` should be present.

**Residual risk:** If sessionStorage survives from a previous incomplete flow (user abandoned at `/generating-report` and returned without re-assessing), the old payload may lack `originalDetails` if it was set before that field was introduced.

---

### Risk 2 — High: Dummy riskLevel thresholds differ from live thresholds

Dummy mode uses `score <= 2` for "Low" and `score <= 6` for "Moderate". The prompt instructs the AI to use `<= 2.9` and `<= 6.9`. A dummy score of 2.5 is labelled "Moderate" but would be "Low" in live mode. A dummy score of 6.5 is labelled "High" but would be "Moderate" in live mode.

If testing is done in dummy mode and reporting is done in live mode (or vice versa), the riskLevel label carried into the premium report may not match what live mode would produce for the same numeric score.

---

### Risk 3 — Medium: Score of 0 possible if user accesses `/generating-report` without prior assessment

If `sessionStorage.checkoutPayload` is missing or contains `score: 0` (e.g., user typed the URL directly), the system sends `score: 0` and `riskLevel: ''` to `POST /generate-report`. The premium report is generated with score=0 and empty riskLevel. The AI receives `Risk Score: 0.0/10` and `Risk Level: ` — it may still produce a report, but the headline score and risk badge will be wrong.

The server does not validate that `score > 0` or `riskLevel` is a known value before calling `PremiumReportAiService.generate()`.

---

### Risk 4 — Medium: No server-side validation of sessionStorage data integrity

The `GenerateReportRequest` is accepted directly as a `@RequestBody` with no field-level validation annotations. Any caller can send arbitrary `score`, `riskLevel`, or `mode` values. A malformed request is not detected until it causes a downstream failure.

---

### Risk 5 — Low: Browser back/forward may show stale result page

If the browser caches the `/result` page (bfcache), the old score and profession are visible. Clicking "Generate" from a cached result page with a different session would use the old baked-in values. This is standard browser behaviour but could confuse users who assess multiple professions in one session.

---

### Risk 6 — Low: AI model returns score outside expected range

If the AI returns `score: 11.5` or `score: -1.0`, Jackson will parse it as a `double` and the value will propagate unchecked. `result.html` displays `score * 10` — this would render as "115%" or "-10%". There is no server-side clamping of the AI score in live mode.

---

## 9. Test Gaps

### Existing tests — what they DO cover

| Test Class | Covers |
|---|---|
| `RiskAssessmentServiceTest` | Form validation, CV parsing, correct call to `JobAiService`, correct `resolvedDetails` |
| `JobAiServiceTest` | Correct prompt content injected per journey (profession, course, a_level) |
| `PremiumReportAiServiceTest` | Correct prompt framing per journey; always uses hardcoded `score=5.5` |
| `CheckoutControllerTest` | Correct price ID routing per mode |

### Existing tests — what they do NOT cover

| Gap | Risk |
|---|---|
| No test verifies that `PremiumReport.score` == `GenerateReportRequest.score` | The score copy may silently break if the mapping changes |
| No test for score values outside 0–10 (AI returning bad data) | OOB score would render as "115%" with no error |
| No test for dummy mode riskLevel threshold discrepancy vs prompt thresholds | Different behaviour in test (dummy) vs prod (live) |
| No test for dummy mode score distribution | No assurance that dummy scores are not all ≈5.8 |
| No test that `originalDetails` is non-null and non-empty when used as description | Falls back silently to `assessment` text |
| No test for `GenerateReportRequest` with `score=0` or blank `riskLevel` | Invalid requests not validated |
| No test for the `description` fallback chain in `generating-report.html` | Wrong context used for premium report if fallback triggers |
| No test for same-session multiple journeys (sessionStorage overwrite) | Stale payload risk |
| No test for `/result` accessed without prior flash attributes | Score=null renders as 0%; JS saves `{score: 0}` to sessionStorage |
| No test for premium score displayed in PDF | PDF template correctness unverified |
| `PremiumReportAiServiceTest` hardcodes `score=5.5` for all journeys | No coverage of edge scores (0, 10, boundary values) |

---

## 10. Recommended Fixes

### Fix 1 — Align dummy mode riskLevel thresholds with prompt thresholds

**File:** `JobAiService.java:182`

Change:
```java
String riskLevel = score <= 2 ? "Low" : (score <= 6 ? "Moderate" : "High");
```

To:
```java
String riskLevel = score <= 2.9 ? "Low" : (score <= 6.9 ? "Moderate" : "High");
```

This makes dummy mode produce labels consistent with what the live AI model is instructed to output.

---

### Fix 2 — Validate `GenerateReportRequest` fields server-side

**File:** `GenerateReportRequest.java` and/or `ReportController.java`

Add validation so that `score` is clamped to 0–10 before use, `riskLevel` is checked against known values, and `mode` is validated against `JourneyType.fromMode()`. A request with `score=0` and blank `riskLevel` should either be rejected or have a safe fallback applied.

---

### Fix 3 — Guard against missing `originalDetails` in sessionStorage

The `generating-report.html` fallback chain `stored.originalDetails || stored.details || stored.assessment || stored.summary` is silent. At minimum, log a warning or show the user a message if `originalDetails` is missing. Consider also validating server-side that `description` has a minimum length before calling the AI.

---

### Fix 4 — Clamp live AI score on the server

**File:** `JobAiService.java` — after `objectMapper.readValue()` (line 103)

Add:
```java
assessment.setScore(Math.max(0.0, Math.min(10.0, assessment.getScore())));
```

This prevents OOB values from the AI (e.g., `11.5`) rendering as `115%` in the UI.

---

### Fix 5 — Show dummy mode warning in non-production environments

When `useDummyMode=true`, add a visible banner in the UI (e.g., a dev-only header bar) so testers know the scores are not real AI outputs. This prevents confusion when testing with dummy mode and observing similar scores.

---

## 11. Suggested Next Implementation Steps

In priority order:

1. **Confirm whether production is running in live or dummy mode** — check `APP_AI_USE_DUMMY` environment variable. If `true`, all scores in production are dummy scores and the ~58% observation is explained.

2. **Fix the dummy riskLevel threshold** (Fix 1 above) — low risk, one-line change, removes inconsistency.

3. **Add server-side score clamping** (Fix 4 above) — defensive, prevents display defects.

4. **Add `@Valid` or manual validation to `GenerateReportRequest`** (Fix 2 above) — prevents invalid score=0 reports.

5. **Add a unit test asserting PremiumReport.score == request.getScore()** — pins the copy behaviour so it cannot silently break.

6. **Add a unit test with boundary scores (0.0, 2.9, 3.0, 6.9, 7.0, 10.0)** across both the dummy logic and the `riskLevel` derivation.

7. **Add a unit test for the `description` fallback chain** — simulate a sessionStorage payload with missing `originalDetails` and assert that the fallback content is flagged or handled gracefully.

8. **Consider whether the premium report should recalculate the score** — the current design (copy free score into premium) is intentional and avoids confusion, but if the AI generates a task exposure map that implies a very different risk level, the headline score and the detailed breakdown may feel inconsistent. This is a product decision, not a code defect, but worth documenting.

---

## Files Changed

| File | Action |
|---|---|
| `docs/scoring-investigation.md` | **Created** — this document |

**No production code was modified.**

---

*Investigation completed: 2026-05-01*  
*Branch: feature/multi-journey-assessment*
