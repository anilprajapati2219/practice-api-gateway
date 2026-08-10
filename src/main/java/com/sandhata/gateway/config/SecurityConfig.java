package com.sandhata.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeExchange(exchanges -> exchanges
                        // Allow everything without token for now
                        .anyExchange().permitAll()
                );

        return http.build();
    }
}
//package com.sandhata.gateway.config;
//
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
//import org.springframework.security.config.web.server.ServerHttpSecurity;
//import org.springframework.security.web.server.SecurityWebFilterChain;
//
///**
// * Security configuration for the API Gateway.
// *
// * All requests must have a valid Azure AD JWT token
// * except public paths like health check and azure config endpoint.
// *
// * The actual role-based access control is handled in JwtAuthenticationFilter.
// */
//@Configuration
//@EnableWebFluxSecurity
//public class SecurityConfig {
//
//    @Bean
//    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
//        http
//                .csrf(csrf -> csrf.disable())
//                .authorizeExchange(exchanges -> exchanges
//                        // Public endpoints — no auth needed
//                        .pathMatchers(
//                                "/actuator/health",
//                                "/actuator/info",
//                                "/api/config/azure"
//                        ).permitAll()
//                        // All other requests must be authenticated
//                        .anyExchange().authenticated()
//                )
//                // Use Azure AD JWT tokens for authentication
//                .oauth2ResourceServer(oauth2 -> oauth2
//                        .jwt(jwt -> {})
//                );
//
//        return http.build();
//    }
//}
