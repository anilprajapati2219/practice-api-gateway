package com.sandhata.gateway.filter;

import com.sandhata.gateway.model.UserRole;
import com.sandhata.gateway.service.RoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private final RoleService roleService;
    private final ReactiveJwtDecoder jwtDecoder;

    private static final List<String> PUBLIC_PATHS = List.of(
            "/actuator/health",
            "/actuator/info",
            "/api/config/azure"
    );

    private static final List<String> ADMIN_ONLY_PATHS = List.of(
            "/api/announcements/add"
    );

    private static final List<String> MANAGER_PATHS = List.of(
            "/api/availability-panel"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange,
                             GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().toString();
        String method = exchange.getRequest().getMethod().name();

        log.debug("Gateway filter — {} {}", method, path);

        // Allow public paths
        if (isPublicPath(path)) {
            return chain.filter(exchange);
        }

        // Allow non-API paths (frontend pages)
        if (!path.startsWith("/api/")) {
            return chain.filter(exchange);
        }

        // Read JWT from Authorization header
        // MSAL sends this automatically with every API call
        String authHeader = exchange.getRequest()
                .getHeaders()
                .getFirst("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("No Bearer token for path: {}", path);
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        String tokenValue = authHeader.substring(7);

        // Validate JWT and get user info
        return jwtDecoder.decode(tokenValue)
                .flatMap(jwt -> {
                    String email = extractEmail(jwt);
                    String name = jwt.getClaimAsString("name");

                    if (email == null) {
                        log.warn("No email in JWT for path: {}", path);
                        exchange.getResponse()
                                .setStatusCode(HttpStatus.UNAUTHORIZED);
                        return exchange.getResponse().setComplete();
                    }

                    // Fetch role from backend by email
                    return roleService.getUserContext(email, name)
                            .flatMap(userContext -> {
                                // Check role based access
                                if (!hasAccess(
                                        userContext.getRole(), path, method)) {
                                    log.warn(
                                            "Access denied user:{} role:{} path:{}",
                                            email, userContext.getRole(), path);
                                    exchange.getResponse()
                                            .setStatusCode(HttpStatus.FORBIDDEN);
                                    return exchange.getResponse().setComplete();
                                }

                                // Forward with user context headers
                                ServerWebExchange mutated = exchange.mutate()
                                        .request(exchange.getRequest()
                                                .mutate()
                                                .header("X-User-Email", email)
                                                .header("X-User-Name",
                                                        name != null ? name : "")
                                                .header("X-User-Role",
                                                        userContext.getRole()
                                                                .name())
                                                .header("X-User-Practice",
                                                        userContext.getPractice()
                                                                != null
                                                                ? userContext
                                                                  .getPractice()
                                                                : "")
                                                .build())
                                        .build();

                                log.debug("Forwarding user:{} role:{}",
                                        email, userContext.getRole());
                                return chain.filter(mutated);
                            });
                })
                .onErrorResume(error -> {
                    log.error("JWT validation failed: {}",
                            error.getMessage());
                    exchange.getResponse()
                            .setStatusCode(HttpStatus.UNAUTHORIZED);
                    return exchange.getResponse().setComplete();
                });
    }

    private boolean hasAccess(UserRole role, String path, String method) {
        if (role == UserRole.GUEST) {
            return method.equals("GET")
                    && !isAdminOnlyPath(path)
                    && !isManagerPath(path);
        }
        if (role == UserRole.VIEWER) {
            return method.equals("GET");
        }
        if (role == UserRole.MANAGER) {
            return !isAdminOnlyPath(path);
        }
        if (role == UserRole.ADMIN) {
            return true;
        }
        return false;
    }

    private boolean isPublicPath(String path) {
        return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
    }

    private boolean isAdminOnlyPath(String path) {
        return ADMIN_ONLY_PATHS.stream().anyMatch(path::startsWith);
    }

    private boolean isManagerPath(String path) {
        return MANAGER_PATHS.stream().anyMatch(path::startsWith);
    }

    private String extractEmail(Jwt jwt) {
        String email = jwt.getClaimAsString("preferred_username");
        if (email == null) email = jwt.getClaimAsString("email");
        if (email == null) email = jwt.getClaimAsString("upn");
        return email;
    }

    @Override
    public int getOrder() {
        return -1;
    }
}