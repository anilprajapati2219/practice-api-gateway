package com.sandhata.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.stereotype.Component;

/**
 * Gateway filter factory referenced in application.yml as "AddUserContextHeader".
 * This filter is applied per-route to ensure user context headers
 * set by JwtAuthenticationFilter are forwarded to the backend.
 */
@Slf4j
@Component
public class AddUserContextHeaderFilter
        extends AbstractGatewayFilterFactory<AddUserContextHeaderFilter.Config> {

    public AddUserContextHeaderFilter() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            // Headers are already added by JwtAuthenticationFilter
            // This filter just logs for debugging
            String userEmail = exchange.getRequest().getHeaders().getFirst("X-User-Email");
            String userRole = exchange.getRequest().getHeaders().getFirst("X-User-Role");
            log.debug("Forwarding request with user: {} role: {}", userEmail, userRole);
            return chain.filter(exchange);
        };
    }

    public static class Config {
        // No config needed
    }
}
