package com.chikere.jobai.configuration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Emits the Content-Security-Policy with a per-request script nonce, replacing the static
 * header (and its 'unsafe-inline' script-src) previously set in SecurityConfig. Inline
 * <script> tags must carry th:attr="nonce=${cspNonce}" (exposed by CspNonceModelAdvice).
 *
 * style-src deliberately keeps 'unsafe-inline': the templates use inline style="" attributes,
 * and per the CSP spec adding a nonce to a directive makes browsers ignore 'unsafe-inline'
 * in it — noncing styles would break every style attribute.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CspNonceFilter extends OncePerRequestFilter {

    public static final String NONCE_ATTRIBUTE = "cspNonce";

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String nonce = generateNonce();
        request.setAttribute(NONCE_ATTRIBUTE, nonce);
        response.setHeader("Content-Security-Policy",
                "default-src 'self'; "
                        // Bootstrap JS loaded from jsDelivr on index + result pages
                        + "script-src 'self' 'nonce-" + nonce + "' https://cdn.jsdelivr.net; "
                        // Bootstrap CSS + Google Fonts CSS; inline style attributes still used
                        + "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com https://cdn.jsdelivr.net; "
                        // Google Fonts files
                        + "font-src 'self' https://fonts.gstatic.com; "
                        + "img-src 'self' data:; "
                        + "connect-src 'self'; "
                        + "frame-ancestors 'none'");
        filterChain.doFilter(request, response);
    }

    private String generateNonce() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
