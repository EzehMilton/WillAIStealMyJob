package com.chikere.jobai.configuration;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Exposes the per-request CSP nonce to every Thymeleaf view as ${cspNonce}
 * (Thymeleaf 3.1 removed direct #request access from templates).
 */
@ControllerAdvice
public class CspNonceModelAdvice {

    @ModelAttribute
    public void exposeCspNonce(HttpServletRequest request, Model model) {
        Object nonce = request.getAttribute(CspNonceFilter.NONCE_ATTRIBUTE);
        model.addAttribute(CspNonceFilter.NONCE_ATTRIBUTE, nonce != null ? nonce : "");
    }
}
