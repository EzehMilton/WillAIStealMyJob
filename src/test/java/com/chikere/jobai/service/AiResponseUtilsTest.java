package com.chikere.jobai.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiResponseUtilsTest {

    @Test
    void extractsObjectFromFencedMarkdown() {
        String response = """
                ```json
                {"score": 5.5, "riskLevel": "Moderate"}
                ```
                """;

        assertEquals("{\"score\": 5.5, \"riskLevel\": \"Moderate\"}", AiResponseUtils.extractJson(response));
    }

    @Test
    void extractsObjectSurroundedByProse() {
        String response = "Here is your assessment: {\"score\": 3} — good luck!";

        assertEquals("{\"score\": 3}", AiResponseUtils.extractJson(response));
    }

    @Test
    void extractsTopLevelArrayUnlikeTheOldPremiumCleaner() {
        String response = "```\n[{\"area\": \"Docs\"}, {\"area\": \"Tests\"}]\n```";

        assertEquals("[{\"area\": \"Docs\"}, {\"area\": \"Tests\"}]", AiResponseUtils.extractJson(response));
    }

    @Test
    void nullResponseYieldsEmptyObject() {
        assertEquals("{}", AiResponseUtils.extractJson(null));
    }

    @Test
    void responseWithoutJsonIsReturnedTrimmed() {
        assertEquals("no json here", AiResponseUtils.extractJson("  no json here  "));
    }

    @Test
    void extractContentIsNullSafe() {
        assertEquals("", AiResponseUtils.extractContent(null));
    }

    @Test
    void clampScoreHandlesRangeAndNonFiniteValues() {
        assertEquals(0.0, AiResponseUtils.clampScore(-1.2));
        assertEquals(10.0, AiResponseUtils.clampScore(12.3));
        assertEquals(4.6, AiResponseUtils.clampScore(4.6));
        assertEquals(0.0, AiResponseUtils.clampScore(Double.NaN));
        assertEquals(0.0, AiResponseUtils.clampScore(Double.POSITIVE_INFINITY));
    }
}
