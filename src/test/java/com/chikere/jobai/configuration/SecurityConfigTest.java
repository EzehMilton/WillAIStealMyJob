package com.chikere.jobai.configuration;

import com.chikere.jobai.service.AnalyticsService;
import com.chikere.jobai.controller.AnalyticsController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AnalyticsController.class)
@Import(SecurityConfig.class)
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AnalyticsService analyticsService;

    @Test
    void postWithoutCsrfTokenIsRejected() throws Exception {
        mockMvc.perform(post("/analytics/event")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "visitorId": "visitor-123",
                                  "eventType": "visit"
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void postWithCsrfTokenIsAccepted() throws Exception {
        mockMvc.perform(post("/analytics/event")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "visitorId": "visitor-123",
                                  "eventType": "visit"
                                }
                                """))
                .andExpect(status().isOk());
    }
}
