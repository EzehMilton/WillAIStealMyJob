package com.chikere.jobai.service;

import com.chikere.jobai.model.GenerateReportRequest;
import com.chikere.jobai.model.PremiumReport;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PremiumReportAiServiceMockedChatClientTest {

    private final JourneyConfigRegistry journeyConfigRegistry = new JourneyConfigRegistry(800, 450, 350);

    @Test
    void generateParsesMockedPremiumJsonWithoutCallingRealAi() {
        MockChatClient mockChatClient = mockChatClient("""
                {
                  "premiumScore": 8.2,
                  "premiumRiskLevel": "High",
                  "scoreRationale": "The official score is justified by routine implementation and documentation in the detailed exposure map.",
                  "executiveSummary": "A detailed mocked report summary.",
                  "taskExposureMap": [
                    {
                      "area": "Documentation",
                      "exposurePercent": 82,
                      "exposure": "High",
                      "timelineLabel": "Now",
                      "reason": "Routine structured writing is highly automatable."
                    }
                  ],
                  "skillCards": [
                    {
                      "icon": "S",
                      "name": "AI workflow design",
                      "description": "Use AI tools deliberately.",
                      "priority": "High",
                      "priorityNote": "Useful immediately"
                    }
                  ],
                  "adjacentRoles": [
                    {
                      "targetRole": "Platform Lead",
                      "salaryBand": "Higher",
                      "aiResilience": "High",
                      "transitionDifficulty": "Medium",
                      "reason": "More judgement and ownership.",
                      "skillsToBuild": ["Architecture", "Stakeholder leadership"]
                    }
                  ]
                }
                """);
        PremiumReportAiService service = newService(mockChatClient.client());

        PremiumReport report = service.generate(request("profession", "Java Developer"));

        assertNotNull(report.getReportId());
        assertEquals(5.5, report.getScore());
        assertEquals("Moderate", report.getRiskLevel());
        assertEquals(null, report.getPremiumScore());
        assertEquals(null, report.getPremiumRiskLevel());
        assertEquals("The official score is justified by routine implementation and documentation in the detailed exposure map.", report.getScoreRationale());
        assertEquals("A detailed mocked report summary.", report.getExecutiveSummary());
        assertEquals(1, report.getTaskExposureMap().size());
        assertEquals("Documentation", report.getTaskExposureMap().getFirst().getArea());
        assertNotNull(report.getGenerationMetrics());
        assertEquals("gpt-5.4-mini", report.getGenerationMetrics().getModel());
        assertEquals(500, report.getGenerationMetrics().getPromptTokens());
        assertEquals(250, report.getGenerationMetrics().getCompletionTokens());
        assertTrue(mockChatClient.prompt().contains("REPORT QUALITY BOOSTER"));
        assertTrue(mockChatClient.prompt().contains("PROFESSIONAL JOURNEY"));
        assertTrue(mockChatClient.prompt().contains("The official score is 5.5/10"));
        assertTrue(mockChatClient.prompt().contains("Do not output premiumScore or premiumRiskLevel"));
    }

    @Test
    void promptIncludesJourneySpecificInstructionsForAllModes() {
        PremiumReportAiService service = newService(mockChatClient("{}").client());

        String professionPrompt = service.buildPremiumPrompt(request("profession", "Java Developer"));
        String coursePrompt = service.buildPremiumPrompt(request("course", "Computer Science"));
        String aLevelPrompt = service.buildPremiumPrompt(request("a_level", "Maths, Biology, Psychology"));

        assertTrue(professionPrompt.contains("PROFESSIONAL JOURNEY"));
        assertTrue(professionPrompt.contains("career survival plan"));
        assertTrue(professionPrompt.contains("CRITICAL SCORE RULE"));
        assertTrue(coursePrompt.contains("UNIVERSITY_STUDENT JOURNEY"));
        assertTrue(coursePrompt.contains("degree and early-career strategy report"));
        assertTrue(aLevelPrompt.contains("A_LEVEL_UNDECIDED JOURNEY"));
        assertTrue(aLevelPrompt.contains("suggested subject/A-Level combinations"));
        assertFalse(aLevelPrompt.contains("Treat the subject as the user's current profession or role."));
    }

    private PremiumReportAiService newService(ChatClient chatClient) {
        return new PremiumReportAiService(
                chatClient,
                new GenerationMetricsService("gpt-5.4", "gpt-5.4-mini", 2.5, 10.0, 0.4, 1.6, 0.79),
                journeyConfigRegistry,
                new DefaultResourceLoader(),
                "gpt-5.4-mini"
        );
    }

    private GenerateReportRequest request(String mode, String profession) {
        GenerateReportRequest request = new GenerateReportRequest();
        request.setMode(mode);
        request.setProfession(profession);
        request.setDescription("Original details preserved from the intake.");
        request.setScore(5.5);
        request.setRiskLevel("Moderate");
        return request;
    }

    private MockChatClient mockChatClient(String json) {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);

        when(client.prompt(promptCaptor.capture())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.chatResponse()).thenReturn(chatResponse(json));

        return new MockChatClient(client, promptCaptor);
    }

    private ChatResponse chatResponse(String json) {
        Usage usage = new Usage() {
            @Override
            public Integer getPromptTokens() {
                return 500;
            }

            @Override
            public Integer getCompletionTokens() {
                return 250;
            }

            @Override
            public Object getNativeUsage() {
                return null;
            }
        };
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .model("gpt-5.4-mini")
                .usage(usage)
                .build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(json))), metadata);
    }

    private record MockChatClient(ChatClient client, ArgumentCaptor<String> promptCaptor) {
        String prompt() {
            return promptCaptor.getValue();
        }
    }
}
