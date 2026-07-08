package com.chikere.jobai.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End to end: the CSP header carries a script nonce, and the rendered page's inline
 * <script> tags carry the same nonce — otherwise browsers would refuse to run them.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CspHeaderIntegrationTest {

    private static final Pattern NONCE = Pattern.compile("'nonce-([A-Za-z0-9+/=]+)'");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void renderedPageScriptNoncesMatchTheHeader() throws Exception {
        MvcResult result = mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn();

        String csp = result.getResponse().getHeader("Content-Security-Policy");
        Matcher matcher = NONCE.matcher(csp);
        assertTrue(matcher.find(), "no nonce in CSP header: " + csp);
        String nonce = matcher.group(1);

        String html = result.getResponse().getContentAsString();
        assertTrue(html.contains("nonce=\"" + nonce + "\""),
                "rendered page does not carry the header's nonce");
        assertTrue(!csp.substring(csp.indexOf("script-src"), csp.indexOf(';', csp.indexOf("script-src")))
                .contains("'unsafe-inline'"));
    }
}
