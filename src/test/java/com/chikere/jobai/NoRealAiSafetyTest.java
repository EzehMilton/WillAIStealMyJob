package com.chikere.jobai;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest
class NoRealAiSafetyTest {

    @Value("${spring.ai.openai.api-key}")
    private String openAiApiKey;

    @Test
    void testProfileUsesNonRealOpenAiKey() {
        assertEquals("test-key", openAiApiKey);
        assertFalse(openAiApiKey.startsWith("sk-"));
    }
}
