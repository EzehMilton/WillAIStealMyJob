package com.chikere.jobai.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CspNonceFilterTest {

    private final CspNonceFilter filter = new CspNonceFilter();

    @Test
    void scriptSrcCarriesNonceInsteadOfUnsafeInline() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String csp = response.getHeader("Content-Security-Policy");
        assertNotNull(csp);
        String scriptSrc = csp.substring(csp.indexOf("script-src"), csp.indexOf(';', csp.indexOf("script-src")));
        assertTrue(scriptSrc.contains("'nonce-"), "script-src missing nonce: " + scriptSrc);
        assertFalse(scriptSrc.contains("'unsafe-inline'"), "script-src still allows unsafe-inline");
        // style-src keeps 'unsafe-inline' (inline style attributes) and must NOT get a nonce,
        // which would make browsers ignore unsafe-inline
        String styleSrc = csp.substring(csp.indexOf("style-src"), csp.indexOf(';', csp.indexOf("style-src")));
        assertTrue(styleSrc.contains("'unsafe-inline'"));
        assertFalse(styleSrc.contains("'nonce-"));
    }

    @Test
    void nonceIsExposedToTheRequestAndMatchesTheHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String nonce = (String) request.getAttribute(CspNonceFilter.NONCE_ATTRIBUTE);
        assertNotNull(nonce);
        assertTrue(response.getHeader("Content-Security-Policy").contains("'nonce-" + nonce + "'"));
    }

    @Test
    void everyRequestGetsAFreshNonce() throws Exception {
        MockHttpServletRequest first = new MockHttpServletRequest("GET", "/");
        MockHttpServletRequest second = new MockHttpServletRequest("GET", "/");
        filter.doFilter(first, new MockHttpServletResponse(), new MockFilterChain());
        filter.doFilter(second, new MockHttpServletResponse(), new MockFilterChain());

        assertNotEquals(first.getAttribute(CspNonceFilter.NONCE_ATTRIBUTE),
                second.getAttribute(CspNonceFilter.NONCE_ATTRIBUTE));
        assertEquals(24, ((String) first.getAttribute(CspNonceFilter.NONCE_ATTRIBUTE)).length());
    }
}
