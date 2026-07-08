package com.chikere.jobai.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.actuator.user=metrics-user",
        "app.actuator.password=metrics-pass"
})
@AutoConfigureMockMvc
class ActuatorMetricsSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void metricsWithoutCredentialsIsUnauthorized() throws Exception {
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void metricsWithConfiguredCredentialsIsAccessible() throws Exception {
        mockMvc.perform(get("/actuator/metrics")
                        .with(httpBasic("metrics-user", "metrics-pass")))
                .andExpect(status().isOk());
    }

    @Test
    void wrongCredentialsAreRejected() throws Exception {
        mockMvc.perform(get("/actuator/metrics")
                        .with(httpBasic("metrics-user", "wrong")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void healthRemainsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }
}
