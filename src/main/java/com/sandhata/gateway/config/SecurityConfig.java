package com.sandhata.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Security config for the gateway.
 *
 * We intentionally do NOT use Spring Security's OAuth2 Resource Server here
 * — this gateway never validates Azure AD tokens directly. Instead it runs
 * the BFF pattern: {@code AuthCallbackController} exchanges the Azure auth
 * code for tokens server-side and mints its own session JWT, which
 * {@code JwtAuthenticationFilter} (a Spring Cloud Gateway GlobalFilter, see
 * that class) validates on every request and uses to enforce role-based
 * access.
 *
 * So this filter chain just gets out of the way (permitAll, no CSRF — CSRF
 * is mitigated by the session cookie's SameSite=Lax attribute plus the fact
 * that state-changing endpoints require the role check in
 * JwtAuthenticationFilter) and lets that GlobalFilter do the real
 * authentication/authorization.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeExchange(exchanges -> exchanges
                        // Actual authentication/authorization happens in
                        // JwtAuthenticationFilter, which runs earlier than
                        // routing for every request. Nothing left for
                        // Spring Security itself to gate here.
                        .anyExchange().permitAll()
                );

        return http.build();
    }
}
