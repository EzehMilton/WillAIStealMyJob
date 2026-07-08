package com.chikere.jobai.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ACTUATOR")
                        .anyRequest().permitAll())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .ignoringRequestMatchers("/stripe/webhook")
                )
                .formLogin(formLogin -> formLogin.disable())
                // Basic auth exists solely for the actuator endpoints; public routes stay permitAll
                .httpBasic(httpBasic -> {})
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(ct -> {})
                        .httpStrictTransportSecurity(hsts -> hsts
                                .maxAgeInSeconds(31_536_000)
                                .includeSubDomains(true))
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; " +
                                // Bootstrap JS loaded from jsDelivr on index + result pages
                                "script-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net; " +
                                // Bootstrap CSS + Google Fonts CSS
                                "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com https://cdn.jsdelivr.net; " +
                                // Google Fonts files
                                "font-src 'self' https://fonts.gstatic.com; " +
                                "img-src 'self' data:; " +
                                "connect-src 'self'; " +
                                "frame-ancestors 'none'"
                        ))
                )
                .build();
    }

    /**
     * The only account is the actuator user, configured via ACTUATOR_USER/ACTUATOR_PASSWORD.
     * When unset, metrics endpoints stay locked (every login attempt fails).
     */
    @Bean
    UserDetailsService userDetailsService(@Value("${app.actuator.user:}") String actuatorUser,
                                          @Value("${app.actuator.password:}") String actuatorPassword) {
        if (!actuatorUser.isBlank() && !actuatorPassword.isBlank()) {
            return new InMemoryUserDetailsManager(User.withUsername(actuatorUser)
                    .password("{noop}" + actuatorPassword)
                    .roles("ACTUATOR")
                    .build());
        }
        return username -> {
            throw new UsernameNotFoundException(username);
        };
    }
}
