package com.chikere.jobai.service;

import com.chikere.jobai.model.JobRiskAssessment;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.io.DefaultResourceLoader;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobAiServiceMockedChatClientTest {

    private final JourneyConfigRegistry journeyConfigRegistry = new JourneyConfigRegistry(800, 450, 350);

    @Test
    void professionAssessmentUsesMiniModelAndScoringLayerInsteadOfAiScore() {
        MockChatClient professionalClient = mockChatClient("""
                {
                  "score": 0.1,
                  "riskLevel": "Low",
                  "summary": "This client should not be used",
                  "assessment": "This client should not be used"
                }
                """, "gpt-5.4");
        MockChatClient miniClient = mockChatClient("""
                {
                  "score": 9.9,
                  "riskLevel": "High",
                  "summary": "Wrong model summary",
                  "assessment": "AI can assist some tasks. Deeper changes need review."
                }
                """, "gpt-5.4-mini");
        JobAiService service = newService(professionalClient.client(), miniClient.client());

        JobRiskAssessment result = service.assessJobRisk(
                "profession",
                "Choir singer",
                "Live performance, audience connection, rehearsals, and real-time human coordination"
        );

        assertEquals("Low", result.getRiskLevel());
        assertTrue(result.getScore() <= 3.4);
        assertTrue(result.getSummary().contains("Choir singer"));
        assertEquals("AI can assist some tasks. Deeper changes need review.", result.getAssessment());
        assertEquals("gpt-5.4-mini", result.getGenerationMetrics().getModel());
        assertTrue(miniClient.prompt().contains("Profession: Choir singer"));
        verify(professionalClient.client(), never()).prompt(anyString());
    }

    @Test
    void courseAssessmentUsesCoursePromptAndMiniModel() {
        MockChatClient professionalClient = mockChatClient("{}", "gpt-5.4");
        MockChatClient miniClient = mockChatClient("""
                {
                  "score": 1.0,
                  "riskLevel": "Low",
                  "summary": "Course narrative",
                  "assessment": "AI affects related paths. Hidden risks remain."
                }
                """, "gpt-5.4-mini");
        JobAiService service = newService(professionalClient.client(), miniClient.client());

        JobRiskAssessment result = service.assessJobRisk(
                "course",
                "Computer Science",
                "I want to become a software engineer and build digital products"
        );

        assertEquals("Moderate", result.getRiskLevel());
        assertEquals("gpt-5.4-mini", result.getGenerationMetrics().getModel());
        assertTrue(miniClient.prompt().contains("Course/Degree: Computer Science"));
        assertTrue(miniClient.prompt().contains("Expected Career Path"));
        verify(professionalClient.client(), never()).prompt(anyString());
    }

    @Test
    void aLevelAssessmentUsesALevelPromptAndMiniModel() {
        MockChatClient professionalClient = mockChatClient("{}", "gpt-5.4");
        MockChatClient miniClient = mockChatClient("""
                {
                  "score": 8.0,
                  "riskLevel": "High",
                  "summary": "A-level narrative",
                  "assessment": "AI affects future paths. Hidden risks remain."
                }
                """, "gpt-5.4-mini");
        JobAiService service = newService(professionalClient.client(), miniClient.client());

        JobRiskAssessment result = service.assessJobRisk(
                "a_level",
                "Maths, Biology, Psychology",
                "I enjoy science, people, problem solving, and open future options"
        );

        assertTrue(result.getScore() >= 0.0 && result.getScore() <= 10.0);
        assertEquals("gpt-5.4-mini", result.getGenerationMetrics().getModel());
        assertTrue(miniClient.prompt().contains("Interests and Possible Subjects: Maths, Biology, Psychology"));
        assertTrue(miniClient.prompt().contains("Year 11, GCSE, or A-Level student"));
        verify(professionalClient.client(), never()).prompt(anyString());
    }

    @Test
    void aiCircuitOpensAfterRepeatedFailuresAndFailsFast() {
        ChatClient failingClient = failingChatClient();
        JobAiService service = newService(failingClient, 2, Duration.ofMinutes(1), 10);

        assertThrows(RuntimeException.class, () -> service.assessJobRisk(
                "profession",
                "Teacher",
                "Planning lessons and supporting students"
        ));
        assertThrows(RuntimeException.class, () -> service.assessJobRisk(
                "profession",
                "Teacher",
                "Planning lessons and supporting students"
        ));

        assertThrows(JobAiService.AiCircuitOpenException.class, () -> service.assessJobRisk(
                "profession",
                "Teacher",
                "Planning lessons and supporting students"
        ));
        verify(failingClient, times(2)).prompt(anyString());
    }

    private JobAiService newService(ChatClient professionalClient, ChatClient miniClient) {
        return new JobAiService(
                miniClient,
                new DefaultResourceLoader(),
                new GenerationMetricsService("gpt-5.4", "gpt-5.4-mini", 2.5, 10.0, 0.75, 4.50, 0.79),
                journeyConfigRegistry,
                new RiskScoringService(new RiskDimensionCalculator(), new RiskAdjustmentService(), new RiskSanityValidator()),
                "gpt-5.4-mini"
        );
    }

    private JobAiService newService(ChatClient miniClient,
                                    int failureThreshold,
                                    Duration openDuration,
                                    int maxConcurrentAiCalls) {
        return new JobAiService(
                miniClient,
                new DefaultResourceLoader(),
                new GenerationMetricsService("gpt-5.4", "gpt-5.4-mini", 2.5, 10.0, 0.75, 4.50, 0.79),
                journeyConfigRegistry,
                new RiskScoringService(new RiskDimensionCalculator(), new RiskAdjustmentService(), new RiskSanityValidator()),
                "gpt-5.4-mini",
                failureThreshold,
                openDuration,
                maxConcurrentAiCalls
        );
    }

    private MockChatClient mockChatClient(String json, String model) {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);

        when(client.prompt(promptCaptor.capture())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.chatResponse()).thenReturn(chatResponse(json, model));

        return new MockChatClient(client, promptCaptor);
    }

    private ChatClient failingChatClient() {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);

        when(client.prompt(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenThrow(new RuntimeException("OpenAI unavailable"));

        return client;
    }

    private ChatResponse chatResponse(String json, String model) {
        Usage usage = usage(120, 45);
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .model(model)
                .usage(usage)
                .build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(json))), metadata);
    }

    private Usage usage(int promptTokens, int completionTokens) {
        return new Usage() {
            @Override
            public Integer getPromptTokens() {
                return promptTokens;
            }

            @Override
            public Integer getCompletionTokens() {
                return completionTokens;
            }

            @Override
            public Object getNativeUsage() {
                return null;
            }
        };
    }

    private record MockChatClient(ChatClient client, ArgumentCaptor<String> promptCaptor) {
        String prompt() {
            return promptCaptor.getValue();
        }
    }
}
